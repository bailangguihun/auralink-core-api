package com.auralink.ops.round9cc;

import java.time.Duration;
import java.util.List;

import org.springframework.context.ConfigurableApplicationContext;
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
import com.auralink.creation.ProviderDispatchState;
import com.auralink.entity.Creation;
import com.auralink.entity.CreationStep;
import com.auralink.entity.CreationStepDispatchAttempt;
import com.auralink.entity.User;
import com.auralink.entity.UserWorkflow;
import com.auralink.repository.CreationExecutionAttemptRepository;
import com.auralink.repository.CreationRepository;
import com.auralink.repository.CreationStepDispatchAttemptRepository;
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

/** Dedicated-harness NORMAL_COMPLETION admission and terminal verification. */
final class Round9CcNormalCompletionCoordinator {

    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(60);

    private final Round9CcPackagedFailureHarness.HarnessState state;
    private final UserRepository users;
    private final UserWorkflowRepository workflows;
    private final CreationSubmissionService submissions;
    private final CreationQueueDispatcher dispatcher;
    private final CreationRecoveryGate recoveryGate;
    private final CreationRepository creations;
    private final CreationStepRepository steps;
    private final CreationExecutionAttemptRepository executionAttempts;
    private final CreationStepDispatchAttemptRepository dispatchAttempts;
    private final GenerationLogRepository generationLogs;
    private final PaintingRepository paintings;
    private final WorkflowGraphCodec workflowCodec;

    Round9CcNormalCompletionCoordinator(
            ConfigurableApplicationContext context,
            Round9CcPackagedFailureHarness.HarnessState state) {
        this.state = state;
        this.users = context.getBean(UserRepository.class);
        this.workflows = context.getBean(UserWorkflowRepository.class);
        this.submissions = context.getBean(CreationSubmissionService.class);
        this.dispatcher = context.getBean(CreationQueueDispatcher.class);
        this.recoveryGate = context.getBean(CreationRecoveryGate.class);
        this.creations = context.getBean(CreationRepository.class);
        this.steps = context.getBean(CreationStepRepository.class);
        this.executionAttempts = context.getBean(CreationExecutionAttemptRepository.class);
        this.dispatchAttempts = context.getBean(CreationStepDispatchAttemptRepository.class);
        this.generationLogs = context.getBean(GenerationLogRepository.class);
        this.paintings = context.getBean(PaintingRepository.class);
        this.workflowCodec = context.getBean(WorkflowGraphCodec.class);
    }

    Completion run(Round9CcPackagedFailureHarness.Launch launch) {
        if (launch.scenario() != Round9CcScenario.NORMAL_COMPLETION || !recoveryGate.isOpen()) {
            throw new IllegalStateException("ROUND 9C-C normal completion cannot start safely");
        }
        Duration timeout = boundedTimeout(launch.timeout());
        if (creations.count() != 0 || generationLogs.count() != 0 || paintings.count() != 0) {
            throw new IllegalStateException("ROUND 9C-C normal completion fixture is not empty");
        }

        User owner = users.saveAndFlush(User.builder()
                .username("round9cc-" + launch.instance())
                .password("fixture-only")
                .fullName("ROUND 9C-C Fixture")
                .email("round9cc-" + launch.instance() + "@example.invalid")
                .build());
        UserWorkflow workflow = workflows.saveAndFlush(UserWorkflow.builder()
                .user(owner)
                .name("ROUND 9C-C NORMAL_COMPLETION")
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

        CreationQueuedResponse queued = submit(owner, workflow);
        Creation creation = creations.findByPublicId(queued.creationId()).orElseThrow(
                () -> new IllegalStateException("ROUND 9C-C normal completion seed is missing"));
        if (creations.count() != 1 || !CreationStatus.QUEUED.name().equals(creation.getStatus())
                || executionAttempts.countByCreationId(creation.getId()) != 1
                || steps.findByCreationIdOrderByStepIndexAsc(creation.getId()).size() != 1) {
            throw new IllegalStateException("ROUND 9C-C normal completion seed is invalid");
        }

        dispatcher.dispatchOne();
        return verifyTerminal(awaitTerminal(queued.creationId(), launch.scenario(), timeout), launch.scenario());
    }

    private CreationQueuedResponse submit(User owner, UserWorkflow workflow) {
        CreationSourceRequest source = new CreationSourceRequest();
        source.setModality(WorkflowModality.TEXT_DESCRIPTION.name());
        source.setText("ROUND9CC_NORMAL_COMPLETION");
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

    private Creation awaitTerminal(String publicId, Round9CcScenario scenario, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Creation creation = creations.findByPublicId(publicId).orElseThrow(
                    () -> new IllegalStateException("ROUND 9C-C normal completion seed is missing"));
            if (CreationStatus.SUCCEEDED.name().equals(creation.getStatus()) && matchesExpectedJournal(scenario)) {
                return creation;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("ROUND 9C-C normal completion interrupted");
            }
        }
        throw new IllegalStateException("ROUND 9C-C normal completion timed out");
    }

    private Completion verifyTerminal(Creation creation, Round9CcScenario scenario) {
        List<CreationStep> persistedSteps = steps.findByCreationIdOrderByStepIndexAsc(creation.getId());
        if (!CreationStatus.SUCCEEDED.name().equals(creation.getStatus())
                || creation.getClaimToken() != null || creation.getLeaseExpiresAt() != null
                || creation.getRetryVersion() != 0 || persistedSteps.size() != 1
                || executionAttempts.countByCreationId(creation.getId()) != 1
                || generationLogs.count() != 0 || paintings.count() != 0) {
            throw new IllegalStateException("ROUND 9C-C normal completion terminal state is invalid");
        }
        CreationStep step = persistedSteps.get(0);
        List<CreationStepDispatchAttempt> dispatches = dispatchAttempts.findByCreationStepIdOrderByIdAsc(step.getId());
        if (!CreationStepStatus.SUCCEEDED.name().equals(step.getStatus())
                || !ProviderDispatchState.RESULT_PERSISTED.name().equals(step.getProviderDispatchState())
                || step.getAttemptCount() != 1 || dispatches.size() != 1
                || !ProviderDispatchState.RESULT_PERSISTED.name().equals(dispatches.get(0).getDispatchState())
                || executionAttempts.findByCreationIdAndFinishedAtIsNull(creation.getId()).isPresent()
                || !matchesExpectedJournal(scenario)) {
            throw new IllegalStateException("ROUND 9C-C normal completion evidence is invalid");
        }
        List<Round9CcMockJournal.Record> journal = Round9CcMockJournal.read(state.journal().file());
        return new Completion(
                creation.getPublicId(), creation.getStatus(), step.getStatus(), step.getProviderDispatchState(),
                executionAttempts.findByCreationIdAndFinishedAtIsNull(creation.getId()).isEmpty(),
                creation.getClaimToken() == null && creation.getLeaseExpiresAt() == null, creation.getRetryVersion(),
                count(journal, Round9CcMockJournal.Event.ENTRY),
                count(journal, Round9CcMockJournal.Event.RETURN), count(journal, Round9CcMockJournal.Event.CLOSE));
    }

    private boolean matchesExpectedJournal(Round9CcScenario scenario) {
        List<Round9CcMockJournal.Record> journal = Round9CcMockJournal.read(state.journal().file());
        Round9CcScenario.Definition expected = scenario.definition();
        return count(journal, Round9CcMockJournal.Event.ENTRY) == expected.entry()
                && count(journal, Round9CcMockJournal.Event.RETURN) == expected.returned()
                && count(journal, Round9CcMockJournal.Event.CLOSE) == expected.close();
    }

    private static int count(List<Round9CcMockJournal.Record> journal, Round9CcMockJournal.Event event) {
        return (int) journal.stream().filter(record -> record.event() == event).count();
    }

    static Duration boundedTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("ROUND 9C-C normal completion timeout is invalid");
        }
        return timeout;
    }

    record Completion(
            String creationId,
            String creationStatus,
            String stepStatus,
            String dispatchState,
            boolean executionFinished,
            boolean claimAndLeaseClear,
            int retryVersion,
            int entries,
            int returns,
            int closes) {
    }
}
