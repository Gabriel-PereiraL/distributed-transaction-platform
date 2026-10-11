package dev.dtp.activity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real filter chain; decoding mocked here, real issuer/signature covered separately. */
@WebMvcTest(controllers = RoleSecurityTest.Probe.class, properties = {
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://127.0.0.1/issuer",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1/jwks", "dtp.activity.audience=dtp-activity"})
@org.springframework.test.context.ContextConfiguration(classes = {ReadSecurity.class, RoleSecurityTest.Probe.class})
class RoleSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;

    @Test void roleAndScopeRequiredTogether() throws Exception {
        token("activity:read", List.of("transfer-client"));
        mvc.perform(get("/v1/activity/transfers").header("Authorization", "Bearer fixture")).andExpect(status().isOk());
        token("wrong:scope", List.of("transfer-client"));
        mvc.perform(get("/v1/activity/transfers").header("Authorization", "Bearer fixture")).andExpect(status().isForbidden());
        token("activity:read", List.of());
        mvc.perform(get("/v1/activity/transfers").header("Authorization", "Bearer fixture")).andExpect(status().isForbidden());
    }
    @Test void administratorAndUntrustedRolesCannotMutate() throws Exception {
        for (String role : List.of("admin", "operator", "ROLE_transfer-client", "unknown")) {
            token("activity:read wrong:scope", List.of(role));
            mvc.perform(get("/v1/activity/transfers").header("Authorization", "Bearer fixture")).andExpect(status().isForbidden());
            mvc.perform(get("/v1/activity/transfers/fixture").header("Authorization", "Bearer fixture")).andExpect(status().isForbidden());
        }
    }
    @Test void malformedAndOversizedRoleClaimsGrantNothing() throws Exception {
        for (Object realm : List.of("transfer-client", Map.of("roles", "transfer-client"), Map.of("roles", java.util.Collections.nCopies(65, "transfer-client")))) {
            when(decoder.decode("fixture")).thenReturn(jwt("activity:read", realm));
            mvc.perform(get("/v1/activity/transfers").header("Authorization", "Bearer fixture")).andExpect(status().isForbidden());
        }
    }
    @Test void absentBearerAndUnknownRoutesDenied() throws Exception {
        mvc.perform(get("/v1/activity/transfers")).andExpect(status().isUnauthorized());
        token("activity:read wrong:scope", List.of("transfer-client", "admin"));
        mvc.perform(get("/private").header("Authorization", "Bearer fixture")).andExpect(status().isForbidden());
    }
    private void token(String scope, List<String> roles) { when(decoder.decode("fixture")).thenReturn(jwt(scope, Map.of("roles", roles))); }
    private Jwt jwt(String scope, Object realm) {
        return Jwt.withTokenValue("fixture").header("alg", "RS256").subject("alice")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).claim("scope", scope).claim("realm_access", realm).build();
    }
    @RestController static class Probe {
        @GetMapping("/v1/activity/transfers") String mutate() { return "ok"; }
        @GetMapping("/v1/activity/transfers/{id}") String read() { return "ok"; }
    }
}
