package com.auralink.creation;

import java.time.LocalDateTime;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.auralink.api.v1.creation.CreationDetailResponse;
import com.auralink.api.v1.creation.CreationPageResponse;
import com.auralink.api.v1.creation.CreationQueuedResponse;
import com.auralink.api.v1.creation.CreationSubmissionRequest;
import com.auralink.api.v1.creation.CreationSummaryResponse;
import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.config.properties.CreationExecutionProperties;
import com.auralink.entity.Creation;
import com.auralink.entity.CreationExecutionAttempt;
import com.auralink.entity.CreationStep;
import com.auralink.entity.User;
import com.auralink.repository.CreationRepository;
import com.auralink.repository.CreationExecutionAttemptRepository;
import com.auralink.repository.CreationStepRepository;
import com.auralink.service.CurrentUserService;
import com.auralink.workflow.service.WorkflowExecutionPreparer;
import com.auralink.workflow.service.WorkflowExecutionPreparer.PreparedTransform;
import com.auralink.workflow.service.WorkflowExecutionPreparer.PreparedWorkflow;

import lombok.RequiredArgsConstructor;

/**
 * Short-transaction Creation admission and owner-only read service.  ROUND
 * 9B.1 persists QUEUED/PENDING state only and has no executor dependency.
 */
@Service
@RequiredArgsConstructor
public class CreationSubmissionService {

    private final CreationFeatureGuard featureGuard;
    private final CreationExecutionProperties properties;
    private final CurrentUserService currentUserService;
    private final WorkflowExecutionPreparer workflowPreparer;
    private final CreationExecutionCapabilityService capabilityService;
    private final CreationSourceResolver sourceResolver;
    private final CreationStateMachine stateMachine;
    private final CreationRepository creations;
    private final CreationExecutionAttemptRepository executionAttempts;
    private final CreationStepRepository steps;
    private final CreationResponseMapper responseMapper;
    private final Clock clock;

    @Transactional
    public CreationQueuedResponse submit(CreationSubmissionRequest request) {
        featureGuard.requireEnabled();
        if (request == null || !request.unknownFields().isEmpty()) {
            throw sourceInvalid();
        }
        User owner = currentUserService.requireCurrentUser();
        PreparedWorkflow workflow = workflowPreparer.prepare(request.getWorkflowId(), owner);
        capabilityService.requireExecutionAvailable(workflow);
        CreationSourceResolver.ResolvedSource source = sourceResolver.resolve(
                request.getSource(), workflow.sourceModality(), owner);
        if (creations.countByStatusIn(List.of(CreationStatus.QUEUED.name(), CreationStatus.RUNNING.name()))
                >= properties.getQueueCapacity()) {
            throw new ApiV1Exception(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ApiErrorCode.CREATION_QUEUE_FULL,
                    "创作队列当前已满");
        }

        stateMachine.requireInitial(CreationStatus.QUEUED);
        LocalDateTime now = LocalDateTime.now(clock);
        Creation creation = Creation.builder()
                .user(owner)
                .workflow(workflow.workflow())
                .workflowSnapshot(workflow.snapshotJson())
                .sourceModality(source.modality().name())
                .sourceText(source.sourceText())
                .sourcePainting(source.sourcePainting())
                .sourceAsset(source.sourceAsset())
                .status(CreationStatus.QUEUED.name())
                .createdAt(now)
                .updatedAt(now)
                .build();
        creations.saveAndFlush(creation);

        executionAttempts.save(CreationExecutionAttempt.builder()
                .creation(creation)
                .attemptNumber(1)
                .admittedAt(now)
                .build());

        List<CreationStep> persistedSteps = IntStream.range(0, workflow.transforms().size())
                .mapToObj(index -> step(creation, workflow.transforms().get(index), index))
                .toList();
        steps.saveAll(persistedSteps);
        return new CreationQueuedResponse(creation.getPublicId(), CreationStatus.QUEUED);
    }

    @Transactional(readOnly = true)
    public CreationDetailResponse get(String creationId) {
        featureGuard.requireEnabled();
        User owner = currentUserService.requireCurrentUser();
        Creation creation = findOwned(creationId, owner);
        return responseMapper.detail(creation, steps.findByCreationIdOrderByStepIndexAsc(creation.getId()));
    }

    @Transactional(readOnly = true)
    public CreationPageResponse list(int page, Integer requestedSize) {
        featureGuard.requireEnabled();
        if (page < 0) {
            throw new ApiV1Exception(HttpStatus.BAD_REQUEST, ApiErrorCode.BAD_REQUEST, "页码必须大于或等于 0");
        }
        int size = requestedSize == null ? properties.getDefaultPageSize() : requestedSize;
        if (size < 1 || size > properties.getMaxPageSize()) {
            throw new ApiV1Exception(
                    HttpStatus.BAD_REQUEST,
                    ApiErrorCode.INVALID_PAGE_SIZE,
                    "每页数量超出允许范围");
        }
        User owner = currentUserService.requireCurrentUser();
        Page<Creation> result = creations.findAllByUser_Id(
                owner.getId(),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")
                        .and(Sort.by(Sort.Direction.DESC, "publicId"))));
        List<CreationSummaryResponse> items = result.getContent().stream().map(responseMapper::summary).toList();
        return CreationPageResponse.from(result, items);
    }

    private CreationStep step(Creation creation, PreparedTransform transform, int index) {
        return CreationStep.builder()
                .creation(creation)
                .stepIndex(index)
                .nodeId(transform.nodeId())
                .operationCode(transform.operation().name())
                .providerCode(transform.providerCode())
                .inputModality(transform.inputModality().name())
                .outputModality(transform.outputModality().name())
                .parametersJson(transform.parametersJson())
                .status(CreationStepStatus.PENDING.name())
                .attemptCount(0)
                .providerDispatchState(ProviderDispatchState.NOT_SENT.name())
                .build();
    }

    private Creation findOwned(String creationId, User owner) {
        String canonicalId;
        try {
            canonicalId = UUID.fromString(creationId).toString();
            if (!canonicalId.equals(creationId)) {
                throw notFound();
            }
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw notFound();
        }
        return creations.findByPublicIdAndUserId(canonicalId, owner.getId()).orElseThrow(CreationSubmissionService::notFound);
    }

    private static ApiV1Exception sourceInvalid() {
        return new ApiV1Exception(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorCode.CREATION_SOURCE_INVALID,
                "创作请求无效");
    }

    private static ApiV1Exception notFound() {
        return new ApiV1Exception(HttpStatus.NOT_FOUND, ApiErrorCode.CREATION_NOT_FOUND, "创作不存在");
    }
}
