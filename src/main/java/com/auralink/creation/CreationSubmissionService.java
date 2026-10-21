package com.auralink.creation;

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
import com.auralink.repository.CreationExecutionAttemptRepository;
import com.auralink.repository.CreationRepository;
import com.auralink.repository.CreationStepRepository;
import com.auralink.service.CurrentUserService;
import com.auralink.provider.vmm.VmmManagedAdmission;
import com.auralink.workflow.service.WorkflowExecutionPreparer;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreationSubmissionService {
   private final CreationFeatureGuard featureGuard;
   private final CreationExecutionProperties properties;
   private final CurrentUserService currentUserService;
   private final WorkflowExecutionPreparer workflowPreparer;
   private final CreationExecutionCapabilityService capabilityService;
   private final CreationSourceResolver sourceResolver;
   private final CreationStateMachine stateMachine;
   private final ObjectProvider<CreationCatalogInputSnapshotWriter> catalogInputSnapshots;
   private final CreationRepository creations;
   private final CreationExecutionAttemptRepository executionAttempts;
   private final CreationStepRepository steps;
   private final CreationResponseMapper responseMapper;
   private final Clock clock;
   private final VmmManagedAdmission managedVmm;

   @Transactional
   public CreationQueuedResponse submit(CreationSubmissionRequest request) {
      this.featureGuard.requireEnabled();
      if (request != null && request.unknownFields().isEmpty()) {
         User owner = this.currentUserService.requireCurrentUser();
         WorkflowExecutionPreparer.PreparedWorkflow workflow = this.workflowPreparer.prepare(request.getWorkflowId(), owner);
         CreationSourceResolver.ResolvedSource source = this.sourceResolver.resolve(request.getSource(), workflow.sourceModality(), owner);
         this.capabilityService.requireExecutionAvailable(workflow, source);
         this.managedVmm.require(owner.getId(), workflow, source);
         if (this.creations.countByStatusIn(List.of(CreationStatus.QUEUED.name(), CreationStatus.RUNNING.name())) >= this.properties.getQueueCapacity()) {
            throw new ApiV1Exception(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.CREATION_QUEUE_FULL, "创作队列当前已满");
         }

         this.stateMachine.requireInitial(CreationStatus.QUEUED);
         LocalDateTime now = LocalDateTime.now(this.clock);
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
         this.creations.saveAndFlush(creation);
         this.catalogInputSnapshots.ifAvailable(writer -> writer.capture(creation));
         this.executionAttempts.save(CreationExecutionAttempt.builder().creation(creation).attemptNumber(1).admittedAt(now).build());
         List<CreationStep> persistedSteps = IntStream.range(0, workflow.transforms().size())
            .mapToObj(index -> this.step(creation, workflow.transforms().get(index), index))
            .toList();
         this.steps.saveAll(persistedSteps);
         return new CreationQueuedResponse(creation.getPublicId(), CreationStatus.QUEUED);
      } else {
         throw sourceInvalid();
      }
   }

   @Transactional(readOnly = true)
   public CreationDetailResponse get(String creationId) {
      User owner = this.currentUserService.requireCurrentUser();
      Creation creation = this.findOwned(creationId, owner);
      return this.responseMapper.detail(creation, this.steps.findByCreationIdOrderByStepIndexAsc(creation.getId()));
   }

   @Transactional(readOnly = true)
   public CreationPageResponse list(int page, Integer requestedSize) {
      if (page < 0) {
         throw new ApiV1Exception(HttpStatus.BAD_REQUEST, ApiErrorCode.BAD_REQUEST, "页码必须大于或等于 0");
      } else {
         int size = requestedSize == null ? this.properties.getDefaultPageSize() : requestedSize;
         if (size >= 1 && size <= this.properties.getMaxPageSize()) {
            User owner = this.currentUserService.requireCurrentUser();
            Page<Creation> result = this.creations
               .findAllByUser_Id(
                  owner.getId(),
                  PageRequest.of(page, size, Sort.by(Direction.DESC, new String[]{"createdAt"}).and(Sort.by(Direction.DESC, new String[]{"publicId"})))
               );
            List<CreationSummaryResponse> items = result.getContent().stream().map(this.responseMapper::summary).toList();
            return CreationPageResponse.from(result, items);
         } else {
            throw new ApiV1Exception(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PAGE_SIZE, "每页数量超出允许范围");
         }
      }
   }

   private CreationStep step(Creation creation, WorkflowExecutionPreparer.PreparedTransform transform, int index) {
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

      return this.creations.findByPublicIdAndUserId(canonicalId, owner.getId()).orElseThrow(CreationSubmissionService::notFound);
   }

   private static ApiV1Exception sourceInvalid() {
      return new ApiV1Exception(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.CREATION_SOURCE_INVALID, "创作请求无效");
   }

   private static ApiV1Exception notFound() {
      return new ApiV1Exception(HttpStatus.NOT_FOUND, ApiErrorCode.CREATION_NOT_FOUND, "创作不存在");
   }

   public CreationSubmissionService(
      final CreationFeatureGuard featureGuard,
      final CreationExecutionProperties properties,
      final CurrentUserService currentUserService,
      final WorkflowExecutionPreparer workflowPreparer,
      final CreationExecutionCapabilityService capabilityService,
      final CreationSourceResolver sourceResolver,
      final CreationStateMachine stateMachine,
      final ObjectProvider<CreationCatalogInputSnapshotWriter> catalogInputSnapshots,
      final CreationRepository creations,
      final CreationExecutionAttemptRepository executionAttempts,
      final CreationStepRepository steps,
      final CreationResponseMapper responseMapper,
      final Clock clock,
      final VmmManagedAdmission managedVmm
   ) {
      this.featureGuard = featureGuard;
      this.properties = properties;
      this.currentUserService = currentUserService;
      this.workflowPreparer = workflowPreparer;
      this.capabilityService = capabilityService;
      this.sourceResolver = sourceResolver;
      this.stateMachine = stateMachine;
      this.catalogInputSnapshots = catalogInputSnapshots;
      this.creations = creations;
      this.executionAttempts = executionAttempts;
      this.steps = steps;
      this.responseMapper = responseMapper;
      this.clock = clock;
      this.managedVmm = managedVmm;
   }
}
