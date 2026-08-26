package com.auralink.workflow.capability;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowOperation;

/**
 * Single source of truth for workflow definition capabilities.
 *
 * <p>This catalog is static metadata. It never reads credentials, probes a
 * provider, or claims that an operation can currently execute.</p>
 */
@Component
public class WorkflowCapabilityRegistry {

    public static final String DEFERRED_REASON = "CREATION_EXECUTION_ENGINE_DEFERRED_TO_ROUND_9B2";
    public static final String VIDEO_RESERVED_REASON = "RESERVED_FOR_FUTURE_IMPLEMENTATION";

    private static final List<WorkflowModality> SOURCE_MODALITIES = List.of(
            WorkflowModality.TEXT_DESCRIPTION,
            WorkflowModality.POEM,
            WorkflowModality.IMAGE,
            WorkflowModality.PAINTING);

    private final Map<WorkflowOperation, WorkflowOperationCapability> byOperation;
    private final List<WorkflowOperationCapability> operations;

    public WorkflowCapabilityRegistry() {
        EnumMap<WorkflowOperation, WorkflowOperationCapability> rules =
                new EnumMap<>(WorkflowOperation.class);
        register(rules, enabled(
                WorkflowOperation.TEXT_TO_PAINTING,
                "Text description to painting",
                WorkflowModality.TEXT_DESCRIPTION,
                WorkflowModality.PAINTING,
                false,
                provider("seedream-5", "Seedream 5", true)));
        register(rules, enabled(
                WorkflowOperation.POEM_TO_PAINTING,
                "Poem to painting",
                WorkflowModality.POEM,
                WorkflowModality.PAINTING,
                false,
                provider("qwen3vl-seedream5", "Qwen3-VL and Seedream 5", true)));
        register(rules, enabled(
                WorkflowOperation.IMAGE_TO_PAINTING,
                "Image to painting",
                WorkflowModality.IMAGE,
                WorkflowModality.PAINTING,
                false,
                provider("seedream-5", "Seedream 5", true)));
        register(rules, enabled(
                WorkflowOperation.PAINTING_TO_MUSIC,
                "Painting to music",
                WorkflowModality.PAINTING,
                WorkflowModality.AUDIO,
                true,
                provider("auralink-vmm", "Auralink VMM", true)));
        register(rules, enabled(
                WorkflowOperation.PAINTING_TO_POEM,
                "Painting to poem",
                WorkflowModality.PAINTING,
                WorkflowModality.POEM,
                false,
                provider("qwen3-vl-plus", "Qwen3-VL-Plus", true)));
        register(rules, new WorkflowOperationCapability(
                WorkflowOperation.PAINTING_TO_VIDEO,
                "Painting to video",
                WorkflowModality.PAINTING,
                WorkflowModality.VIDEO,
                false,
                false,
                true,
                VIDEO_RESERVED_REASON,
                List.of(provider("reserved-video", "Reserved video", false))));

        byOperation = Map.copyOf(rules);
        operations = List.of(
                rules.get(WorkflowOperation.TEXT_TO_PAINTING),
                rules.get(WorkflowOperation.POEM_TO_PAINTING),
                rules.get(WorkflowOperation.IMAGE_TO_PAINTING),
                rules.get(WorkflowOperation.PAINTING_TO_MUSIC),
                rules.get(WorkflowOperation.PAINTING_TO_POEM),
                rules.get(WorkflowOperation.PAINTING_TO_VIDEO));
    }

    public List<WorkflowModality> sourceModalities() {
        return SOURCE_MODALITIES;
    }

    public List<WorkflowOperationCapability> operations() {
        return operations;
    }

    public Optional<WorkflowOperationCapability> find(String operationCode) {
        if (operationCode == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(byOperation.get(WorkflowOperation.valueOf(operationCode)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public WorkflowOperationCapability require(WorkflowOperation operation) {
        WorkflowOperationCapability capability = byOperation.get(operation);
        if (capability == null) {
            throw new IllegalArgumentException("Unknown workflow operation");
        }
        return capability;
    }

    private static WorkflowOperationCapability enabled(
            WorkflowOperation operation,
            String displayName,
            WorkflowModality input,
            WorkflowModality output,
            boolean terminalOutput,
            WorkflowProviderCapability provider) {
        return new WorkflowOperationCapability(
                operation,
                displayName,
                input,
                output,
                true,
                false,
                terminalOutput,
                DEFERRED_REASON,
                List.of(provider));
    }

    private static WorkflowProviderCapability provider(
            String code,
            String displayName,
            boolean definitionEnabled) {
        return new WorkflowProviderCapability(
                code,
                displayName,
                definitionEnabled,
                false,
                WorkflowParameterSchema.emptyStrictObject());
    }

    private static void register(
            Map<WorkflowOperation, WorkflowOperationCapability> rules,
            WorkflowOperationCapability capability) {
        rules.put(capability.operation(), capability);
    }
}
