package dev.dtp.application;

import dev.dtp.domain.Transfer;
import java.time.Instant;

public record TransferState(Transfer transfer, String status, long aggregateVersion, Instant terminalAt, String reason) { }
