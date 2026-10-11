package dev.dtp.api;

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
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1/jwks", "dtp.audience=dtp-ledger"})
@org.springframework.test.context.ContextConfiguration(classes = {SecurityConfiguration.class, RoleSecurityTest.Probe.class})
class RoleSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;

    @Test void roleAndScopeRequiredTogether() throws Exception {
        token("transfer:write", List.of("transfer-client"));
        mvc.perform(post("/v1/transfers").header("Authorization", "Bearer fixture")).andExpect(status().isOk());
        token("transfer:read", List.of("transfer-client"));
        mvc.perform(post("/v1/transfers").header("Authorization", "Bearer fixture")).andExpect(status().isForbidden());
        token("transfer:write", List.of());
        mvc.perform(post("/v1/transfers").header("Authorization", "Bearer fixture")).andExpect(status().isForbidden());
    }
    @Test void administratorAndUntrustedRolesCannotMutate() throws Exception {
        for (String role : List.of("admin", "operator", "ROLE_transfer-client", "unknown")) {
            token("transfer:write transfer:read", List.of(role));
            mvc.perform(post("/v1/transfers").header("Authorization", "Bearer fixture")).andExpect(status().isForbidden());
            mvc.perform(get("/v1/transfers/fixture").header("Authorization", "Bearer fixture")).andExpect(status().isForbidden());
        }
    }
    @Test void malformedAndOversizedRoleClaimsGrantNothing() throws Exception {
        for (Object realm : List.of("transfer-client", Map.of("roles", "transfer-client"), Map.of("roles", java.util.Collections.nCopies(65, "transfer-client")))) {
            when(decoder.decode("fixture")).thenReturn(jwt("transfer:write", realm));
            mvc.perform(post("/v1/transfers").header("Authorization", "Bearer fixture")).andExpect(status().isForbidden());
        }
    }
    @Test void absentBearerAndUnknownRoutesDenied() throws Exception {
        mvc.perform(post("/v1/transfers")).andExpect(status().isUnauthorized());
        token("transfer:write transfer:read", List.of("transfer-client", "admin"));
        mvc.perform(get("/private").header("Authorization", "Bearer fixture")).andExpect(status().isForbidden());
    }
    private void token(String scope, List<String> roles) { when(decoder.decode("fixture")).thenReturn(jwt(scope, Map.of("roles", roles))); }
    private Jwt jwt(String scope, Object realm) {
        return Jwt.withTokenValue("fixture").header("alg", "RS256").subject("alice")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).claim("scope", scope).claim("realm_access", realm).build();
    }
    @RestController static class Probe {
        @PostMapping("/v1/transfers") String mutate() { return "ok"; }
        @GetMapping("/v1/transfers/{id}") String read() { return "ok"; }
    }
}
