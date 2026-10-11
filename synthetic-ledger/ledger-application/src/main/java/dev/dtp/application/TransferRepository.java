package dev.dtp.application;

import dev.dtp.domain.Transfer;
import java.util.Optional;
import java.util.UUID;

public interface TransferRepository {
    /** Empty = newly claimed in this transaction; present = existing committed admission. */
    Optional<Admission> claim(Actor actor, String key, String hash, Admission proposed);
    void insertWithHold(Transfer transfer);
    Optional<Transfer> findInitiated(Actor actor, UUID id);
    Optional<TransferState> findState(Actor actor, UUID id);
}
