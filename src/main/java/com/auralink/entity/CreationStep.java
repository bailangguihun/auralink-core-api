package com.auralink.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
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
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "creation_steps",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_creation_steps_public_id", columnNames = "public_id"),
                @UniqueConstraint(
                        name = "uq_creation_steps_creation_index",
                        columnNames = {"creation_id", "step_index"}
                )
        },
        indexes = {
                @Index(name = "idx_creation_steps_creation_status", columnList = "creation_id, status"),
                @Index(name = "idx_creation_steps_input_asset", columnList = "input_asset_id"),
                @Index(name = "idx_creation_steps_output_asset", columnList = "output_asset_id")
        }
)
public class CreationStep extends BasePublicIdEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "creation_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_creation_steps_creation")
    )
    private Creation creation;

    @Column(name = "step_index", nullable = false)
    private int stepIndex;

    @Column(name = "node_id", nullable = false, length = 255)
    private String nodeId;

    @Column(name = "operation_code", nullable = false, length = 128)
    private String operationCode;

    @Column(name = "provider_code", length = 128)
    private String providerCode;

    @Column(name = "input_modality", nullable = false, length = 64)
    private String inputModality;

    @Column(name = "output_modality", nullable = false, length = 64)
    private String outputModality;

    @Column(name = "input_json", columnDefinition = "TEXT")
    private String inputJson;

    @Column(name = "parameters_json", columnDefinition = "TEXT")
    private String parametersJson;

    @Column(name = "output_json", columnDefinition = "TEXT")
    private String outputJson;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(
            name = "input_asset_id",
            foreignKey = @ForeignKey(name = "fk_creation_steps_input_asset")
    )
    private MediaAsset inputAsset;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(
            name = "output_asset_id",
            foreignKey = @ForeignKey(name = "fk_creation_steps_output_asset")
    )
    private MediaAsset outputAsset;

    @Column(nullable = false, length = 64)
    private String status;

    @Builder.Default
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "error_code", length = 128)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Builder.Default
    @Column(name = "provider_dispatch_state", nullable = false, length = 32)
    private String providerDispatchState = "NOT_SENT";

    @Column(name = "provider_request_key", length = 128)
    private String providerRequestKey;
}
