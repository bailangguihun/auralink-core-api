package com.auralink.creation;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.auralink.repository.CreationRepository;

/** Keeps each heartbeat update in one short database transaction. */
@Service
public class CreationLeaseHeartbeatTransactionService {

    private final CreationRepository creations;

    public CreationLeaseHeartbeatTransactionService(CreationRepository creations) {
        this.creations = creations;
    }

    @Transactional
    public int refresh(Long creationId, String claimToken, LocalDateTime leaseExpiresAt, LocalDateTime now) {
        return creations.refreshLease(creationId, claimToken, leaseExpiresAt, now);
    }
}
