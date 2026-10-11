package dev.dtp.application;

import java.util.UUID;

public interface RequeueRepository {
    record Result(UUID transferId, UUID eventId, String outcome) { }
    Result request(Actor actor, UUID transferId, String reason);
}
