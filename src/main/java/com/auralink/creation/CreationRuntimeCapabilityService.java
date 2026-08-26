package com.auralink.creation;

import org.springframework.stereotype.Service;

import com.auralink.config.properties.CreationExecutionProperties;
import com.auralink.workflow.WorkflowOperation;
import com.auralink.workflow.capability.WorkflowOperationCapability;

import lombok.RequiredArgsConstructor;

/** Adds the feature-switch/engine gate to static workflow definition metadata. */
@Service
@RequiredArgsConstructor
public class CreationRuntimeCapabilityService {

    private final CreationExecutionProperties properties;
    private final CreationExecutionCapabilityService executionCapabilities;
    private final CreationRecoveryGate recoveryGate;

    public CreationExecutionCapabilityService.ExecutionAvailability availability(
            WorkflowOperationCapability capability) {
        WorkflowOperation operation = capability.operation();
        if (operation == WorkflowOperation.PAINTING_TO_MUSIC) {
            return new CreationExecutionCapabilityService.ExecutionAvailability(
                    false, CreationExecutionCapabilityService.MUSIC_DEFERRED_REASON);
        }
        if (operation == WorkflowOperation.PAINTING_TO_VIDEO) {
            return new CreationExecutionCapabilityService.ExecutionAvailability(
                    false, "RESERVED_FOR_FUTURE_IMPLEMENTATION");
        }
        if (!properties.isEnabled()) {
            return new CreationExecutionCapabilityService.ExecutionAvailability(
                    false, "CREATIONS_FEATURE_DISABLED");
        }
        if (!recoveryGate.isOpen()) {
            return new CreationExecutionCapabilityService.ExecutionAvailability(
                    false, "CREATION_RECOVERY_NOT_READY");
        }
        if (!capability.definitionEnabled() || capability.providers().size() != 1) {
            return new CreationExecutionCapabilityService.ExecutionAvailability(
                    false, "CREATION_OPERATION_UNAVAILABLE");
        }
        return executionCapabilities.availability(operation, capability.providers().get(0).code());
    }
}
