package com.auralink.ops.round9b2;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;

import com.auralink.Application;
import com.auralink.api.v1.creation.CreationQueuedResponse;
import com.auralink.api.v1.creation.CreationSourceRequest;
import com.auralink.api.v1.creation.CreationSubmissionRequest;
import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.config.properties.CreationExecutionProperties;
import com.auralink.creation.CreationExecutionTransactionService;
import com.auralink.creation.CreationQueueDispatcher;
import com.auralink.creation.CreationRecoveryCoordinator;
import com.auralink.creation.CreationRecoveryGate;
import com.auralink.creation.CreationResultPersistenceService;
import com.auralink.creation.CreationRetryService;
import com.auralink.creation.CreationExecutionFailure;
import com.auralink.creation.CreationStatus;
import com.auralink.creation.CreationSubmissionService;
import com.auralink.creation.provider.ProviderBinaryOutput;
import com.auralink.creation.provider.ProviderExecutionRequest;
import com.auralink.creation.provider.ProviderExecutionResult;
import com.auralink.creation.provider.ProviderTextInput;
import com.auralink.entity.MediaAsset;
import com.auralink.entity.CreationExecutionAttempt;
import com.auralink.entity.Painting;
import com.auralink.entity.User;
import com.auralink.entity.UserWorkflow;
import com.auralink.media.MediaAssetValues;
import com.auralink.repository.CatalogImportRunRepository;
import com.auralink.repository.CreationRepository;
import com.auralink.repository.CreationExecutionAttemptRepository;
import com.auralink.repository.CreationStepDispatchAttemptRepository;
import com.auralink.repository.CreationStepRepository;
import com.auralink.repository.GenerationLogRepository;
import com.auralink.repository.MediaAssetRepository;
import com.auralink.repository.PaintingRepository;
import com.auralink.repository.UserRepository;
import com.auralink.repository.UserWorkflowRepository;
import com.auralink.service.media.GeneratedAssetRequest;
import com.auralink.service.media.MediaAssetService;
import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowOperation;
import com.auralink.workflow.graph.CanonicalWorkflowEdge;
import com.auralink.workflow.graph.CanonicalWorkflowGraph;
import com.auralink.workflow.graph.CanonicalWorkflowNode;
import com.auralink.workflow.graph.WorkflowGraphCodec;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Disposable, package-executable ROUND 9B.2 smoke harness.  It starts an
 * isolated Spring context and SQLite V1+V2+V3+V4 database with only the explicit
 * in-process mock adapter enabled.  It never reads backend/.env or contacts a
 * network provider.
 */
public final class Round9B2PackagedMockHarness {

    private static final LocalDateTime EXPIRED_LEASE = LocalDateTime.of(2000, 1, 1, 0, 0);

    private static final byte[] PNG = Round9B2MockCreationProviderAdapter.validPng();

    private Round9B2PackagedMockHarness() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("auralink-round9b2-packaged-");
        ConfigurableApplicationContext context = null;
        try {
            context = startContext(root, args);
            run(context, root);
            System.out.println("ROUND9B2_PACKAGED_MOCK_HARNESS_OK");
        } finally {
            SecurityContextHolder.clearContext();
            if (context != null) {
                context.close();
            }
            deleteOwnedTree(root);
        }
    }

    static void run(ConfigurableApplicationContext context, Path root) throws Exception {
        UserRepository users = context.getBean(UserRepository.class);
        UserWorkflowRepository workflows = context.getBean(UserWorkflowRepository.class);
        CreationSubmissionService submission = context.getBean(CreationSubmissionService.class);
        CreationExecutionTransactionService transactions = context.getBean(CreationExecutionTransactionService.class);
        CreationResultPersistenceService results = context.getBean(CreationResultPersistenceService.class);
        CreationRetryService retries = context.getBean(CreationRetryService.class);
        com.auralink.creation.CreationWorker worker = context.getBean(com.auralink.creation.CreationWorker.class);
        CreationRepository creations = context.getBean(CreationRepository.class);
        CreationExecutionAttemptRepository executionAttempts = context.getBean(CreationExecutionAttemptRepository.class);
        CreationStepRepository creationSteps = context.getBean(CreationStepRepository.class);
        CreationStepDispatchAttemptRepository dispatchAttempts =
                context.getBean(CreationStepDispatchAttemptRepository.class);
        CatalogImportRunRepository catalogImportRuns = context.getBean(CatalogImportRunRepository.class);
        MediaAssetService media = context.getBean(MediaAssetService.class);
        PaintingRepository paintings = context.getBean(PaintingRepository.class);
        MediaAssetRepository assets = context.getBean(MediaAssetRepository.class);
        GenerationLogRepository logs = context.getBean(GenerationLogRepository.class);
        WorkflowGraphCodec codec = context.getBean(WorkflowGraphCodec.class);
        Round9B2MockCreationProviderAdapter mock = context.getBean(Round9B2MockCreationProviderAdapter.class);
        CreationExecutionProperties executionProperties = context.getBean(CreationExecutionProperties.class);
        CreationRecoveryCoordinator recovery = context.getBean(CreationRecoveryCoordinator.class);
        CreationRecoveryGate recoveryGate = context.getBean(CreationRecoveryGate.class);
        CreationQueueDispatcher dispatcher = context.getBean(CreationQueueDispatcher.class);
        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);

        ObjectMapper mapper = context.getBean(ObjectMapper.class);
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        require(catalogImportRuns.count() == 0, "startup catalog import must not create an audit row");
        require(recoveryGate.isOpen(), "ApplicationReady startup recovery must open the dispatcher gate");
        HarnessPrincipal principal = registerThroughApi(users, mapper, port);
        User owner = principal.owner();
        authenticate(owner);
        long logsBefore = logs.count();
        long paintingsBefore = paintings.count();

        UserWorkflow text = workflow(workflows, owner, codec, WorkflowModality.TEXT_DESCRIPTION,
                WorkflowOperation.TEXT_TO_PAINTING);
        UserWorkflow poem = workflow(workflows, owner, codec, WorkflowModality.POEM,
                WorkflowOperation.POEM_TO_PAINTING);
        UserWorkflow image = workflow(workflows, owner, codec, WorkflowModality.IMAGE,
                WorkflowOperation.IMAGE_TO_PAINTING);
        UserWorkflow painting = workflow(workflows, owner, codec, WorkflowModality.PAINTING,
                WorkflowOperation.PAINTING_TO_POEM);
        UserWorkflow twoStep = workflow(workflows, owner, codec, WorkflowModality.TEXT_DESCRIPTION,
                WorkflowOperation.TEXT_TO_PAINTING, WorkflowOperation.PAINTING_TO_POEM);
        UserWorkflow laterFailure = workflow(workflows, owner, codec, WorkflowModality.PAINTING,
                WorkflowOperation.PAINTING_TO_POEM, WorkflowOperation.POEM_TO_PAINTING);

        CreationQueuedResponse textQueued = submitTextThroughApi(mapper, port, principal.token(), text, "山水松风");
        require(textQueued.status() == CreationStatus.QUEUED, "text admission must be queued");
        executeOne(transactions, worker);
        requireStatus(creations, textQueued, CreationStatus.SUCCEEDED, "text terminal success");
        var textCreation = creations.findByPublicId(textQueued.creationId()).orElseThrow();
        require(executionAttempts.countByCreationId(textCreation.getId()) == 1,
                "initial execution attempt persisted");
        var textStep = creationSteps.findByCreationIdOrderByStepIndexAsc(textCreation.getId()).get(0);
        require(dispatchAttempts.findByCreationStepIdOrderByIdAsc(textStep.getId()).size() == 1,
                "terminal step has one immutable dispatch attempt");
        require("RESULT_PERSISTED".equals(dispatchAttempts
                .findByCreationStepIdOrderByIdAsc(textStep.getId()).get(0).getDispatchState()),
                "terminal dispatch result persisted");

        CreationQueuedResponse poemQueued = submitText(submission, poem, WorkflowModality.POEM, "孤帆远影碧空尽");
        executeOne(transactions, worker);
        require(status(creations, poemQueued) == CreationStatus.SUCCEEDED, "poem terminal success");

        MediaAsset inputAsset = media.storeGeneratedAsset(new GeneratedAssetRequest(
                owner, new ByteArrayInputStream(PNG), "harness-input.png", "image/png",
                MediaAssetValues.AssetType.IMAGE, MediaAssetValues.SemanticType.IMAGE, null));
        CreationQueuedResponse imageQueued = submitAsset(submission, image, inputAsset.getPublicId());
        executeOne(transactions, worker);
        require(status(creations, imageQueued) == CreationStatus.SUCCEEDED, "image terminal success");

        Path catalog = root.resolve("catalog");
        Files.createDirectories(catalog);
        Files.write(catalog.resolve("official.png"), PNG);
        MediaAsset catalogAsset = media.registerCatalogReference("official.png");
        Painting official = paintings.saveAndFlush(Painting.builder()
                .sourceKey("round9b2-harness-official")
                .imageStorageName("official.png")
                .title("Harness Official Painting")
                .imageAsset(catalogAsset)
                .imageAvailable(true)
                .visibleInGallery(true)
                .status("ACTIVE")
                .build());
        CreationQueuedResponse paintingQueued = submitPainting(submission, painting, official.getPublicId());
        executeOne(transactions, worker);
        require(status(creations, paintingQueued) == CreationStatus.SUCCEEDED, "painting poem success");

        CreationQueuedResponse twoStepQueued = submitText(submission, twoStep, WorkflowModality.TEXT_DESCRIPTION, "江上秋色");
        executeOne(transactions, worker);
        require(status(creations, twoStepQueued) == CreationStatus.SUCCEEDED, "two-step success");

        mock.failNextCall();
        CreationQueuedResponse firstFailure = submitText(submission, text, WorkflowModality.TEXT_DESCRIPTION, "失败首步");
        executeOne(transactions, worker);
        require(status(creations, firstFailure) == CreationStatus.FAILED, "first-step failure");
        int callsAfterFirstFailure = mock.seedreamCalls() + mock.qwenCalls();
        require(status(creations, firstFailure) == CreationStatus.FAILED, "no automatic retry state");
        require(callsAfterFirstFailure == mock.seedreamCalls() + mock.qwenCalls(), "no automatic retry call");
        requireRetryCode(retries, firstFailure.creationId(), "round9cb1-ambiguous-retry-key", 0,
                ApiErrorCode.CREATION_RETRY_DISPATCH_AMBIGUOUS);

        mock.failNextOperation(WorkflowOperation.POEM_TO_PAINTING);
        CreationQueuedResponse partial = submitPainting(submission, laterFailure, official.getPublicId());
        executeOne(transactions, worker);
        require(status(creations, partial) == CreationStatus.PARTIAL_SUCCESS, "later failure is partial success");

        mock.invalidateNextOutput();
        CreationQueuedResponse invalid = submitText(submission, text, WorkflowModality.TEXT_DESCRIPTION, "无效结果");
        executeOne(transactions, worker);
        require(status(creations, invalid) == CreationStatus.FAILED, "invalid output fails once");

        CreationQueuedResponse safelyFailed = submitText(
                submission, text, WorkflowModality.TEXT_DESCRIPTION, "可安全重试");
        executionProperties.setEnabled(false);
        try {
            executeOne(transactions, worker);
        } finally {
            executionProperties.setEnabled(true);
        }
        require(status(creations, safelyFailed) == CreationStatus.FAILED, "pre-dispatch failure");
        int callsBeforeSafeRetry = mock.seedreamCalls() + mock.qwenCalls();
        var safeRetry = retries.retry(safelyFailed.creationId(), "round9cb1-safe-failure-retry-key", retryRequest(0));
        require(!safeRetry.idempotentReplay(), "safe failed retry accepted once");
        require(safeRetry.response().executionAttemptNumber() == 2, "safe retry creates attempt two");
        require(callsBeforeSafeRetry == mock.seedreamCalls() + mock.qwenCalls(),
                "retry admission must not call a provider");
        var safeRetryDuplicate = retries.retry(
                safelyFailed.creationId(), "round9cb1-safe-failure-retry-key", retryRequest(0));
        require(safeRetryDuplicate.idempotentReplay(), "safe retry duplicate is idempotent");
        require(callsBeforeSafeRetry == mock.seedreamCalls() + mock.qwenCalls(),
                "idempotent retry replay must not call a provider");
        requireRetryCode(retries, safelyFailed.creationId(), "round9cb1-version-conflict-key", 0,
                ApiErrorCode.CREATION_RETRY_VERSION_CONFLICT);
        executeOne(transactions, worker);
        require(status(creations, safelyFailed) == CreationStatus.SUCCEEDED, "safe failed retry succeeds");

        CreationQueuedResponse safelyPartial = submitText(
                submission, twoStep, WorkflowModality.TEXT_DESCRIPTION, "可安全部分重试");
        createSafePartial(transactions, results, mock, safelyPartial);
        require(status(creations, safelyPartial) == CreationStatus.PARTIAL_SUCCESS, "safe partial state");
        int seedreamBeforePartialRetry = mock.seedreamCalls();
        var partialRetry = retries.retry(safelyPartial.creationId(), "round9cb1-safe-partial-retry-key", retryRequest(0));
        require(!partialRetry.idempotentReplay(), "safe partial retry accepted");
        executeOne(transactions, worker);
        require(status(creations, safelyPartial) == CreationStatus.SUCCEEDED, "safe partial retry succeeds");
        require(mock.seedreamCalls() == seedreamBeforePartialRetry,
                "safe partial retry reuses prior successful painting step");
        var partialCreation = creations.findByPublicId(safelyPartial.creationId()).orElseThrow();
        require(executionAttempts.countByCreationId(partialCreation.getId()) == 2,
                "partial retry retains both execution attempts");

        CreationQueuedResponse disabledRetry = submitText(
                submission, text, WorkflowModality.TEXT_DESCRIPTION, "重试功能禁用");
        executionProperties.setEnabled(false);
        try {
            executeOne(transactions, worker);
            require(status(creations, disabledRetry) == CreationStatus.FAILED,
                    "disabled feature creates a pre-dispatch retry candidate");
            requireRetryCode(retries, disabledRetry.creationId(), "round9cb1-disabled-retry-key", 0,
                    ApiErrorCode.CREATIONS_DISABLED);
        } finally {
            executionProperties.setEnabled(true);
        }

        CreationQueuedResponse duplicate = submitText(submission, text, WorkflowModality.TEXT_DESCRIPTION, "重复调度");
        var firstClaim = transactions.claimOldestQueued();
        require(firstClaim.isPresent(), "first duplicate claim");
        require(transactions.claimOldestQueued().isEmpty(), "duplicate claim rejected");
        worker.execute(firstClaim.get());
        require(status(creations, duplicate) == CreationStatus.SUCCEEDED, "duplicate claim still executes once");

        executionProperties.setEnabled(false);
        try {
            boolean disabledRejected;
            try {
                submitText(submission, text, WorkflowModality.TEXT_DESCRIPTION, "disabled");
                disabledRejected = false;
            } catch (RuntimeException expected) {
                disabledRejected = true;
            }
            require(disabledRejected, "feature disabled admission");
        } finally {
            executionProperties.setEnabled(true);
        }
        assertUnsupportedSubmission(submission, workflows, owner, codec, WorkflowOperation.PAINTING_TO_MUSIC);
        assertUnsupportedSubmission(submission, workflows, owner, codec, WorkflowOperation.PAINTING_TO_VIDEO);

        runRecoveryScenarios(
                submission, transactions, worker, dispatcher, recovery, recoveryGate, jdbc, creations,
                executionAttempts, creationSteps, dispatchAttempts, mock, text, twoStep, textQueued,
                paintingQueued, firstFailure);

        require(logs.count() == logsBefore, "no generation_logs write");
        require(paintings.count() == paintingsBefore + 1, "no official generated painting");
        require(assets.count() > 1, "generated media persisted");
        require(empty(root.resolve("provider-staging")), "provider staging empty");
        require(allManaged(root.resolve("managed")), "generated assets remain under managed root");
    }

    /**
     * Deterministic recovery coverage. The only provider calls below happen
     * after a safe item has been requeued and the dispatcher is explicitly resumed.
     */
    private static void runRecoveryScenarios(
            CreationSubmissionService submission,
            CreationExecutionTransactionService transactions,
            com.auralink.creation.CreationWorker worker,
            CreationQueueDispatcher dispatcher,
            CreationRecoveryCoordinator recovery,
            CreationRecoveryGate recoveryGate,
            JdbcTemplate jdbc,
            CreationRepository creations,
            CreationExecutionAttemptRepository executionAttempts,
            CreationStepRepository steps,
            CreationStepDispatchAttemptRepository dispatchAttempts,
            Round9B2MockCreationProviderAdapter mock,
            UserWorkflow text,
            UserWorkflow twoStep,
            CreationQueuedResponse durableSuccess,
            CreationQueuedResponse durablePoem,
            CreationQueuedResponse historicalFailure) {
        int before = providerCalls(mock);

        CreationQueuedResponse notSent = submitText(
                submission, text, WorkflowModality.TEXT_DESCRIPTION, "恢复未发送边界");
        var notSentClaim = transactions.claimOldestQueued().orElseThrow();
        var notSentCreation = creations.findById(notSentClaim.id()).orElseThrow();
        int notSentRetryVersion = notSentCreation.getRetryVersion();
        var notSentStep = transactions.loadSteps(notSentClaim.id()).get(0);
        require(transactions.startPendingStep(notSentClaim.id(), notSentClaim.claimToken(), notSentStep.stepId()),
                "NOT_SENT recovery fixture starts its step");
        expire(jdbc, notSentClaim.id());
        recovery.recoverOneBatch();
        requireStatus(creations, notSent, CreationStatus.QUEUED, "expired NOT_SENT requeues");
        var queuedNotSent = creations.findById(notSentClaim.id()).orElseThrow();
        require(queuedNotSent.getClaimToken() == null && queuedNotSent.getLeaseExpiresAt() == null,
                "NOT_SENT recovery clears the claim and lease");
        require(queuedNotSent.getRetryVersion() == notSentRetryVersion,
                "NOT_SENT recovery does not change retry version");
        require(executionAttempts.findByCreationIdAndFinishedAtIsNullOrderByIdAsc(notSentClaim.id()).size() == 1,
                "NOT_SENT recovery keeps one active execution attempt");
        var recoveredNotSent = steps.findByCreationIdOrderByStepIndexAsc(notSentClaim.id()).get(0);
        require("PENDING".equals(recoveredNotSent.getStatus()), "NOT_SENT projection reset to pending");
        require(recoveredNotSent.getAttemptCount() == 1, "recovery does not decrement step attempt count");
        var preservedNotSent = dispatchAttempts.findByCreationStepIdOrderByIdAsc(recoveredNotSent.getId());
        require(preservedNotSent.size() == 1
                        && "NOT_SENT".equals(preservedNotSent.get(0).getDispatchState())
                        && "RECOVERY_REQUEUED_NOT_SENT".equals(preservedNotSent.get(0).getResolutionCode()),
                "NOT_SENT dispatch evidence retained");
        require(providerCalls(mock) == before, "NOT_SENT recovery has zero provider calls");
        require(recoveryGate.isOpen(), "recovery gate remains open after a periodic recovery batch");
        dispatchAndAwait(dispatcher, creations, notSent, CreationStatus.SUCCEEDED,
                "recovered NOT_SENT executes after resume");
        requireStatus(creations, notSent, CreationStatus.SUCCEEDED, "recovered NOT_SENT executes after resume");
        require(providerCalls(mock) == before + 1, "recovered NOT_SENT invokes exactly one provider");
        var resumedNotSent = steps.findById(recoveredNotSent.getId()).orElseThrow();
        require(resumedNotSent.getAttemptCount() == 2,
                "resumed NOT_SENT increments only its step attempt count");
        require(dispatchAttempts.findByCreationStepIdOrderByIdAsc(recoveredNotSent.getId()).size() == 1,
                "recovered NOT_SENT reuses its dispatch attempt row");

        int beforeAmbiguous = providerCalls(mock);
        CreationQueuedResponse sendStarted = submitText(
                submission, text, WorkflowModality.TEXT_DESCRIPTION, "恢复已发送边界");
        var sendStartedClaim = transactions.claimOldestQueued().orElseThrow();
        var sendStartedStep = transactions.loadSteps(sendStartedClaim.id()).get(0);
        require(transactions.startPendingStep(sendStartedClaim.id(), sendStartedClaim.claimToken(), sendStartedStep.stepId()),
                "SEND_STARTED recovery fixture starts its step");
        require(transactions.markSendStarted(sendStartedClaim.id(), sendStartedClaim.claimToken(), sendStartedStep.stepId())
                .isPresent(), "SEND_STARTED ledger marker persisted");
        expire(jdbc, sendStartedClaim.id());
        recovery.recoverOneBatch();
        requireStatus(creations, sendStarted, CreationStatus.FAILED, "SEND_STARTED is terminally ambiguous");
        require("PROVIDER_DISPATCH_AMBIGUOUS".equals(
                creations.findByPublicId(sendStarted.creationId()).orElseThrow().getErrorCode()),
                "SEND_STARTED ambiguity code retained");
        require(providerCalls(mock) == beforeAmbiguous, "SEND_STARTED recovery never replays provider");

        CreationQueuedResponse resultPersisted = submitText(
                submission, text, WorkflowModality.TEXT_DESCRIPTION, "恢复结果持久化不一致");
        Long resultPersistedId = creations.findByPublicId(resultPersisted.creationId()).orElseThrow().getId();
        jdbc.update("UPDATE creation_steps SET status = 'RUNNING', provider_dispatch_state = 'RESULT_PERSISTED' "
                + "WHERE creation_id = ?", resultPersistedId);
        makeExpiredRunning(jdbc, resultPersistedId, "recovery-result-persisted");
        int beforeResultInconsistency = providerCalls(mock);
        recovery.recoverOneBatch();
        requireStatus(creations, resultPersisted, CreationStatus.FAILED,
                "RUNNING RESULT_PERSISTED is quarantined");
        require("CREATION_RESULT_PERSISTENCE_INCONSISTENT".equals(
                creations.findByPublicId(resultPersisted.creationId()).orElseThrow().getErrorCode()),
                "RESULT_PERSISTED inconsistency has safe code");
        require(providerCalls(mock) == beforeResultInconsistency, "RESULT_PERSISTED recovery never replays provider");

        int beforeFinalization = providerCalls(mock);
        Long durableId = creations.findByPublicId(durableSuccess.creationId()).orElseThrow().getId();
        jdbc.update("UPDATE creation_execution_attempts SET finished_at = NULL, resolution_code = NULL WHERE creation_id = ?",
                durableId);
        makeExpiredRunning(jdbc, durableId, "recovery-durable-success");
        recovery.recoverOneBatch();
        requireStatus(creations, durableSuccess, CreationStatus.SUCCEEDED, "durable result finalizes without provider");
        require(providerCalls(mock) == beforeFinalization, "durable finalization has zero provider calls");

        int beforePoemFinalization = providerCalls(mock);
        Long durablePoemId = creations.findByPublicId(durablePoem.creationId()).orElseThrow().getId();
        jdbc.update("UPDATE creation_execution_attempts SET finished_at = NULL, resolution_code = NULL WHERE creation_id = ?",
                durablePoemId);
        makeExpiredRunning(jdbc, durablePoemId, "recovery-durable-poem");
        recovery.recoverOneBatch();
        requireStatus(creations, durablePoem, CreationStatus.SUCCEEDED, "durable poem finalizes without provider");
        require(creations.findByPublicId(durablePoem.creationId()).orElseThrow().getFinalOutputJson() != null,
                "durable poem final output restored");
        require(providerCalls(mock) == beforePoemFinalization, "durable poem finalization has zero provider calls");

        int beforeFailed = providerCalls(mock);
        Long failedId = creations.findByPublicId(historicalFailure.creationId()).orElseThrow().getId();
        jdbc.update("UPDATE creation_execution_attempts SET finished_at = NULL, resolution_code = NULL WHERE creation_id = ?",
                failedId);
        makeExpiredRunning(jdbc, failedId, "recovery-failed-step");
        recovery.recoverOneBatch();
        requireStatus(creations, historicalFailure, CreationStatus.FAILED, "failed step terminalizes without provider");
        require(providerCalls(mock) == beforeFailed, "failed-step recovery has zero provider calls");

        CreationQueuedResponse successfulPrefix = submitText(
                submission, twoStep, WorkflowModality.TEXT_DESCRIPTION, "恢复成功前缀");
        executeOne(transactions, worker);
        Long prefixId = creations.findByPublicId(successfulPrefix.creationId()).orElseThrow().getId();
        CreationExecutionAttempt nextAttempt = executionAttempts.save(CreationExecutionAttempt.builder()
                .creation(creations.findById(prefixId).orElseThrow())
                .attemptNumber(2)
                .admittedAt(LocalDateTime.now(Clock.systemUTC()))
                .build());
        require(nextAttempt.getId() != null, "recovery prefix fixture has one active attempt");
        jdbc.update("UPDATE creation_steps SET status = 'PENDING', started_at = NULL, finished_at = NULL, "
                + "provider_dispatch_state = 'NOT_SENT', provider_request_key = NULL, output_asset_id = NULL, "
                + "output_json = NULL, error_code = NULL, error_message = NULL "
                + "WHERE creation_id = ? AND step_index = 1", prefixId);
        makeExpiredRunning(jdbc, prefixId, "recovery-success-prefix");
        int beforePrefixResume = providerCalls(mock);
        recovery.recoverOneBatch();
        requireStatus(creations, successfulPrefix, CreationStatus.QUEUED, "successful prefix queues remaining work");
        require(providerCalls(mock) == beforePrefixResume, "prefix recovery does not replay provider");
        executeOne(transactions, worker);
        requireStatus(creations, successfulPrefix, CreationStatus.SUCCEEDED, "prefix remaining work resumes");
        require(providerCalls(mock) == beforePrefixResume + 1, "only remaining prefix provider runs");

        CreationQueuedResponse multipleRunning = submitText(
                submission, twoStep, WorkflowModality.TEXT_DESCRIPTION, "恢复多运行步骤");
        Long multipleId = creations.findByPublicId(multipleRunning.creationId()).orElseThrow().getId();
        CreationExecutionAttempt inconsistentAttempt = executionAttempts.findByCreationIdAndFinishedAtIsNull(multipleId)
                .orElseThrow();
        require(inconsistentAttempt.getFinishedAt() == null, "new inconsistent fixture attempt active");
        jdbc.update("UPDATE creation_steps SET status = 'RUNNING', provider_dispatch_state = 'NOT_SENT', "
                + "provider_request_key = NULL WHERE creation_id = ?", multipleId);
        makeExpiredRunning(jdbc, multipleId, "recovery-multiple-running");
        int beforeQuarantine = providerCalls(mock);
        recovery.recoverOneBatch();
        requireStatus(creations, multipleRunning, CreationStatus.FAILED, "multiple RUNNING steps are quarantined");
        require("CREATION_STATE_INCONSISTENT".equals(
                creations.findByPublicId(multipleRunning.creationId()).orElseThrow().getErrorCode()),
                "multiple RUNNING steps expose only safe inconsistency");
        require(providerCalls(mock) == beforeQuarantine, "inconsistency quarantine has zero provider calls");

        CreationQueuedResponse allPending = submitText(
                submission, text, WorkflowModality.TEXT_DESCRIPTION, "恢复前未开始");
        var pendingClaim = transactions.claimOldestQueued().orElseThrow();
        expire(jdbc, pendingClaim.id());
        int beforeAllPendingRecovery = providerCalls(mock);
        recovery.recoverOneBatch();
        requireStatus(creations, allPending, CreationStatus.QUEUED, "expired all-pending work requeues");
        require(providerCalls(mock) == beforeAllPendingRecovery,
                "recovery never calls provider for all-pending work");
    }

    private static int providerCalls(Round9B2MockCreationProviderAdapter mock) {
        return mock.seedreamCalls() + mock.qwenCalls();
    }

    private static void dispatchAndAwait(
            CreationQueueDispatcher dispatcher,
            CreationRepository creations,
            CreationQueuedResponse queued,
            CreationStatus expected,
            String check) {
        dispatcher.dispatchOne();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (status(creations, queued) == expected) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Harness dispatcher wait interrupted");
            }
        }
        requireStatus(creations, queued, expected, check);
    }

    private static void expire(JdbcTemplate jdbc, Long creationId) {
        jdbc.update("UPDATE creations SET lease_expires_at = ? WHERE id = ?",
                Timestamp.valueOf(EXPIRED_LEASE), creationId);
    }

    private static void makeExpiredRunning(JdbcTemplate jdbc, Long creationId, String token) {
        jdbc.update("UPDATE creations SET status = 'RUNNING', claim_token = ?, "
                + "lease_expires_at = ?, finished_at = NULL WHERE id = ?",
                token, Timestamp.valueOf(EXPIRED_LEASE), creationId);
    }

    static ConfigurableApplicationContext startContext(Path root, String[] supplied) {
        return new SpringApplicationBuilder(Application.class)
                .web(WebApplicationType.SERVLET)
                .run(startupArguments(root, supplied));
    }

    static List<String> startupProperties(Path root) {
        return List.of(
                "spring.config.import=optional:file:" + root.resolve("no-env.properties"),
                "server.port=0",
                "spring.datasource.url=jdbc:sqlite:" + root.resolve("harness.db"),
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration",
                "auralink.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "auralink.workflows.enabled=true",
                "auralink.creations.enabled=true",
                "auralink.creations.dispatch-delay=3600000",
                "auralink.creation-providers.mock-adapters-enabled=true",
                "auralink.creation-providers.enabled=false",
                "auralink.creation-providers.staging-dir=" + root.resolve("provider-staging"),
                "auralink.media-assets.managed-dir=" + root.resolve("managed"),
                "auralink.paintings.picture-dir=" + root.resolve("catalog"),
                "auralink.paintings.metadata-csv-path=" + root.resolve("unused.csv"),
                "auralink.paintings.import-enabled=false",
                "auralink.storage.upload-dir=" + root.resolve("uploads"),
                "auralink.storage.audio-dir=" + root.resolve("audio"),
                "auralink.storage.legacy-frontend-audio-dir=" + root.resolve("legacy-audio"));
    }

    static String[] startupArguments(Path root, String[] supplied) {
        List<String> arguments = new ArrayList<>();
        for (String property : startupProperties(root)) {
            arguments.add("--" + property);
        }
        java.util.Collections.addAll(arguments, supplied);
        return arguments.toArray(String[]::new);
    }

    private static UserWorkflow workflow(
            UserWorkflowRepository workflows,
            User owner,
            WorkflowGraphCodec codec,
            WorkflowModality source,
            WorkflowOperation... operations) {
        List<CanonicalWorkflowNode> nodes = new ArrayList<>();
        List<CanonicalWorkflowEdge> edges = new ArrayList<>();
        nodes.add(CanonicalWorkflowNode.source("source", source));
        String previous = "source";
        WorkflowModality input = source;
        for (int index = 0; index < operations.length; index++) {
            WorkflowOperation operation = operations[index];
            WorkflowModality output = output(operation);
            String node = "step-" + index;
            nodes.add(CanonicalWorkflowNode.transform(node, operation, provider(operation), input, output));
            edges.add(new CanonicalWorkflowEdge(previous, node));
            previous = node;
            input = output;
        }
        return workflows.saveAndFlush(UserWorkflow.builder()
                .user(owner)
                .name("Harness " + source + " " + operations.length + " " + java.util.UUID.randomUUID())
                .graphJson(codec.encode(new CanonicalWorkflowGraph(1, nodes, edges)))
                .schemaVersion(1)
                .status("ACTIVE")
                .build());
    }

    private static CreationQueuedResponse submitText(
            CreationSubmissionService service,
            UserWorkflow workflow,
            WorkflowModality modality,
            String text) {
        CreationSourceRequest source = new CreationSourceRequest();
        source.setModality(modality.name());
        source.setText(text);
        return submit(service, workflow.getPublicId(), source);
    }

    private static HarnessPrincipal registerThroughApi(
            UserRepository users,
            ObjectMapper mapper,
            int port) throws Exception {
        String body = mapper.writeValueAsString(java.util.Map.of(
                "username", "round9b2-harness",
                "password", "round9b2-harness-password",
                "fullName", "ROUND 9B.2 Harness",
                "email", "round9b2-harness@example.invalid"));
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/register"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        require(response.statusCode() == 200, "loopback registration status");
        String token = mapper.readTree(response.body()).path("data").path("token").asText();
        require(!token.isBlank(), "loopback registration token");
        User owner = users.findByUsername("round9b2-harness").orElseThrow();
        return new HarnessPrincipal(owner, token);
    }

    private static CreationQueuedResponse submitTextThroughApi(
            ObjectMapper mapper,
            int port,
            String token,
            UserWorkflow workflow,
            String text) throws Exception {
        String body = mapper.writeValueAsString(java.util.Map.of(
                "workflowId", workflow.getPublicId(),
                "source", java.util.Map.of("modality", "TEXT_DESCRIPTION", "text", text)));
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/creations"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        require(response.statusCode() == 202, "POST /api/v1/creations returns 202");
        var queued = mapper.readTree(response.body());
        require("QUEUED".equals(queued.path("status").asText()), "POST returns queued status");
        return new CreationQueuedResponse(
                queued.path("creationId").asText(), CreationStatus.QUEUED);
    }

    private static CreationQueuedResponse submitAsset(
            CreationSubmissionService service, UserWorkflow workflow, String assetId) {
        CreationSourceRequest source = new CreationSourceRequest();
        source.setModality(WorkflowModality.IMAGE.name());
        source.setAssetId(assetId);
        return submit(service, workflow.getPublicId(), source);
    }

    private static CreationQueuedResponse submitPainting(
            CreationSubmissionService service, UserWorkflow workflow, String paintingId) {
        CreationSourceRequest source = new CreationSourceRequest();
        source.setModality(WorkflowModality.PAINTING.name());
        source.setPaintingId(paintingId);
        return submit(service, workflow.getPublicId(), source);
    }

    private static CreationQueuedResponse submit(
            CreationSubmissionService service, String workflowId, CreationSourceRequest source) {
        CreationSubmissionRequest request = new CreationSubmissionRequest();
        request.setWorkflowId(workflowId);
        request.setSource(source);
        return service.submit(request);
    }

    private static com.auralink.api.v1.creation.CreationRetryRequest retryRequest(int expectedVersion) {
        var request = new com.auralink.api.v1.creation.CreationRetryRequest();
        request.setExpectedRetryVersion(expectedVersion);
        return request;
    }

    private static void requireRetryCode(
            CreationRetryService retries,
            String creationId,
            String idempotencyKey,
            int expectedVersion,
            ApiErrorCode code) {
        try {
            retries.retry(creationId, idempotencyKey, retryRequest(expectedVersion));
        } catch (ApiV1Exception expected) {
            require(expected.getCode() == code, "retry rejection code=" + expected.getCode());
            return;
        }
        throw new IllegalStateException("ROUND 9B.2 packaged harness failed: retry must be rejected " + code);
    }

    private static void createSafePartial(
            CreationExecutionTransactionService transactions,
            CreationResultPersistenceService results,
            Round9B2MockCreationProviderAdapter mock,
            CreationQueuedResponse queued) {
        CreationExecutionTransactionService.ClaimedCreation claim = transactions.claimOldestQueued().orElseThrow();
        CreationExecutionTransactionService.ClaimedCreationData creation = transactions.loadClaimed(
                claim.id(), claim.claimToken()).orElseThrow();
        var steps = transactions.loadSteps(claim.id());
        require(steps.size() == 2, "safe partial fixture has two steps");
        var first = steps.get(0);
        var second = steps.get(1);
        require(transactions.startPendingStep(claim.id(), claim.claimToken(), first.stepId()),
                "safe partial first step starts");
        String requestKey = transactions.markSendStarted(claim.id(), claim.claimToken(), first.stepId()).orElseThrow();
        ProviderExecutionResult result = mock.execute(new ProviderExecutionRequest(
                requestKey, WorkflowOperation.TEXT_TO_PAINTING, "seedream-5",
                new ProviderTextInput("可安全部分重试", WorkflowModality.TEXT_DESCRIPTION)));
        ProviderBinaryOutput output = (ProviderBinaryOutput) result.output();
        try {
            results.persistPainting(creation, first, output, false);
        } finally {
            output.artifact().close();
        }
        require(transactions.startPendingStep(claim.id(), claim.claimToken(), second.stepId()),
                "safe partial boundary starts without dispatch");
        require(transactions.failStep(claim.id(), claim.claimToken(), second.stepId(), true,
                CreationExecutionFailure.inputInvalid()), "safe partial boundary fails without dispatch");
    }

    private static void executeOne(
            CreationExecutionTransactionService transactions,
            com.auralink.creation.CreationWorker worker) {
        worker.execute(transactions.claimOldestQueued().orElseThrow(
                () -> new IllegalStateException("Harness expected a queued Creation")));
    }

    private static CreationStatus status(CreationRepository creations, CreationQueuedResponse response) {
        return CreationStatus.valueOf(creations.findByPublicId(response.creationId()).orElseThrow().getStatus());
    }

    private static void requireStatus(
            CreationRepository creations,
            CreationQueuedResponse response,
            CreationStatus expected,
            String check) {
        var creation = creations.findByPublicId(response.creationId()).orElseThrow();
        require(expected.name().equals(creation.getStatus()), check + " status=" + creation.getStatus()
                + " error=" + creation.getErrorCode());
    }

    private static void assertUnsupportedSubmission(
            CreationSubmissionService service,
            UserWorkflowRepository workflows,
            User owner,
            WorkflowGraphCodec codec,
            WorkflowOperation operation) {
        UserWorkflow workflow = workflow(workflows, owner, codec, WorkflowModality.PAINTING, operation);
        boolean rejected;
        try {
            submitPainting(service, workflow, java.util.UUID.randomUUID().toString());
            rejected = false;
        } catch (RuntimeException expected) {
            rejected = true;
        }
        require(rejected, operation + " rejected");
    }

    private static void authenticate(User user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                user.getUsername(), "not-used", user.getAuthorities()));
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

    private static WorkflowModality output(WorkflowOperation operation) {
        return switch (operation) {
            case TEXT_TO_PAINTING, IMAGE_TO_PAINTING, POEM_TO_PAINTING -> WorkflowModality.PAINTING;
            case PAINTING_TO_POEM -> WorkflowModality.POEM;
            case PAINTING_TO_MUSIC -> WorkflowModality.AUDIO;
            case PAINTING_TO_VIDEO -> WorkflowModality.VIDEO;
        };
    }

    private static boolean empty(Path path) throws Exception {
        if (!Files.exists(path)) {
            return true;
        }
        try (var entries = Files.list(path)) {
            return entries.findAny().isEmpty();
        }
    }

    private static boolean allManaged(Path managedRoot) throws Exception {
        if (!Files.exists(managedRoot)) {
            return false;
        }
        try (var paths = Files.walk(managedRoot)) {
            return paths.filter(Files::isRegularFile).allMatch(path -> path.normalize().startsWith(managedRoot));
        }
    }

    private static void deleteOwnedTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("ROUND 9B.2 packaged harness failed: " + message);
        }
    }

    private record HarnessPrincipal(User owner, String token) {
    }
}
