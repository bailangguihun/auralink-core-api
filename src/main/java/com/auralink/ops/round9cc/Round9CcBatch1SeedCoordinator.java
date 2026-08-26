package com.auralink.ops.round9cc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.auralink.api.v1.creation.CreationQueuedResponse;
import com.auralink.api.v1.creation.CreationSourceRequest;
import com.auralink.api.v1.creation.CreationSubmissionRequest;
import com.auralink.creation.CreationQueueDispatcher;
import com.auralink.creation.CreationRecoveryGate;
import com.auralink.creation.CreationStatus;
import com.auralink.creation.CreationStepStatus;
import com.auralink.creation.CreationSubmissionService;
import com.auralink.entity.Creation;
import com.auralink.entity.CreationStep;
import com.auralink.entity.User;
import com.auralink.entity.UserWorkflow;
import com.auralink.repository.CreationExecutionAttemptRepository;
import com.auralink.repository.CreationRepository;
import com.auralink.repository.CreationStepRepository;
import com.auralink.repository.GenerationLogRepository;
import com.auralink.repository.PaintingRepository;
import com.auralink.repository.UserRepository;
import com.auralink.repository.UserWorkflowRepository;
import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowOperation;
import com.auralink.workflow.graph.CanonicalWorkflowEdge;
import com.auralink.workflow.graph.CanonicalWorkflowGraph;
import com.auralink.workflow.graph.CanonicalWorkflowNode;
import com.auralink.workflow.graph.WorkflowGraphCodec;

/**
 * Explicitly registered only by the private Round9Cc Harness. It seeds the
 * four Batch 1 scenarios through normal admission, never through a Worker.
 */
final class Round9CcBatch1SeedCoordinator {

    private final Round9CcPackagedFailureHarness.HarnessState state;
    private final UserRepository users;
    private final UserWorkflowRepository workflows;
    private final CreationSubmissionService submissions;
    private final CreationQueueDispatcher dispatcher;
    private final CreationRecoveryGate recoveryGate;
    private final CreationRepository creations;
    private final CreationStepRepository steps;
    private final CreationExecutionAttemptRepository executionAttempts;
    private final GenerationLogRepository generationLogs;
    private final PaintingRepository paintings;
    private final WorkflowGraphCodec workflowCodec;

    Round9CcBatch1SeedCoordinator(
            Round9CcPackagedFailureHarness.HarnessState state,
            UserRepository users,
            UserWorkflowRepository workflows,
            CreationSubmissionService submissions,
            CreationQueueDispatcher dispatcher,
            CreationRecoveryGate recoveryGate,
            CreationRepository creations,
            CreationStepRepository steps,
            CreationExecutionAttemptRepository executionAttempts,
            GenerationLogRepository generationLogs,
            PaintingRepository paintings,
            WorkflowGraphCodec workflowCodec) {
        this.state = state;
        this.users = users;
        this.workflows = workflows;
        this.submissions = submissions;
        this.dispatcher = dispatcher;
        this.recoveryGate = recoveryGate;
        this.creations = creations;
        this.steps = steps;
        this.executionAttempts = executionAttempts;
        this.generationLogs = generationLogs;
        this.paintings = paintings;
        this.workflowCodec = workflowCodec;
    }

    void seed(Round9CcPackagedFailureHarness.Launch launch) {
        require(launch, Round9CcRunPhase.SEED);
        if (!recoveryGate.isOpen() || creations.count() != 0 || generationLogs.count() != 0 || paintings.count() != 0) {
            throw invalid();
        }
        User owner = users.saveAndFlush(User.builder()
                .username("round9cc-batch1-" + launch.instance())
                .password("fixture-only")
                .fullName("ROUND 9C-C Batch 1 Fixture")
                .email("round9cc-batch1-" + launch.instance() + "@example.invalid")
                .build());
        UserWorkflow workflow = workflows.saveAndFlush(UserWorkflow.builder()
                .user(owner)
                .name("ROUND 9C-C " + launch.scenario().name())
                .graphJson(workflowCodec.encode(new CanonicalWorkflowGraph(
                        1,
                        List.of(
                                CanonicalWorkflowNode.source("source", WorkflowModality.TEXT_DESCRIPTION),
                                CanonicalWorkflowNode.transform(
                                        "painting", WorkflowOperation.TEXT_TO_PAINTING, "seedream-5",
                                        WorkflowModality.TEXT_DESCRIPTION, WorkflowModality.PAINTING)),
                        List.of(new CanonicalWorkflowEdge("source", "painting")))))
                .schemaVersion(1)
                .status("ACTIVE")
                .build());
        CreationQueuedResponse queued = submit(owner, workflow, launch.scenario());
        Creation creation = creations.findByPublicId(queued.creationId()).orElseThrow(Round9CcBatch1SeedCoordinator::invalid);
        assertQueuedSeed(creation);
        Round9CcPackagedFailureHarness.writePrivate(launch.fixture().runtimeFile(launch.instance(), "seed"),
                "SCENARIO=" + launch.scenario().name() + "\n"
                        + "ROLE=" + launch.role() + "\n"
                        + "CREATIONS=1\n"
                        + "EXECUTION_ATTEMPTS=1\n"
                        + "MOCK_PROVIDER_CALLS=0\n");
    }

    void requireSeeded(Round9CcPackagedFailureHarness.Launch launch) {
        require(launch, Round9CcRunPhase.INITIAL);
        Creation creation = onlyCreation();
        if (generationLogs.count() != 0 || paintings.count() != 0) {
            throw invalid();
        }
        assertQueuedSeed(creation);
    }

    void beginInitialExecution(Round9CcPackagedFailureHarness.Launch launch) {
        require(launch, Round9CcRunPhase.INITIAL);
        if (launch.scenario() == Round9CcScenario.TERM_BEFORE_CLAIM) {
            return;
        }
        if (!recoveryGate.isOpen()) {
            throw invalid();
        }
        dispatcher.dispatchOne();
    }

    void verifyRecovered(Round9CcPackagedFailureHarness.Launch launch) {
        require(launch, Round9CcRunPhase.RECOVERY);
        if (!recoveryGate.isOpen() || generationLogs.count() != 0 || paintings.count() != 0) {
            throw invalid();
        }
        Creation creation = onlyCreation();
        List<CreationStep> persistedSteps = steps.findByCreationIdOrderByStepIndexAsc(creation.getId());
        Round9CcScenario.Definition expected = launch.scenario().definition();
        if (persistedSteps.size() != 1
                || !expected.creationStatus().equals(creation.getStatus())
                || !expected.stepStatus().equals(persistedSteps.get(0).getStatus())
                || !expected.dispatchState().equals(persistedSteps.get(0).getProviderDispatchState())
                || executionAttempts.countByCreationId(creation.getId()) != 1
                || !activeAttemptMatches(creation, expected.attemptState())
                || !claimLeaseMatches(creation, expected.claimLease())
                || !safeCodeMatches(creation, expected.safeCode())
                || !journalIsExpected(expected)
                || !fixtureFilesMatch(launch.fixture(), expected.expectedFiles())) {
            throw invalid();
        }
        Round9CcPackagedFailureHarness.writePrivate(launch.fixture().runtimeFile(launch.instance(), "recovery"),
                "SCENARIO=" + launch.scenario().name() + "\n"
                        + "ROLE=" + launch.role() + "\n"
                        + "RECOVERY_GATE_OPEN\n"
                        + "RECOVERY_PROVIDER_CALLS=" + expected.recoveryCalls() + "\n"
                        + "ORDINARY_DISPATCH_RESUMES=" + expected.ordinaryDispatch() + "\n");
    }

    private CreationQueuedResponse submit(User owner, UserWorkflow workflow, Round9CcScenario scenario) {
        CreationSourceRequest source = new CreationSourceRequest();
        source.setModality(WorkflowModality.TEXT_DESCRIPTION.name());
        source.setText("ROUND9CC_BATCH1_" + scenario.name());
        CreationSubmissionRequest request = new CreationSubmissionRequest();
        request.setWorkflowId(workflow.getPublicId());
        request.setSource(source);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                owner.getUsername(), "fixture-only", owner.getAuthorities()));
        try {
            return submissions.submit(request);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void assertQueuedSeed(Creation creation) {
        List<CreationStep> persistedSteps = steps.findByCreationIdOrderByStepIndexAsc(creation.getId());
        if (!CreationStatus.QUEUED.name().equals(creation.getStatus())
                || creation.getClaimToken() != null || creation.getLeaseExpiresAt() != null
                || persistedSteps.size() != 1
                || !CreationStepStatus.PENDING.name().equals(persistedSteps.get(0).getStatus())
                || !"NOT_SENT".equals(persistedSteps.get(0).getProviderDispatchState())
                || executionAttempts.countByCreationId(creation.getId()) != 1
                || executionAttempts.findByCreationIdAndFinishedAtIsNull(creation.getId()).isEmpty()) {
            throw invalid();
        }
    }

    private Creation onlyCreation() {
        if (creations.count() != 1) {
            throw invalid();
        }
        return creations.findAll().stream().findFirst().orElseThrow(Round9CcBatch1SeedCoordinator::invalid);
    }

    private boolean activeAttemptMatches(Creation creation, String expected) {
        boolean active = executionAttempts.findByCreationIdAndFinishedAtIsNull(creation.getId()).isPresent();
        return ("ACTIVE".equals(expected) && active) || ("FINISHED".equals(expected) && !active);
    }

    private boolean claimLeaseMatches(Creation creation, String expected) {
        boolean clear = creation.getClaimToken() == null && creation.getLeaseExpiresAt() == null;
        boolean present = creation.getClaimToken() != null && creation.getLeaseExpiresAt() != null;
        return ("CLEAR".equals(expected) && clear) || ("PRESENT".equals(expected) && present);
    }

    private boolean safeCodeMatches(Creation creation, String expected) {
        return "NONE".equals(expected)
                ? creation.getErrorCode() == null && creation.getErrorMessage() == null
                : expected.equals(creation.getErrorCode()) && expected.equals(creation.getErrorMessage());
    }

    private boolean journalIsExpected(Round9CcScenario.Definition expected) {
        List<Round9CcMockJournal.Record> records = Round9CcMockJournal.read(state.journal().file());
        return count(records, Round9CcMockJournal.Event.ENTRY) == expected.entry()
                && count(records, Round9CcMockJournal.Event.RETURN) == expected.returned()
                && count(records, Round9CcMockJournal.Event.CLOSE) == expected.close();
    }

    private boolean fixtureFilesMatch(Round9CcFixture fixture, String expected) {
        if (!"none".equals(expected)) {
            return false;
        }
        return directoryEmpty(fixture.requireDirectory("managed"))
                && directoryEmpty(fixture.requireDirectory("provider-staging"));
    }

    private boolean directoryEmpty(Path directory) {
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        } catch (IOException exception) {
            return false;
        }
    }

    private static int count(List<Round9CcMockJournal.Record> records, Round9CcMockJournal.Event event) {
        return (int) records.stream().filter(record -> record.event() == event).count();
    }

    private static void require(Round9CcPackagedFailureHarness.Launch launch, Round9CcRunPhase phase) {
        if (launch == null || !launch.scenario().isBatch1() || launch.phase() != phase) {
            throw invalid();
        }
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("ROUND9CC_ERROR:BATCH1_STATE_INVALID");
    }
}
