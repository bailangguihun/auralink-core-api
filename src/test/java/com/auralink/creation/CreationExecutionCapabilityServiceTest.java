package com.auralink.creation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.creation.provider.CreationProviderAdapter;
import com.auralink.creation.provider.ProviderAdapterRegistry;
import com.auralink.creation.provider.ProviderReadiness;
import com.auralink.creation.provider.ProviderReadinessState;
import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowOperation;
import com.auralink.workflow.capability.WorkflowCapabilityRegistry;
import com.auralink.workflow.capability.WorkflowOperationCapability;
import com.auralink.workflow.capability.WorkflowParameterSchema;
import com.auralink.workflow.capability.WorkflowProviderCapability;
import com.auralink.workflow.service.WorkflowExecutionPreparer.PreparedTransform;
import com.auralink.workflow.service.WorkflowExecutionPreparer.PreparedWorkflow;

@ExtendWith(MockitoExtension.class)
class CreationExecutionCapabilityServiceTest {

    @Mock private WorkflowCapabilityRegistry workflowCapabilities;
    @Mock private ProviderAdapterRegistry adapters;
    @Mock private CreationProviderAdapter adapter;

    private CreationExecutionCapabilityService service;

    @BeforeEach
    void setUp() {
        service = new CreationExecutionCapabilityService(workflowCapabilities, adapters);
    }

    @Test
    void conditionallyAdmitsEachApprovedOperationOnlyWhenExactAdapterIsReady() {
        for (WorkflowOperation operation : List.of(
                WorkflowOperation.TEXT_TO_PAINTING,
                WorkflowOperation.IMAGE_TO_PAINTING,
                WorkflowOperation.PAINTING_TO_POEM,
                WorkflowOperation.POEM_TO_PAINTING)) {
            PreparedTransform transform = transform(operation);
            when(workflowCapabilities.require(operation)).thenReturn(capability(operation, transform.providerCode()));
            when(adapters.find(operation, transform.providerCode())).thenReturn(Optional.of(adapter));
            when(adapters.readiness(operation, transform.providerCode())).thenReturn(
                    new ProviderReadiness(
                            ProviderReadinessState.READY_FOR_CONTROLLED_EXECUTION,
                            "READY_FOR_CONTROLLED_EXECUTION"));

            assertDoesNotThrow(() -> service.requireExecutionAvailable(workflow(transform)));
        }
    }

    @Test
    void providerConfigurationFailureIsRejectedBeforeAnyProviderExecution() {
        PreparedTransform transform = transform(WorkflowOperation.TEXT_TO_PAINTING);
        when(workflowCapabilities.require(transform.operation()))
                .thenReturn(capability(transform.operation(), transform.providerCode()));
        when(adapters.find(transform.operation(), transform.providerCode())).thenReturn(Optional.of(adapter));
        when(adapters.readiness(transform.operation(), transform.providerCode())).thenReturn(
                new ProviderReadiness(ProviderReadinessState.CONFIGURATION_INVALID, "CONFIGURATION_INVALID"));

        ApiV1Exception exception = assertThrows(ApiV1Exception.class,
                () -> service.requireExecutionAvailable(workflow(transform)));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("CREATION_OPERATION_UNAVAILABLE", exception.getCode().name());
    }

    @Test
    void musicAndVideoAreAlwaysRejectedWithoutReadingAnAdapter() {
        ApiV1Exception music = assertThrows(ApiV1Exception.class,
                () -> service.requireExecutionAvailable(workflow(transform(WorkflowOperation.PAINTING_TO_MUSIC))));
        assertEquals("CREATION_OPERATION_UNAVAILABLE", music.getCode().name());
        assertEquals(HttpStatus.CONFLICT, music.getStatus());
        verify(adapters, never()).find(any(), any());
        verify(adapters, never()).readiness(any(), any());

        ApiV1Exception video = assertThrows(ApiV1Exception.class,
                () -> service.requireExecutionAvailable(workflow(transform(WorkflowOperation.PAINTING_TO_VIDEO))));
        assertEquals("CREATION_VIDEO_RESERVED", video.getCode().name());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, video.getStatus());
        verify(workflowCapabilities, never()).require(eq(WorkflowOperation.PAINTING_TO_VIDEO));
    }

    private static PreparedWorkflow workflow(PreparedTransform transform) {
        return new PreparedWorkflow(null, null, "{}", transform.inputModality(), transform.outputModality(),
                List.of(transform));
    }

    private static PreparedTransform transform(WorkflowOperation operation) {
        return new PreparedTransform(
                "step",
                operation,
                provider(operation),
                input(operation),
                output(operation),
                "{}");
    }

    private static WorkflowOperationCapability capability(WorkflowOperation operation, String providerCode) {
        return new WorkflowOperationCapability(
                operation,
                operation.name(),
                input(operation),
                output(operation),
                true,
                false,
                false,
                "CREATION_EXECUTION_ENGINE_DEFERRED_TO_ROUND_9B2",
                List.of(new WorkflowProviderCapability(
                        providerCode, providerCode, true, false, WorkflowParameterSchema.emptyStrictObject())));
    }

    private static String provider(WorkflowOperation operation) {
        return switch (operation) {
            case TEXT_TO_PAINTING, IMAGE_TO_PAINTING -> "seedream-5";
            case POEM_TO_PAINTING -> "qwen3vl-seedream5";
            case PAINTING_TO_POEM -> "qwen3-vl-plus";
            case PAINTING_TO_MUSIC -> "auralink-vmm";
            case PAINTING_TO_VIDEO -> "reserved-video";
        };
    }

    private static WorkflowModality input(WorkflowOperation operation) {
        return switch (operation) {
            case TEXT_TO_PAINTING -> WorkflowModality.TEXT_DESCRIPTION;
            case POEM_TO_PAINTING -> WorkflowModality.POEM;
            case IMAGE_TO_PAINTING -> WorkflowModality.IMAGE;
            case PAINTING_TO_POEM, PAINTING_TO_MUSIC, PAINTING_TO_VIDEO -> WorkflowModality.PAINTING;
        };
    }

    private static WorkflowModality output(WorkflowOperation operation) {
        return switch (operation) {
            case TEXT_TO_PAINTING, POEM_TO_PAINTING, IMAGE_TO_PAINTING -> WorkflowModality.PAINTING;
            case PAINTING_TO_POEM -> WorkflowModality.POEM;
            case PAINTING_TO_MUSIC -> WorkflowModality.AUDIO;
            case PAINTING_TO_VIDEO -> WorkflowModality.VIDEO;
        };
    }
}
