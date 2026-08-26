package com.auralink.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Immutable admission history for one Creation execution or owner-safe retry. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "creation_execution_attempts",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_creation_execution_attempts_creation_number",
                        columnNames = {"creation_id", "attempt_number"}),
                @UniqueConstraint(
                        name = "uq_creation_execution_attempts_creation_idempotency",
                        columnNames = {"creation_id", "retry_idempotency_key_digest"})
        },
        indexes = @Index(
                name = "idx_creation_execution_attempts_creation_admitted",
                columnList = "creation_id, admitted_at, id")
)
public class CreationExecutionAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "creation_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_creation_execution_attempts_creation"))
    private Creation creation;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "retry_idempotency_key_digest", length = 64)
    private String retryIdempotencyKeyDigest;

    @Column(name = "admitted_at", nullable = false)
    private LocalDateTime admittedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "resolution_code", length = 128)
    private String resolutionCode;
}
