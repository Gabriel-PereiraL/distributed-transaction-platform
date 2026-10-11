package dev.dtp.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import static org.springframework.security.authorization.AuthorizationManagers.allOf;
import static org.springframework.security.authorization.AuthorizationManagers.anyOf;
import static org.springframework.security.authorization.AuthorityAuthorizationManager.hasAuthority;

@Configuration
@org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
class SecurityConfiguration {
    @Bean
    JwtDecoder decoder(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
                       @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwks,
                       @Value("${dtp.audience}") String audience) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            try {
                ActorMapping.from(jwt);
                if (jwt.getExpiresAt() != null && jwt.getAudience().contains(audience)) return OAuth2TokenValidatorResult.success();
            } catch (RuntimeException ignored) {
                // Missing/malformed identity cannot downgrade to signature-only authentication.
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid token claims.", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), claims));
        return decoder;
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(RealmAuthorities::from);
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/v1/transfers/*/requeue").access(allOf(hasAuthority("SCOPE_transfer:operate"), hasAuthority("ROLE_operator")))
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/v1/transfers").access(allOf(hasAuthority("SCOPE_transfer:write"), hasAuthority("ROLE_transfer-client")))
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/v1/accounts/*/legacy").access(allOf(hasAuthority("SCOPE_transfer:read"), hasAuthority("ROLE_transfer-client")))
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/v1/transfers/**", "/v1/accounts/**").access(allOf(hasAuthority("SCOPE_transfer:read"), anyOf(hasAuthority("ROLE_transfer-client"), hasAuthority("ROLE_auditor"))))
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)))
                .build();
    }
}
