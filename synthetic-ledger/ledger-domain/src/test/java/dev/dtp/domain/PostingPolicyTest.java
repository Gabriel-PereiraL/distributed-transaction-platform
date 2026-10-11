package dev.dtp.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostingPolicyTest {
    private final PostingPolicy policy = new PostingPolicy();
    @Test void exactLimitPostsAndNextCentRejects() {
        assertThat(policy.decide(transfer(500000, Transfer.RULE_VERSION), true, true).status()).isEqualTo("POSTED");
        assertThat(policy.decide(transfer(500001, Transfer.RULE_VERSION), true, true).reason()).isEqualTo("SYNTHETIC_LIMIT");
    }
    @Test void eitherInactiveAccountRejects() {
        assertThat(policy.decide(transfer(1, Transfer.RULE_VERSION), false, true).reason()).isEqualTo("ACCOUNT_INACTIVE");
        assertThat(policy.decide(transfer(1, Transfer.RULE_VERSION), true, false).reason()).isEqualTo("ACCOUNT_INACTIVE");
    }
    @Test void unknownRuleCannotSilentlyUseLatestPolicy() {
        assertThatThrownBy(() -> policy.decide(transfer(1, "future-v2"), true, true)).isInstanceOf(IllegalArgumentException.class);
    }
    private static Transfer transfer(long cents, String version) {
        return new Transfer(UUID.randomUUID(), UUID.randomUUID(), "alice", UUID.randomUUID(), UUID.randomUUID(), new Money(cents,"BRL"), Instant.EPOCH, version);
    }
}
