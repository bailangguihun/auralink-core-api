package com.auralink.creation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.auralink.config.properties.CreationExecutionProperties;
import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.entity.Creation;
import com.auralink.entity.CreationStep;
import com.auralink.entity.CreationStepDispatchAttempt;
import com.auralink.provider.qwen.PaintingPoemResultValidator;
import com.auralink.repository.CreationExecutionAttemptRepository;
import com.auralink.repository.CreationStepDispatchAttemptRepository;
import com.auralink.service.media.MediaAssetStorageService;
import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowOperation;
import com.auralink.workflow.capability.WorkflowCapabilityRegistry;
import com.auralink.workflow.capability.WorkflowOperationCapability;
import com.auralink.workflow.capability.WorkflowProviderCapability;
import com.auralink.workflow.graph.CanonicalWorkflowEdge;
import com.auralink.workflow.graph.CanonicalWorkflowGraph;
import com.auralink.workflow.graph.CanonicalWorkflowNode;
import com.auralink.workflow.snapshot.WorkflowSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;

class CreationRetryEligibilityServiceTest {

    @Test
    void identifiesAnUnsentFailedBoundaryAsSafelyRetryable() throws Exception {
        Fixture fixture = fixture();
        when(fixture.dispatchAttempts.findByCreationStepIdOrderByIdAsc(22L)).thenReturn(List.of());

        CreationRetryEligibilityService.RetryAssessment assessment = fixture.service.assess(
                fixture.creation, List.of(fixture.failedStep));

        assertThat(assessment.available()).isTrue();
        assertThat(assessment.boundaryIndex()).isZero();
        assertThat(assessment.blockedReason()).isNull();
    }

    @Test
    void blocksHistoricalSendStartedWithoutResettingAnyStep() throws Exception {
        Fixture fixture = fixture();
        when(fixture.dispatchAttempts.findByCreationStepIdOrderByIdAsc(22L)).thenReturn(List.of(
                CreationStepDispatchAttempt.builder()
                        .dispatchState(ProviderDispatchState.SEND_STARTED.name())
                        .providerRequestKey("historical-provider-request-key")
                        .build()));

        CreationRetryEligibilityService.RetryAssessment assessment = fixture.service.assess(
                fixture.creation, List.of(fixture.failedStep));

        assertThat(assessment.available()).isFalse();
        assertThat(assessment.blockedReason()).isEqualTo(CreationRetryEligibilityService.AMBIGUOUS);
    }

    private Fixture fixture() throws Exception {
        CreationExecutionProperties execution = new CreationExecutionProperties();
        execution.setEnabled(true);
        CreationProviderProperties providers = new CreationProviderProperties();
        CreationExecutionCapabilityService capabilities = mock(CreationExecutionCapabilityService.class);
        WorkflowCapabilityRegistry workflowCapabilities = mock(WorkflowCapabilityRegistry.class);
        CreationExecutionAttemptRepository executionAttempts = mock(CreationExecutionAttemptRepository.class);
        CreationStepDispatchAttemptRepository dispatchAttempts = mock(CreationStepDispatchAttemptRepository.class);
        WorkflowOperationCapability capability = new WorkflowOperationCapability(
                WorkflowOperation.TEXT_TO_PAINTING,
                "Text to painting",
                WorkflowModality.TEXT_DESCRIPTION,
                WorkflowModality.PAINTING,
                true,
                true,
                true,
                "READY_FOR_CONTROLLED_EXECUTION",
                List.of(new WorkflowProviderCapability("seedream-5", "Mock", true, true, null)));
        when(workflowCapabilities.require(WorkflowOperation.TEXT_TO_PAINTING)).thenReturn(capability);
        when(capabilities.availability(WorkflowOperation.TEXT_TO_PAINTING, "seedream-5"))
                .thenReturn(new CreationExecutionCapabilityService.ExecutionAvailability(
                        true, "READY_FOR_CONTROLLED_EXECUTION"));
        when(executionAttempts.findByCreationIdAndFinishedAtIsNull(11L)).thenReturn(Optional.empty());

        ObjectMapper mapper = new ObjectMapper();
        WorkflowSnapshot snapshot = new WorkflowSnapshot(
                1,
                "00000000-0000-0000-0000-000000000111",
                "Retry fixture",
                1,
                new CanonicalWorkflowGraph(
                        1,
                        List.of(
                                CanonicalWorkflowNode.source("source", WorkflowModality.TEXT_DESCRIPTION),
                                CanonicalWorkflowNode.transform("step-0", WorkflowOperation.TEXT_TO_PAINTING,
                                        "seedream-5", WorkflowModality.TEXT_DESCRIPTION, WorkflowModality.PAINTING)),
                        List.of(new CanonicalWorkflowEdge("source", "step-0"))));
        Creation creation = Creation.builder()
                .workflowSnapshot(mapper.writeValueAsString(snapshot))
                .sourceModality(WorkflowModality.TEXT_DESCRIPTION.name())
                .sourceText("可安全重试")
                .status(CreationStatus.FAILED.name())
                .retryVersion(0)
                .build();
        creation.setId(11L);
        CreationStep failedStep = CreationStep.builder()
                .creation(creation)
                .stepIndex(0)
                .nodeId("step-0")
                .operationCode(WorkflowOperation.TEXT_TO_PAINTING.name())
                .providerCode("seedream-5")
                .inputModality(WorkflowModality.TEXT_DESCRIPTION.name())
                .outputModality(WorkflowModality.PAINTING.name())
                .status(CreationStepStatus.FAILED.name())
                .providerDispatchState(ProviderDispatchState.NOT_SENT.name())
                .build();
        failedStep.setId(22L);
        CreationRetryEligibilityService service = new CreationRetryEligibilityService(
                execution,
                providers,
                capabilities,
                workflowCapabilities,
                executionAttempts,
                dispatchAttempts,
                mock(PaintingPoemResultValidator.class),
                mock(MediaAssetStorageService.class),
                mapper);
        return new Fixture(service, creation, failedStep, dispatchAttempts);
    }

    private record Fixture(
            CreationRetryEligibilityService service,
            Creation creation,
            CreationStep failedStep,
            CreationStepDispatchAttemptRepository dispatchAttempts) {
    }
}
