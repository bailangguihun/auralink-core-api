package com.auralink.creation;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.creation.provider.ProviderAdapterRegistry;
import com.auralink.creation.provider.ProviderReadiness;
import com.auralink.creation.provider.ProviderReadinessState;
import com.auralink.workflow.WorkflowOperation;
import com.auralink.workflow.capability.WorkflowCapabilityRegistry;
import com.auralink.workflow.capability.WorkflowOperationCapability;
import com.auralink.config.properties.CreationExecutionProperties;
import com.auralink.workflow.service.WorkflowExecutionPreparer.PreparedWorkflow;
import com.auralink.workflow.service.WorkflowExecutionPreparer.PreparedTransform;

import lombok.RequiredArgsConstructor;

/** Admission-only readiness policy. It never probes or calls an external provider. */
@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class CreationExecutionCapabilityService {

    public static final String MUSIC_DEFERRED_REASON = "PAINTING_TO_MUSIC_DEFERRED_NOT_VALIDATED";

    private final WorkflowCapabilityRegistry workflowCapabilities;
    private final ProviderAdapterRegistry adapters;
    private final CreationExecutionProperties properties;
    private final CreationRecoveryGate recoveryGate;

    /** Compatibility constructor for capability-only unit tests. */
    public CreationExecutionCapabilityService(
            WorkflowCapabilityRegistry workflowCapabilities,
            ProviderAdapterRegistry adapters) {
        this(workflowCapabilities, adapters, new CreationExecutionProperties(), new CreationRecoveryGate());
    }

    public void requireExecutionAvailable(PreparedWorkflow workflow) {
        for (PreparedTransform transform : workflow.transforms()) {
            requireExecutionAvailable(transform.operation(), transform.providerCode());
        }
    }

    /** Reused by submission and worker rechecks; it never contacts a provider. */
    public void requireExecutionAvailable(WorkflowOperation operation, String providerCode) {
        ExecutionAvailability availability = availability(operation, providerCode);
        if (!availability.available()) {
            if (operation == WorkflowOperation.PAINTING_TO_VIDEO) {
                throw new ApiV1Exception(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        ApiErrorCode.CREATION_VIDEO_RESERVED,
                        "绘画转视频功能保留且当前不可用");
            }
            if ("CREATION_RECOVERY_NOT_READY".equals(availability.reason())) {
                throw new ApiV1Exception(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        ApiErrorCode.CREATION_RECOVERY_NOT_READY,
                        "创作恢复尚未准备就绪");
            }
            throw unavailable(availability.reason());
        }
    }

    /** Stable, provider-safe runtime capability fact used by discovery DTO mapping. */
    public ExecutionAvailability availability(WorkflowOperation operation, String providerCode) {
        if (operation == null || providerCode == null || providerCode.isBlank()) {
            return new ExecutionAvailability(false, "CREATION_OPERATION_UNAVAILABLE");
        }
        if (operation == WorkflowOperation.PAINTING_TO_MUSIC) {
            return new ExecutionAvailability(false, MUSIC_DEFERRED_REASON);
        }
        if (operation == WorkflowOperation.PAINTING_TO_VIDEO) {
            return new ExecutionAvailability(false, "RESERVED_FOR_FUTURE_IMPLEMENTATION");
        }

        if (properties.isEnabled() && !recoveryGate.isOpen()) {
            return new ExecutionAvailability(false, "CREATION_RECOVERY_NOT_READY");
        }

        WorkflowOperationCapability capability = workflowCapabilities.require(operation);
        if (!capability.definitionEnabled() || !capability.allowsProvider(providerCode)) {
            return new ExecutionAvailability(false, "CREATION_OPERATION_UNAVAILABLE");
        }
        if (adapters.find(operation, providerCode).isEmpty()) {
            return new ExecutionAvailability(false, "PROVIDER_ADAPTER_NOT_REGISTERED");
        }
        ProviderReadiness readiness = adapters.readiness(operation, providerCode);
        if (readiness.state() != ProviderReadinessState.READY_FOR_CONTROLLED_EXECUTION) {
            return new ExecutionAvailability(false, safeReadinessReason(readiness.state()));
        }
        return new ExecutionAvailability(true, "READY_FOR_CONTROLLED_EXECUTION");
    }

    private String safeReadinessReason(ProviderReadinessState state) {
        return switch (state) {
            case FEATURE_DISABLED -> "CREATION_PROVIDER_FEATURE_DISABLED";
            case CONFIGURATION_MISSING -> "CREATION_PROVIDER_CONFIGURATION_MISSING";
            case CONFIGURATION_INVALID -> "CREATION_PROVIDER_CONFIGURATION_INVALID";
            case RESERVED_DISABLED -> "RESERVED_FOR_FUTURE_IMPLEMENTATION";
            case ADAPTER_IMPLEMENTED, INTERNAL_SERVICE_NOT_VALIDATED -> "CREATION_PROVIDER_NOT_READY";
            case READY_FOR_CONTROLLED_EXECUTION -> "READY_FOR_CONTROLLED_EXECUTION";
        };
    }

    private static ApiV1Exception unavailable(String reason) {
        return new ApiV1Exception(
                HttpStatus.CONFLICT,
                ApiErrorCode.CREATION_OPERATION_UNAVAILABLE,
                "创作操作当前不可执行：" + reason);
    }

    public record ExecutionAvailability(boolean available, String reason) {
    }
}
