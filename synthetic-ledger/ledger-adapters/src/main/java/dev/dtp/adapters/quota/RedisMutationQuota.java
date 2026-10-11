package dev.dtp.adapters.quota;

import dev.dtp.application.Actor;
import dev.dtp.application.MutationQuota;
import dev.dtp.application.QuotaExceeded;
import dev.dtp.application.QuotaUnavailable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Atomic anchored window per authenticated tenant/client; never stores financial balances. */
@Component
@ConditionalOnProperty(name = "dtp.quota.enabled", havingValue = "true")
public class RedisMutationQuota implements MutationQuota {
    private static final DefaultRedisScript<String> SCRIPT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return tostring(count) .. ':' .. tostring(redis.call('PTTL', KEYS[1]))
            """, String.class);
    private final StringRedisTemplate redis;
    private final long limit;
    private final long windowMillis;
    public RedisMutationQuota(StringRedisTemplate redis, @Value("${dtp.quota.limit:60}") long limit,
                              @Value("${dtp.quota.window-ms:60000}") long windowMillis) {
        if (limit < 1 || limit > 100_000 || windowMillis < 100 || windowMillis > 3_600_000) throw new IllegalArgumentException("Bounded quota settings required.");
        this.redis = redis; this.limit = limit; this.windowMillis = windowMillis;
    }
    @Override public void check(Actor actor) {
        check(actor, "POST /v1/transfers");
    }
    @Override public void check(Actor actor, String operation) {
        final String result;
        try { result = redis.execute(SCRIPT, List.of(key(actor, operation)), Long.toString(windowMillis)); }
        catch (DataAccessException unavailable) { throw new QuotaUnavailable(); }
        if (result == null) throw new QuotaUnavailable();
        var values = result.split(":", -1);
        if (values.length != 2) throw new QuotaUnavailable();
        long count = Long.parseLong(values[0]); long ttl = Long.parseLong(values[1]);
        if (ttl <= 0) throw new QuotaUnavailable();
        if (count > limit) throw new QuotaExceeded((ttl + 999) / 1000);
    }
    public static String key(Actor actor) {
        return key(actor, "POST /v1/transfers");
    }
    public static String key(Actor actor, String operation) {
        if (!java.util.Set.of("POST /v1/transfers", "POST /v1/transfers/{id}/requeue").contains(operation)) throw new IllegalArgumentException("Unknown quota operation.");
        var identity = actor.tenantId() + "\n" + actor.clientId() + "\n" + operation;
        try { return "dtp:quota:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable.", impossible); }
    }
}
