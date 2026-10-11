package dev.dtp.api;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.images.builder.Transferable;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Real OAuth service identity, signed tokens and issuer/audience validation. */
@Testcontainers
class KeycloakIdentityIT {
    private static final String SECRET = UUID.randomUUID().toString();
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String ADMIN_SECRET = UUID.randomUUID().toString();
    private static final String OPERATOR_SECRET = UUID.randomUUID().toString();
    private static final UUID TENANT = UUID.randomUUID();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Container @SuppressWarnings("resource")
    static final GenericContainer<?> IDP = new GenericContainer<>("quay.io/keycloak/keycloak@sha256:27b3230fbabb8c9ff3c66c864cf09dcd8a41aca82e5100679d109ef0eeb05741")
            .withExposedPorts(8080).withCommand("start-dev", "--import-realm", "--hostname-strict=false")
            .withEnv("DTP_TENANT_ID", TENANT.toString()).withEnv("DTP_OPERATOR_SECRET", OPERATOR_SECRET).withEnv("DTP_USER_PASSWORD", PASSWORD)
            .withCopyFileToContainer(org.testcontainers.utility.MountableFile.forHostPath(java.nio.file.Path.of("../infra/keycloak/dtp-local-realm.json").toAbsolutePath()), "/opt/keycloak/data/import/dtp-local-realm.json")
            .withCopyToContainer(Transferable.of(realm().getBytes(StandardCharsets.UTF_8)), "/opt/keycloak/data/import/dtp-realm.json")
            .waitingFor(Wait.forHttp("/realms/dtp/.well-known/openid-configuration").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)));

    @Test void serviceTokenHasSignedTenantScopeAudienceAndExplicitRole() throws Exception {
        String token = accessToken();
        var jwt = new SecurityConfiguration().decoder(issuer(), issuer() + "/protocol/openid-connect/certs", "dtp-ledger").decode(token);
        assertEquals(TENANT, ActorMapping.from(jwt).tenantId());
        assertEquals("dtp-service", ActorMapping.from(jwt).clientId());
        var authorities = RealmAuthorities.from(jwt).stream().map(a -> a.getAuthority()).toList();
        assertTrue(authorities.contains("ROLE_transfer-client"));
        assertTrue(authorities.contains("SCOPE_transfer:read"));
        assertFalse(authorities.contains("ROLE_admin"));
    }
    @Test void wrongAudienceIssuerAndModifiedSignatureRejected() throws Exception {
        String token = accessToken(); String jwks = issuer() + "/protocol/openid-connect/certs";
        assertThrows(org.springframework.security.oauth2.jwt.JwtException.class, () -> new SecurityConfiguration().decoder(issuer(), jwks, "different-service").decode(token));
        assertThrows(org.springframework.security.oauth2.jwt.JwtException.class, () -> new SecurityConfiguration().decoder(issuer() + "-other", jwks, "dtp-ledger").decode(token));
        String[] parts = token.split("\\.");
        parts[2] = (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
        String modified = String.join(".", parts);
        assertThrows(org.springframework.security.oauth2.jwt.JwtException.class, () -> new SecurityConfiguration().decoder(issuer(), jwks, "dtp-ledger").decode(modified));
    }
    @Test void secretIsRequiredAndPasswordGrantDisabled() throws Exception {
        assertEquals(401, form("grant_type=client_credentials&client_id=dtp-service&client_secret=invalid").statusCode());
        var response = form("grant_type=password&client_id=dtp-service&client_secret=" + SECRET + "&username=unused&password=unused");
        assertEquals(400, response.statusCode());
        assertEquals("unauthorized_client", JSON.readTree(response.body()).path("error").asString());
    }
    @Test void userLoginUsesAuthorizationCodePkceAndCodesAreSingleUse() throws Exception {
        var cookies = new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL);
        var browser = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
        String verifier = UUID.randomUUID().toString() + UUID.randomUUID();
        String challenge = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String state = UUID.randomUUID().toString(); String nonce = UUID.randomUUID().toString();
        String auth = issuer() + "/protocol/openid-connect/auth?client_id=dtp-web&response_type=code&redirect_uri=" + encode("http://127.0.0.1:8999/callback")
                + "&scope=" + encode("openid transfer:read") + "&state=" + state + "&nonce=" + nonce + "&code_challenge_method=S256&code_challenge=" + challenge;
        var login = browser.send(HttpRequest.newBuilder(URI.create(auth)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode());
        // Localhost is a trustworthy browser origin even over HTTP; JDK CookieManager
        // does not implement that browser exception. This normalization is fixture-only.
        assertEquals("localhost", URI.create(issuer()).getHost());
        cookies.getCookieStore().getCookies().forEach(cookie -> { cookie.setVersion(0); cookie.setSecure(false); });
        var action = java.util.regex.Pattern.compile("<form[^>]*id=\"kc-form-login\"[^>]*action=\"([^\"]+)\"").matcher(login.body());
        assertTrue(action.find(), "Login form must exist");
        var submitted = browser.send(HttpRequest.newBuilder(URI.create(action.group(1).replace("&amp;", "&"))).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("username=alice&password=" + encode(PASSWORD))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(302, submitted.statusCode(), () -> loginError(submitted.body()) + " Cookie metadata: " + cookies.getCookieStore().getCookies().stream()
                .map(c -> c.getName() + ":domain=" + c.getDomain() + ":path=" + c.getPath() + ":secure=" + c.getSecure()).toList()
                + " action host=" + URI.create(action.group(1).replace("&amp;", "&")).getHost());
        URI callback = URI.create(submitted.headers().firstValue("Location").orElseThrow());
        var parameters = new java.util.HashMap<String, String>();
        for (String parameter : callback.getRawQuery().split("&")) {
            String[] pair = parameter.split("=", 2); parameters.put(pair[0], java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        assertEquals(state, parameters.get("state"));
        String body = "grant_type=authorization_code&client_id=dtp-web&redirect_uri=" + encode("http://127.0.0.1:8999/callback") + "&code=" + encode(parameters.get("code"));
        var tokens = form(body + "&code_verifier=" + encode(verifier));
        assertEquals(200, tokens.statusCode());
        String access = JSON.readTree(tokens.body()).path("access_token").asString();
        var raw = com.nimbusds.jwt.SignedJWT.parse(access).getJWTClaimsSet();
        assertNotNull(raw.getSubject(), "Basic scope must map user subject");
        assertEquals(TENANT.toString(), raw.getStringClaim("tenant_id"));
        assertEquals("dtp-web", raw.getStringClaim("azp"));
        assertTrue(raw.getAudience().contains("dtp-ledger"));
        var jwt = new SecurityConfiguration().decoder(issuer(), issuer() + "/protocol/openid-connect/certs", "dtp-ledger").decode(access);
        assertEquals(TENANT, ActorMapping.from(jwt).tenantId());
        assertEquals("dtp-web", ActorMapping.from(jwt).clientId());
        assertTrue(RealmAuthorities.from(jwt).stream().anyMatch(a -> a.getAuthority().equals("ROLE_transfer-client")));
        var idToken = com.nimbusds.jwt.SignedJWT.parse(JSON.readTree(tokens.body()).path("id_token").asString());
        assertEquals(nonce, idToken.getJWTClaimsSet().getStringClaim("nonce"));
        // Authorization codes are single use even with the original verifier.
        assertEquals(400, form(body + "&code_verifier=" + encode(verifier)).statusCode());
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String loginError(String html) {
        var message = java.util.regex.Pattern.compile("<p[^>]*class=\"instruction\"[^>]*>(.*?)</p>", java.util.regex.Pattern.DOTALL).matcher(html);
        return message.find() ? message.group(1).replaceAll("<[^>]*>", "").strip() : "Login rejected (body intentionally omitted).";
    }
    @Test void publicClientRequiresS256AndRejectsUnregisteredRedirect() throws Exception {
        String base = issuer() + "/protocol/openid-connect/auth?client_id=dtp-web&response_type=code&scope=openid&redirect_uri=";
        var missing = HTTP.send(HttpRequest.newBuilder(URI.create(base + encode("http://127.0.0.1:8999/callback"))).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(302, missing.statusCode());
        assertTrue(missing.headers().firstValue("Location").orElseThrow().contains("error=invalid_request"));
        var redirect = HTTP.send(HttpRequest.newBuilder(URI.create(base + encode("http://127.0.0.1:8998/unknown"))).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, redirect.statusCode());
        assertTrue(redirect.headers().firstValue("Location").isEmpty());
    }
    @Test void keyRotationRefreshesCachedJwksAndPreservesUnexpiredOldToken() throws Exception {
        var decoder = new SecurityConfiguration().decoder(issuer(), issuer() + "/protocol/openid-connect/certs", "dtp-ledger");
        String before = accessToken();
        String oldKid = decoder.decode(before).getHeaders().get("kid").toString();
        var admin = form("grant_type=client_credentials&client_id=dtp-bootstrap&client_secret=" + encode(ADMIN_SECRET));
        assertEquals(200, admin.statusCode());
        String bearer = JSON.readTree(admin.body()).path("access_token").asString();
        String adminUrl = "http://" + IDP.getHost() + ":" + IDP.getMappedPort(8080) + "/admin/realms/dtp";
        var realm = HTTP.send(HttpRequest.newBuilder(URI.create(adminUrl)).timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + bearer).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, realm.statusCode());
        String parent = JSON.readTree(realm.body()).path("id").asString();
        String component = JSON.writeValueAsString(java.util.Map.of("name", "rotated-local-test", "parentId", parent,
                "providerType", "org.keycloak.keys.KeyProvider", "providerId", "rsa-generated", "config",
                java.util.Map.of("priority", List.of("200"), "enabled", List.of("true"), "active", List.of("true"), "algorithm", List.of("RS256"), "keySize", List.of("2048"))));
        var rotation = HTTP.send(HttpRequest.newBuilder(URI.create(adminUrl + "/components")).timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + bearer)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(component)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, rotation.statusCode());
        String after = accessToken();
        assertNotEquals(oldKid, decoder.decode(after).getHeaders().get("kid"));
        assertEquals(oldKid, decoder.decode(before).getHeaders().get("kid"));
    }
    @Test void technicalOperatorHasOnlyExplicitOperationalGrant() throws Exception {
        var response = form("grant_type=client_credentials&client_id=dtp-operator&client_secret=" + encode(OPERATOR_SECRET));
        assertEquals(200, response.statusCode());
        var jwt = new SecurityConfiguration().decoder(issuer(), issuer() + "/protocol/openid-connect/certs", "dtp-ledger").decode(JSON.readTree(response.body()).path("access_token").asString());
        assertEquals(TENANT, ActorMapping.from(jwt).tenantId());
        assertTrue(ActorMapping.from(jwt).roles().contains("operator"));
        var authorities = RealmAuthorities.from(jwt).stream().map(a -> a.getAuthority()).toList();
        assertTrue(authorities.contains("SCOPE_transfer:operate"));
        assertFalse(authorities.contains("ROLE_transfer-client")); assertFalse(authorities.contains("SCOPE_transfer:write"));
    }
    @Test void committedLocalRealmTemplateImportsEnvironmentWithoutBootstrapAdminClient() throws Exception {
        String local = issuer().replace("/realms/dtp", "/realms/dtp-local");
        var response = HTTP.send(HttpRequest.newBuilder(URI.create(local + "/protocol/openid-connect/token")).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(
                    "grant_type=client_credentials&client_id=dtp-operator&client_secret=" + encode(OPERATOR_SECRET))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var jwt = new SecurityConfiguration().decoder(local, local + "/protocol/openid-connect/certs", "dtp-ledger").decode(JSON.readTree(response.body()).path("access_token").asString());
        assertEquals(TENANT, ActorMapping.from(jwt).tenantId()); assertTrue(ActorMapping.from(jwt).roles().contains("operator"));
        String realmJson = java.nio.file.Files.readString(java.nio.file.Path.of("../infra/keycloak/dtp-local-realm.json"));
        assertFalse(realmJson.contains("realm-admin")); assertFalse(realmJson.contains("dtp-bootstrap"));
    }
    private String accessToken() throws Exception {
        var response = form("grant_type=client_credentials&client_id=dtp-service&client_secret=" + URLEncoder.encode(SECRET, StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode());
        return JSON.readTree(response.body()).path("access_token").asString();
    }
    private HttpResponse<String> form(String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(issuer() + "/protocol/openid-connect/token")).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static String issuer() { return "http://" + IDP.getHost() + ":" + IDP.getMappedPort(8080) + "/realms/dtp"; }
    private static String realm() {
        return """
                {"realm":"dtp","enabled":true,"sslRequired":"none","accessTokenLifespan":120,
                 "roles":{"realm":[{"name":"transfer-client"},{"name":"auditor"},{"name":"operator"},{"name":"admin"}]},
                 "clients":[{"clientId":"dtp-service","secret":"%s","enabled":true,"publicClient":false,
                  "serviceAccountsEnabled":true,"standardFlowEnabled":false,"directAccessGrantsEnabled":false,"fullScopeAllowed":true,
                  "protocol":"openid-connect","protocolMappers":[
                   {"name":"tenant","protocol":"openid-connect","protocolMapper":"oidc-hardcoded-claim-mapper","config":{"claim.name":"tenant_id","claim.value":"%s","jsonType.label":"String","access.token.claim":"true"}},
                   {"name":"aud","protocol":"openid-connect","protocolMapper":"oidc-audience-mapper","config":{"included.custom.audience":"dtp-ledger","access.token.claim":"true"}},
                   {"name":"roles","protocol":"openid-connect","protocolMapper":"oidc-usermodel-realm-role-mapper","config":{"claim.name":"realm_access.roles","jsonType.label":"String","multivalued":"true","access.token.claim":"true"}}
                  ],"defaultClientScopes":["transfer:read"]},
                  {"clientId":"dtp-operator","secret":"%s","enabled":true,"publicClient":false,"serviceAccountsEnabled":true,"standardFlowEnabled":false,"directAccessGrantsEnabled":false,"fullScopeAllowed":true,"protocol":"openid-connect",
                   "defaultClientScopes":["transfer:operate"],"protocolMappers":[
                    {"name":"tenant","protocol":"openid-connect","protocolMapper":"oidc-hardcoded-claim-mapper","config":{"claim.name":"tenant_id","claim.value":"%s","jsonType.label":"String","access.token.claim":"true"}},
                    {"name":"aud","protocol":"openid-connect","protocolMapper":"oidc-audience-mapper","config":{"included.custom.audience":"dtp-ledger","access.token.claim":"true"}},
                    {"name":"roles","protocol":"openid-connect","protocolMapper":"oidc-usermodel-realm-role-mapper","config":{"claim.name":"realm_access.roles","jsonType.label":"String","multivalued":"true","access.token.claim":"true"}}
                   ]},
                  {"clientId":"dtp-bootstrap","secret":"%s","enabled":true,"publicClient":false,"serviceAccountsEnabled":true,"standardFlowEnabled":false,"directAccessGrantsEnabled":false,"fullScopeAllowed":true,"protocol":"openid-connect",
                   "protocolMappers":[{"name":"management-roles","protocol":"openid-connect","protocolMapper":"oidc-usermodel-client-role-mapper","config":{"usermodel.clientRoleMapping.clientId":"realm-management","claim.name":"resource_access.realm-management.roles","jsonType.label":"String","multivalued":"true","access.token.claim":"true"}}]},
                  {"clientId":"dtp-web","enabled":true,"publicClient":true,"standardFlowEnabled":true,"directAccessGrantsEnabled":false,
                   "redirectUris":["http://127.0.0.1:8999/callback"],"attributes":{"pkce.code.challenge.method":"S256"},"protocol":"openid-connect","fullScopeAllowed":true,
                   "defaultClientScopes":["basic","transfer:read"],"protocolMappers":[
                    {"name":"tenant","protocol":"openid-connect","protocolMapper":"oidc-usermodel-attribute-mapper","config":{"user.attribute":"tenant_id","claim.name":"tenant_id","jsonType.label":"String","access.token.claim":"true"}},
                    {"name":"aud","protocol":"openid-connect","protocolMapper":"oidc-audience-mapper","config":{"included.custom.audience":"dtp-ledger","access.token.claim":"true"}},
                    {"name":"roles","protocol":"openid-connect","protocolMapper":"oidc-usermodel-realm-role-mapper","config":{"claim.name":"realm_access.roles","jsonType.label":"String","multivalued":"true","access.token.claim":"true"}}
                   ]}],
                 "clientScopes":[{"name":"transfer:read","protocol":"openid-connect","attributes":{"include.in.token.scope":"true"}},
                    {"name":"transfer:operate","protocol":"openid-connect","attributes":{"include.in.token.scope":"true"}},
                    {"name":"basic","protocol":"openid-connect","attributes":{"include.in.token.scope":"false"},"protocolMappers":[{"name":"sub","protocol":"openid-connect","protocolMapper":"oidc-sub-mapper","config":{"access.token.claim":"true"}}]}],
                 "users":[{"username":"service-account-dtp-service","enabled":true,"serviceAccountClientId":"dtp-service","realmRoles":["transfer-client"]},
                          {"username":"service-account-dtp-bootstrap","enabled":true,"serviceAccountClientId":"dtp-bootstrap","clientRoles":{"realm-management":["realm-admin"]}},
                          {"username":"service-account-dtp-operator","enabled":true,"serviceAccountClientId":"dtp-operator","realmRoles":["operator"]},
                          {"username":"alice","attributes":{"tenant_id":["%s"]},"enabled":true,"email":"alice@example.invalid","emailVerified":true,"firstName":"Alice","lastName":"Synthetic","realmRoles":["transfer-client"],"credentials":[{"type":"password","value":"%s","temporary":false}]}]}
                """.formatted(SECRET, TENANT, OPERATOR_SECRET, TENANT, ADMIN_SECRET, TENANT, PASSWORD);
    }
}
