package dev.dtp.application;

import dev.dtp.domain.Account;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository {
    /** Locks customer accounts by ascending id, confined to the caller's tenant. */
    List<Account> lockPair(UUID tenantId, UUID source, UUID destination);
    void saveReservation(Account account);
    Optional<Account> findOwned(Actor actor, UUID id);
    /** Customer-only tenant read: owner or explicitly authorized auditor. */
    default Optional<Account> findVisible(Actor actor, UUID id) { return findOwned(actor, id); }
}
