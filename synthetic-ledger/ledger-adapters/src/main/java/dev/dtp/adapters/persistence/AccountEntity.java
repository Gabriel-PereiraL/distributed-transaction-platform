package dev.dtp.adapters.persistence;

import dev.dtp.domain.Account;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(name = "accounts")
class AccountEntity {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "owner_subject", nullable = false, length = 128) private String owner;
    @Column(nullable = false, length = 16) private String kind;
    @Column(nullable = false, length = 3) private String currency;
    @Column(name = "balance_cents", nullable = false) private long balanceCents;
    @Column(name = "reserved_cents", nullable = false) private long reservedCents;
    @Column(nullable = false) private boolean active;
    @Version private long version;

    protected AccountEntity() { }
    Account snapshot() { return new Account(id, tenantId, owner, currency, balanceCents, reservedCents, active); }
    void reserveTo(long cents) { reservedCents = cents; }
}
