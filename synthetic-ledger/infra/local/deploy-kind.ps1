param([Parameter(Mandatory)][string]$Kubectl, [Parameter(Mandatory)][string]$Helm, [Parameter(Mandatory)][string]$Kind,
      [Parameter(Mandatory)][string]$Python)
$ErrorActionPreference = 'Stop'
$projectDirectory = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$secretDirectory = Join-Path $projectDirectory '.local-secrets'
$labKubeconfig = Join-Path $secretDirectory 'kind.kubeconfig'
$chart = Join-Path $projectDirectory 'infra/helm/dtp'
$context = 'kind-dtp-f7'
$clusterNames = & $Kind get clusters
if ($LASTEXITCODE -ne 0 -or $clusterNames -notcontains 'dtp-f7') { throw 'Dedicated local kind cluster must exist' }
$server = & $Kubectl --kubeconfig $labKubeconfig --context $context config view --minify -o 'jsonpath={.clusters[0].cluster.server}'
if ($LASTEXITCODE -ne 0 -or $server -notmatch '^https://(?:127\.0\.0\.1|localhost):[0-9]+$') { throw 'Only the dedicated loopback kind API server is permitted' }
function Invoke-Kube([string[]]$Arguments) {
    & $Kubectl --kubeconfig $labKubeconfig --context $context -n dtp-lab @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'Local Kubernetes operation failed' }
}
& $Kubectl --kubeconfig $labKubeconfig --context $context create namespace dtp-lab --dry-run=client -o yaml |
    & $Kubectl --kubeconfig $labKubeconfig --context $context apply -f -
if ($LASTEXITCODE -ne 0) { throw 'Local namespace initialization failed' }
foreach ($image in @('dtp-ledger-api:f7','dtp-ledger-worker:f7','dtp-activity:f7','dtp-keycloak:f7')) {
    & $Kind load docker-image $image --name dtp-f7
    if ($LASTEXITCODE -ne 0) { throw 'Local image loading failed' }
}
function Write-LocalSecret([string]$Name, [string[]]$Sources) {
    # Secret YAML exists only in the pipe; it is never written to evidence/logs.
    & $Kubectl --kubeconfig $labKubeconfig --context $context -n dtp-lab create secret generic $Name @Sources --dry-run=client -o yaml |
        & $Kubectl --kubeconfig $labKubeconfig --context $context -n dtp-lab apply -f -
    if ($LASTEXITCODE -ne 0) { throw 'Local secret initialization failed' }
}
foreach ($pair in @(@('dtp-api-env','api.env'),@('dtp-worker-env','worker.env'),@('dtp-activity-env','activity.env'),@('dtp-migrate-env','migrate.env'),@('dtp-keycloak-env','keycloak.env'))) {
    Write-LocalSecret $pair[0] @("--from-env-file=$(Join-Path $secretDirectory $pair[1])")
}
function File-Sources([string[]]$Names) { return @($Names | ForEach-Object { "--from-file=$_=$(Join-Path $secretDirectory $_)" }) }
Write-LocalSecret 'dtp-postgres-bootstrap' (File-Sources @('postgres-admin.password','postgres-owner.password','postgres-api.password','postgres-worker.password','postgres-keycloak.password'))
Write-LocalSecret 'dtp-kafka-bootstrap' (File-Sources @('kafka-server.properties','kafka-admin.properties','server.p12','truststore.p12'))
Write-LocalSecret 'dtp-kafka-admin' (File-Sources @('kafka-admin.properties','truststore.p12'))
Write-LocalSecret 'dtp-kafka-client' (File-Sources @('truststore.p12'))
Write-LocalSecret 'dtp-mongo-bootstrap' (File-Sources @('mongo-admin.password','mongo-activity.password','mongo.key'))
Write-LocalSecret 'dtp-redis' (File-Sources @('redis.conf','redis.password'))
Write-LocalSecret 'dtp-keycloak-realm' (File-Sources @('dtp-local-realm.json'))
& $Helm upgrade --install dtp $chart --namespace dtp-lab --kubeconfig $labKubeconfig --kube-context $context --set application.enabled=false --set bootstrap.enabled=true --timeout 5m
if ($LASTEXITCODE -ne 0) { throw 'Local dependency/bootstrap rendering failed' }
foreach ($job in @('dtp-migrate','dtp-grants-seed','dtp-kafka-init','dtp-mongo-init')) {
    Invoke-Kube @('wait','--for=condition=complete',"job/$job",'--timeout=600s')
}
Invoke-Kube @('rollout','status','deployment/keycloak','--timeout=300s')
& $Python (Join-Path $projectDirectory 'scripts/reconcile-local-realm.py')
if ($LASTEXITCODE -ne 0) { throw 'Local synthetic realm bindings failed' }
& $Helm upgrade dtp $chart -f (Join-Path $chart 'values-local.yaml') --namespace dtp-lab --kubeconfig $labKubeconfig --kube-context $context --wait --timeout 5m
if ($LASTEXITCODE -ne 0) { throw 'Local application rollout failed' }
Invoke-Kube @('get','deployments','-o','wide')
Write-Output 'Local two-stage application rollout completed; no cloud context was used.'
