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

/** Append-only provider-dispatch evidence for one Step in one execution attempt. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "creation_step_dispatch_attempts",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_creation_step_dispatch_attempts_step_execution",
                        columnNames = {"creation_step_id", "creation_execution_attempt_id"}),
                @UniqueConstraint(
                        name = "uq_creation_step_dispatch_attempts_provider_request_key",
                        columnNames = "provider_request_key")
        },
        indexes = {
                @Index(name = "idx_creation_step_dispatch_attempts_step", columnList = "creation_step_id, id"),
                @Index(name = "idx_creation_step_dispatch_attempts_execution",
                        columnList = "creation_execution_attempt_id, id")
        }
)
public class CreationStepDispatchAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "creation_step_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_creation_step_dispatch_attempts_step"))
    private CreationStep creationStep;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "creation_execution_attempt_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_creation_step_dispatch_attempts_execution_attempt"))
    private CreationExecutionAttempt creationExecutionAttempt;

    @Column(name = "provider_request_key", length = 128)
    private String providerRequestKey;

    @Column(name = "dispatch_state", nullable = false, length = 32)
    private String dispatchState;

    @Column(name = "dispatch_started_at")
    private LocalDateTime dispatchStartedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "resolution_code", length = 128)
    private String resolutionCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(
            name = "result_asset_id",
            foreignKey = @ForeignKey(name = "fk_creation_step_dispatch_attempts_result_asset"))
    private MediaAsset resultAsset;

    @Column(name = "result_digest", length = 64)
    private String resultDigest;

    @Column(name = "canonical_poem_digest", length = 64)
    private String canonicalPoemDigest;
}
