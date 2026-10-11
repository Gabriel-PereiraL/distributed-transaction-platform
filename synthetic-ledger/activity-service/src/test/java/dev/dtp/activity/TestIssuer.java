package dev.dtp.activity;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/** Ephemeral test-only RSA issuer; no private key is committed. */
final class TestIssuer implements AutoCloseable {
    private final RSAKey key;
    private final HttpServer server;
    private final String url;
    TestIssuer() {
        try {
            key = new RSAKeyGenerator(2048).keyID("test-issuer").generate();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jwks", exchange -> {
                byte[] bytes = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.start(); url = "http://127.0.0.1:" + server.getAddress().getPort();
        } catch (Exception error) { throw new IllegalStateException("Test issuer failed to start.", error); }
    }
    String url() { return url; }
    String token(UUID tenant, String subject, String client, String audience, String scope) throws Exception {
        return token(tenant, subject, client, audience, scope, java.util.List.of("transfer-client"));
    }
    String token(UUID tenant, String subject, String client, String audience, String scope, java.util.List<String> roles) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(url).subject(subject).audience(audience)
                .issueTime(Date.from(Instant.now().minusSeconds(1))).expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .claim("azp", client).claim("scope", scope)
                .claim("realm_access", java.util.Map.of("roles", roles));
        if (tenant != null) claims.claim("tenant_id", tenant.toString());
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(key)); return jwt.serialize();
    }
    @Override public void close() { server.stop(0); }
}
