param([string]$Keytool = 'keytool', [string]$IssuerBase = 'http://localhost:18080')
$ErrorActionPreference = 'Stop'
$projectDirectory = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$secretDirectory = Join-Path $projectDirectory '.local-secrets'
git -C $projectDirectory check-ignore -q '.local-secrets/credentials.json'
if ($LASTEXITCODE -ne 0) { throw 'Secret directory must be ignored by Git before generation' }
if ($IssuerBase -notmatch '^http://(?:localhost|127\.0\.0\.1):[0-9]{1,5}$') { throw 'Only loopback local issuer is allowed' }
New-Item -ItemType Directory -Force -Path $secretDirectory | Out-Null
$names = @('postgres-admin','postgres-owner','postgres-api','postgres-worker','postgres-keycloak','mongo-admin','mongo-activity','kafka-store','kafka-admin','kafka-worker','kafka-activity','redis','keycloak-admin','alice','operator-client')
$credentialsFile = Join-Path $secretDirectory 'credentials.json'
if (Test-Path -LiteralPath $credentialsFile) {
    $credentialMap = Get-Content -LiteralPath $credentialsFile -Raw | ConvertFrom-Json -AsHashtable
} else {
    $credentialMap = @{}
    foreach ($name in $names) { $credentialMap[$name] = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(20)).ToLowerInvariant() }
    $credentialMap | ConvertTo-Json | Set-Content -LiteralPath $credentialsFile -Encoding utf8NoBOM
}
foreach ($name in $names) {
    if (-not $credentialMap.ContainsKey($name)) { $credentialMap[$name] = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(20)).ToLowerInvariant() }
    if ($credentialMap[$name] -notmatch '^[0-9a-f]{40}$') { throw 'Invalid bounded local credential material' }
    Set-Content -LiteralPath (Join-Path $secretDirectory "$name.password") -Value $credentialMap[$name] -NoNewline -Encoding ascii
}
$credentialMap | ConvertTo-Json | Set-Content -LiteralPath $credentialsFile -Encoding utf8NoBOM
Set-Content -LiteralPath (Join-Path $secretDirectory 'mongo.key') -Value $credentialMap['mongo-admin'] -NoNewline -Encoding ascii
$store = Join-Path $secretDirectory 'server.p12'
if (-not (Test-Path -LiteralPath $store)) {
    & $Keytool -genkeypair -alias broker -keyalg RSA -keysize 2048 -sigalg SHA256withRSA -validity 90 -dname 'CN=kafka' -ext 'SAN=dns:kafka,dns:localhost,dns:kafka.dtp-lab.svc,dns:kafka.dtp-lab.svc.cluster.local,ip:127.0.0.1' -storetype PKCS12 -keystore $store -storepass $credentialMap['kafka-store'] -keypass $credentialMap['kafka-store'] *> (Join-Path $secretDirectory 'keytool.log')
    if ($LASTEXITCODE -ne 0) { throw 'Local server certificate generation failed' }
    & $Keytool -exportcert -alias broker -keystore $store -storepass $credentialMap['kafka-store'] -file (Join-Path $secretDirectory 'broker.cer') *> (Join-Path $secretDirectory 'keytool-export.log')
    if ($LASTEXITCODE -ne 0) { throw 'Local certificate export failed' }
    & $Keytool -importcert -noprompt -alias broker -storetype PKCS12 -keystore (Join-Path $secretDirectory 'truststore.p12') -storepass $credentialMap['kafka-store'] -file (Join-Path $secretDirectory 'broker.cer') *> (Join-Path $secretDirectory 'keytool-import.log')
    if ($LASTEXITCODE -ne 0) { throw 'Local truststore generation failed' }
}
function Write-PrivateEnv([string]$Name, [hashtable]$Values) {
    $lines = $Values.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Key)=$($_.Value)" }
    Set-Content -LiteralPath (Join-Path $secretDirectory $Name) -Value $lines -Encoding utf8NoBOM
}
$issuer = "$IssuerBase/realms/dtp-local"
$jwks = 'http://keycloak:8080/realms/dtp-local/protocol/openid-connect/certs'
Write-PrivateEnv 'api.env' @{ DTP_DATABASE_URL='jdbc:postgresql://postgres:5432/dtp';DTP_DATABASE_USER='dtp_api_login';DTP_DATABASE_PASSWORD=$credentialMap['postgres-api'];DTP_REDIS_HOST='redis';DTP_REDIS_PORT='6379';DTP_REDIS_PASSWORD=$credentialMap['redis'];DTP_ISSUER=$issuer;DTP_JWK_SET_URI=$jwks;DTP_PORT='8085';SPRING_PROFILES_ACTIVE='container' }
Write-PrivateEnv 'worker.env' @{ DTP_DATABASE_URL='jdbc:postgresql://postgres:5432/dtp';DTP_DATABASE_USER='dtp_worker_login';DTP_DATABASE_PASSWORD=$credentialMap['postgres-worker'];DTP_KAFKA_BOOTSTRAP_SERVERS='kafka:9093';DTP_KAFKA_WORKER_PASSWORD=$credentialMap['kafka-worker'];DTP_KAFKA_TRUSTSTORE='/run/secrets/truststore.p12';DTP_KAFKA_TRUSTSTORE_PASSWORD=$credentialMap['kafka-store'];DTP_PORT='8090';SPRING_PROFILES_ACTIVE='secure,container' }
Write-PrivateEnv 'activity.env' @{ DTP_MONGO_URI="mongodb://dtp_activity:$($credentialMap['mongo-activity'])@mongo:27017/dtp_activity?authSource=dtp_activity&replicaSet=rs0";DTP_KAFKA_BOOTSTRAP_SERVERS='kafka:9093';DTP_KAFKA_ACTIVITY_PASSWORD=$credentialMap['kafka-activity'];DTP_KAFKA_TRUSTSTORE='/run/secrets/truststore.p12';DTP_KAFKA_TRUSTSTORE_PASSWORD=$credentialMap['kafka-store'];DTP_ISSUER=$issuer;DTP_JWK_SET_URI=$jwks;DTP_PORT='8086';DTP_ACTIVITY_PREFIX='local';DTP_ACTIVITY_GROUP='activity-local';SPRING_PROFILES_ACTIVE='secure,container' }
Write-PrivateEnv 'migrate.env' @{ DTP_ENVIRONMENT='local';DTP_ALLOW_MIGRATE='true';DTP_DATABASE_URL='jdbc:postgresql://postgres:5432/dtp';DTP_DATABASE_USER='dtp_owner';DTP_DATABASE_PASSWORD=$credentialMap['postgres-owner'] }
Write-PrivateEnv 'keycloak.env' @{ KC_DB='postgres';KC_DB_URL='jdbc:postgresql://postgres:5432/keycloak';KC_DB_USERNAME='dtp_keycloak';KC_DB_PASSWORD=$credentialMap['postgres-keycloak'];KC_BOOTSTRAP_ADMIN_USERNAME='dtp-admin';KC_BOOTSTRAP_ADMIN_PASSWORD=$credentialMap['keycloak-admin'];KC_HEALTH_ENABLED='true';KC_HTTP_ENABLED='true';KC_HOSTNAME=$IssuerBase;KC_HOSTNAME_STRICT='true';KC_HOSTNAME_BACKCHANNEL_DYNAMIC='true' }
$realmTemplate = Get-Content -LiteralPath (Join-Path $projectDirectory 'infra/keycloak/dtp-local-realm.json') -Raw
$realmTemplate = $realmTemplate.Replace('${DTP_TENANT_ID}', '10000000-0000-0000-0000-000000000001').Replace('${DTP_USER_PASSWORD}', $credentialMap['alice']).Replace('${DTP_OPERATOR_SECRET}', $credentialMap['operator-client'])
if ($realmTemplate.Contains('${')) { throw 'Unresolved local realm binding; no import is allowed' }
$realm = $realmTemplate | ConvertFrom-Json
foreach ($user in $realm.users) { foreach ($credential in $user.credentials) { if ($credential.type -eq 'password') { $credential.value = $credentialMap['alice'] } } }
$realm | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath (Join-Path $secretDirectory 'dtp-local-realm.json') -Encoding utf8NoBOM
$serverProperties = @"
process.roles=broker,controller
node.id=1
controller.quorum.voters=1@127.0.0.1:9094
controller.listener.names=CONTROLLER
listeners=CLIENT://0.0.0.0:9093,CONTROLLER://127.0.0.1:9094
advertised.listeners=CLIENT://kafka:9093
listener.security.protocol.map=CLIENT:SASL_SSL,CONTROLLER:SASL_PLAINTEXT
inter.broker.listener.name=CLIENT
sasl.enabled.mechanisms=PLAIN
sasl.mechanism.inter.broker.protocol=PLAIN
sasl.mechanism.controller.protocol=PLAIN
listener.name.controller.plain.sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="admin" password="$($credentialMap['kafka-admin'])" user_admin="$($credentialMap['kafka-admin'])";
listener.name.client.plain.sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="admin" password="$($credentialMap['kafka-admin'])" user_admin="$($credentialMap['kafka-admin'])" user_worker="$($credentialMap['kafka-worker'])" user_activity="$($credentialMap['kafka-activity'])";
ssl.keystore.location=/run/secrets/server.p12
ssl.keystore.password=$($credentialMap['kafka-store'])
ssl.key.password=$($credentialMap['kafka-store'])
ssl.keystore.type=PKCS12
ssl.truststore.location=/run/secrets/truststore.p12
ssl.truststore.password=$($credentialMap['kafka-store'])
ssl.truststore.type=PKCS12
ssl.endpoint.identification.algorithm=https
authorizer.class.name=org.apache.kafka.metadata.authorizer.StandardAuthorizer
allow.everyone.if.no.acl.found=false
super.users=User:admin
auto.create.topics.enable=false
log.dirs=/var/lib/kafka/data
offsets.topic.replication.factor=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
num.partitions=3
"@
Set-Content -LiteralPath (Join-Path $secretDirectory 'kafka-server.properties') -Value $serverProperties -Encoding utf8NoBOM
$adminProperties = @"
security.protocol=SASL_SSL
sasl.mechanism=PLAIN
sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="admin" password="$($credentialMap['kafka-admin'])";
ssl.truststore.location=/run/secrets/truststore.p12
ssl.truststore.password=$($credentialMap['kafka-store'])
ssl.truststore.type=PKCS12
ssl.endpoint.identification.algorithm=https
request.timeout.ms=5000
default.api.timeout.ms=15000
"@
Set-Content -LiteralPath (Join-Path $secretDirectory 'kafka-admin.properties') -Value $adminProperties -Encoding utf8NoBOM
Set-Content -LiteralPath (Join-Path $secretDirectory 'redis.conf') -Value @('bind 0.0.0.0','protected-mode yes',"requirepass $($credentialMap['redis'])",'appendonly yes','dir /data') -Encoding utf8NoBOM
Write-Output 'Local per-process secrets and certificate material prepared; values were not printed.'
