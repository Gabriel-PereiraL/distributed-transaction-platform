package dev.dtp.contracts;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Bounded contract validation independent of SQL; future optional fields can be ignored. */
public final class TransferEventDecoder {
    public static final int MAX_BYTES = 65_536;
    private final ObjectMapper json;
    public TransferEventDecoder(ObjectMapper json) { this.json = json; }
    public TransferFact decode(byte[] bytes, String key) {
        if (bytes == null || bytes.length > MAX_BYTES) throw new InvalidContract();
        try {
            var root = json.readTree(bytes);
            if (number(root, "schemaVersion") != 1) throw new InvalidContract();
            var type = text(root, "eventType", 100); long version = number(root, "aggregateVersion");
            if (!Set.of("TransferAccepted", "TransferPosted", "TransferRejected").contains(type)) throw new InvalidContract();
            var tenant = uuid(root, "tenantId"); var id = uuid(root, "aggregateId");
            if (!(tenant + ":" + id).equals(key)) throw new InvalidContract();
            var payload = root.get("payload");
            if (!tenant.equals(uuid(payload, "tenantId")) || !id.equals(uuid(payload, "id"))) throw new InvalidContract();
            var source = uuid(payload, "sourceAccountId"); var destination = uuid(payload, "destinationAccountId");
            if (source.equals(destination)) throw new InvalidContract();
            var amount = payload.get("amount"); long cents = number(amount, "cents");
            if (cents < 1 || cents > 1_000_000_000 || !"BRL".equals(text(amount, "currency", 3))) throw new InvalidContract();
            var acceptedAt = time(payload, "acceptedAt"); var occurredAt = time(root, "occurredAt");
            String status = "ACCEPTED"; Instant terminalAt = null; String reason = null;
            if (type.equals("TransferAccepted")) {
                if (version != 1 || !acceptedAt.equals(occurredAt)) throw new InvalidContract();
            } else {
                if (version != 2) throw new InvalidContract();
                status = text(payload, "status", 16); terminalAt = time(payload, "terminalAt");
                if (!terminalAt.equals(occurredAt) || terminalAt.isBefore(acceptedAt)) throw new InvalidContract();
                reason = optionalText(payload, "rejectionReason", 64);
                if (type.equals("TransferPosted") && (!status.equals("POSTED") || reason != null)) throw new InvalidContract();
                if (type.equals("TransferRejected") && (!status.equals("REJECTED") || !Set.of("ACCOUNT_INACTIVE", "SYNTHETIC_LIMIT").contains(reason == null ? "" : reason))) throw new InvalidContract();
            }
            var correlation = text(root, "correlationId", 64);
            if (!correlation.matches("[A-Za-z0-9_-]{1,64}")) throw new InvalidContract();
            var trace = optionalText(root, "traceparent", 55);
            if (trace != null && !trace.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")) throw new InvalidContract();
            return new TransferFact(uuid(root, "eventId"), tenant, id, version, type, occurredAt, text(payload, "initiatedBy", 128),
                    source, destination, cents, "BRL", acceptedAt, text(payload, "ruleVersion", 32), status, terminalAt, reason, correlation, trace);
        } catch (RuntimeException invalid) { throw new InvalidContract(); }
    }
    public String canonicalHash(TransferFact fact) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(fact))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable.", impossible); }
    }
    private static String text(JsonNode node, String name, int max) {
        var value = node.get(name);
        if (value == null || !value.isString() || value.stringValue().isBlank() || value.stringValue().length() > max) throw new InvalidContract();
        return value.stringValue();
    }
    private static String optionalText(JsonNode node, String name, int max) {
        var value = node.get(name);
        return value == null || value.isNull() ? null : text(node, name, max);
    }
    private static long number(JsonNode node, String name) {
        var value = node.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw new InvalidContract();
        return value.longValue();
    }
    private static UUID uuid(JsonNode node, String name) {
        String value = text(node, name, 36);
        if (value.length() != 36) throw new InvalidContract();
        return UUID.fromString(value);
    }
    private static Instant time(JsonNode node, String name) { return Instant.parse(text(node, name, 64)); }
}
