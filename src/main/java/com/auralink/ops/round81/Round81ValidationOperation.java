package com.auralink.ops.round81;

import java.util.Arrays;

import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowOperation;

/** Frozen one-operation validation modes and exact local call budgets. */
public enum Round81ValidationOperation {
    TEXT_TO_PAINTING(
            "text-to-painting",
            WorkflowOperation.TEXT_TO_PAINTING,
            "seedream-5",
            WorkflowModality.TEXT_DESCRIPTION,
            WorkflowModality.PAINTING,
            "VALIDATE_ONE_LIVE_TEXT_TO_PAINTING",
            1, 0, 0,
            false),
    IMAGE_TO_PAINTING(
            "image-to-painting",
            WorkflowOperation.IMAGE_TO_PAINTING,
            "seedream-5",
            WorkflowModality.IMAGE,
            WorkflowModality.PAINTING,
            "VALIDATE_ONE_LIVE_IMAGE_TO_PAINTING",
            1, 0, 0,
            true),
    POEM_TO_PAINTING(
            "poem-to-painting",
            WorkflowOperation.POEM_TO_PAINTING,
            "qwen3vl-seedream5",
            WorkflowModality.POEM,
            WorkflowModality.PAINTING,
            "VALIDATE_ONE_LIVE_POEM_TO_PAINTING",
            1, 1, 0,
            false),
    PAINTING_TO_POEM(
            "painting-to-poem",
            WorkflowOperation.PAINTING_TO_POEM,
            "qwen3-vl-plus",
            WorkflowModality.PAINTING,
            WorkflowModality.POEM,
            "VALIDATE_ONE_LIVE_PAINTING_TO_POEM",
            0, 1, 0,
            true),
    PAINTING_TO_MUSIC(
            "painting-to-music",
            WorkflowOperation.PAINTING_TO_MUSIC,
            "auralink-vmm",
            WorkflowModality.PAINTING,
            WorkflowModality.AUDIO,
            "VALIDATE_ONE_LIVE_PAINTING_TO_MUSIC",
            0, 0, 1,
            true);

    private final String token;
    private final WorkflowOperation workflowOperation;
    private final String providerCode;
    private final WorkflowModality inputModality;
    private final WorkflowModality outputModality;
    private final String confirmation;
    private final int seedreamCalls;
    private final int qwenCalls;
    private final int vmmCalls;
    private final boolean imageInputRequired;

    Round81ValidationOperation(
            String token,
            WorkflowOperation workflowOperation,
            String providerCode,
            WorkflowModality inputModality,
            WorkflowModality outputModality,
            String confirmation,
            int seedreamCalls,
            int qwenCalls,
            int vmmCalls,
            boolean imageInputRequired) {
        this.token = token;
        this.workflowOperation = workflowOperation;
        this.providerCode = providerCode;
        this.inputModality = inputModality;
        this.outputModality = outputModality;
        this.confirmation = confirmation;
        this.seedreamCalls = seedreamCalls;
        this.qwenCalls = qwenCalls;
        this.vmmCalls = vmmCalls;
        this.imageInputRequired = imageInputRequired;
    }

    public static Round81ValidationOperation fromToken(String token) {
        return Arrays.stream(values())
                .filter(value -> value.token.equals(token))
                .findFirst()
                .orElseThrow(() -> new Round81ValidationException(
                        "UNSUPPORTED_OPERATION", "The validation operation is not supported"));
    }

    public String token() {
        return token;
    }

    public WorkflowOperation workflowOperation() {
        return workflowOperation;
    }

    public String providerCode() {
        return providerCode;
    }

    public WorkflowModality inputModality() {
        return inputModality;
    }

    public WorkflowModality outputModality() {
        return outputModality;
    }

    public String confirmation() {
        return confirmation;
    }

    public int expectedCalls(Round81ProviderFamily family) {
        return switch (family) {
            case SEEDREAM -> seedreamCalls;
            case QWEN -> qwenCalls;
            case VMM -> vmmCalls;
        };
    }

    public boolean imageInputRequired() {
        return imageInputRequired;
    }
}
