package dev.dtp.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TransferEventDecoderTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final TransferEventDecoder decoder = new TransferEventDecoder(json);
    private static final UUID TENANT = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final String KEY = TENANT + ":" + ID;
    @Test void acceptedSnapshotIsIndependentOfLedgerClasses() {
        var fact = decoder.decode(json.writeValueAsBytes(event("TransferAccepted")), KEY);
        assertThat(fact.status()).isEqualTo("ACCEPTED"); assertThat(fact.aggregateVersion()).isEqualTo(1); assertThat(fact.amountCents()).isEqualTo(100);
    }
    @Test void postedAndRejectedCarryCompleteTerminalSnapshots() {
        assertThat(decoder.decode(json.writeValueAsBytes(event("TransferPosted")), KEY).status()).isEqualTo("POSTED");
        assertThat(decoder.decode(json.writeValueAsBytes(event("TransferRejected")), KEY).rejectionReason()).isEqualTo("SYNTHETIC_LIMIT");
    }
    @Test void optionalTraceEvolutionUsesDefaultAndUnknownOptionalFieldsDoNotBreakConsumer() {
        var older = event("TransferAccepted"); var legacy = decoder.decode(json.writeValueAsBytes(older), KEY);
        var newer = event("TransferAccepted"); newer.put("traceparent", null); newer.put("optionalFutureMetadata", "ignored");
        var evolved = decoder.decode(json.writeValueAsBytes(newer), KEY);
        assertThat(evolved).isEqualTo(legacy); assertThat(decoder.canonicalHash(evolved)).isEqualTo(decoder.canonicalHash(legacy));
        newer.put("traceparent", "00-11111111111111111111111111111111-2222222222222222-01");
        assertThat(decoder.decode(json.writeValueAsBytes(newer), KEY).traceparent()).isNotNull();
    }
    @Test void canonicalFingerprintIgnoresWhitespaceAndPropertyOrder() {
        var data = event("TransferAccepted");
        var first = decoder.decode(json.writeValueAsBytes(data), KEY);
        var reordered = new java.util.TreeMap<>(data);
        var second = decoder.decode(json.writeValueAsBytes(reordered), KEY);
        assertThat(decoder.canonicalHash(first)).isEqualTo(decoder.canonicalHash(second));
    }
    @Test void schemaIdentityAmountAndBoundsAreValidated() {
        var data = event("TransferAccepted"); data.put("schemaVersion", 99);
        invalid(data, KEY); invalid(event("TransferAccepted"), "other-key");
        data = event("TransferAccepted"); data.put("tenantId", UUID.randomUUID()); invalid(data, KEY);
        data = event("TransferAccepted"); payload(data).put("amount", Map.of("cents", 1.5, "currency", "BRL")); invalid(data, KEY);
        data = event("TransferAccepted"); payload(data).remove("initiatedBy"); invalid(data, KEY);
        data = event("TransferAccepted"); data.put("tenantId", "1-1-1-1-1"); payload(data).put("tenantId", "1-1-1-1-1");
        invalid(data, UUID.fromString("1-1-1-1-1") + ":" + ID);
        assertThatThrownBy(() -> decoder.decode(new byte[70000], KEY)).isInstanceOf(InvalidContract.class);
    }
    @Test void invalidTerminalVersionTimeAndReasonAreRejected() {
        var data = event("TransferPosted"); data.put("aggregateVersion", 1); invalid(data, KEY);
        data = event("TransferPosted"); data.put("occurredAt", "2026-10-11T10:00:00Z"); invalid(data, KEY);
        data = event("TransferRejected"); payload(data).put("rejectionReason", null); invalid(data, KEY);
        data = event("TransferPosted"); payload(data).put("rejectionReason", "SYNTHETIC_LIMIT"); invalid(data, KEY);
    }
    private void invalid(Map<String, Object> data, String key) {
        var bytes = json.writeValueAsBytes(data);
        assertThatThrownBy(() -> decoder.decode(bytes, key)).isInstanceOf(InvalidContract.class);
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> payload(Map<String, Object> event) { return (Map<String, Object>) event.get("payload"); }
    static Map<String, Object> event(String type) {
        boolean accepted = type.equals("TransferAccepted"); boolean posted = type.equals("TransferPosted");
        var payload = new LinkedHashMap<String, Object>();
        payload.put("id", ID); payload.put("tenantId", TENANT); payload.put("initiatedBy", "alice");
        payload.put("sourceAccountId", "20000000-0000-0000-0000-000000000001"); payload.put("destinationAccountId", "20000000-0000-0000-0000-000000000002");
        payload.put("amount", Map.of("cents", 100, "currency", "BRL")); payload.put("acceptedAt", "2026-10-11T10:00:00Z"); payload.put("ruleVersion", "synthetic-v1");
        if (!accepted) { payload.put("status", posted ? "POSTED" : "REJECTED"); payload.put("terminalAt", "2026-10-11T10:00:01Z"); payload.put("rejectionReason", posted ? null : "SYNTHETIC_LIMIT"); }
        var event = new LinkedHashMap<String, Object>();
        event.put("eventId", "40000000-0000-0000-0000-000000000001"); event.put("tenantId", TENANT); event.put("aggregateId", ID);
        event.put("schemaVersion", 1); event.put("aggregateVersion", accepted ? 1 : 2); event.put("eventType", type);
        event.put("occurredAt", accepted ? "2026-10-11T10:00:00Z" : "2026-10-11T10:00:01Z"); event.put("payload", payload); event.put("correlationId", "contract-test");
        return event;
    }
}
