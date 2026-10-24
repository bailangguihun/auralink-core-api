package com.auralink.creation;

import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.config.properties.CreationExecutionProperties;
import com.auralink.creation.provider.ProviderAdapterRegistry;
import com.auralink.creation.provider.ProviderReadiness;
import com.auralink.creation.provider.ProviderReadinessState;
import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowOperation;
import com.auralink.workflow.capability.WorkflowCapabilityRegistry;
import com.auralink.workflow.capability.WorkflowOperationCapability;
import com.auralink.workflow.service.WorkflowExecutionPreparer;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class CreationExecutionCapabilityService {
   public static final String MUSIC_DIRECT_SOURCE_ONLY_REASON = "PAINTING_TO_MUSIC_REQUIRES_SINGLE_DIRECT_PAINTING_SOURCE";
   private final WorkflowCapabilityRegistry workflowCapabilities;
   private final ProviderAdapterRegistry adapters;
   private final CreationExecutionProperties properties;
   private final CreationRecoveryGate recoveryGate;

   public CreationExecutionCapabilityService(WorkflowCapabilityRegistry workflowCapabilities, ProviderAdapterRegistry adapters) {
      this(workflowCapabilities, adapters, new CreationExecutionProperties(), new CreationRecoveryGate());
   }

   public void requireExecutionAvailable(WorkflowExecutionPreparer.PreparedWorkflow workflow) {
      this.requireExecutionTransformCount(workflow.transforms().size());
      this.requireMusicWorkflowScope(
         workflow.transforms().stream().map(WorkflowExecutionPreparer.PreparedTransform::operation).toList(), workflow.sourceModality(), false
      );

      for (WorkflowExecutionPreparer.PreparedTransform transform : workflow.transforms()) {
         this.requireExecutionAvailable(transform.operation(), transform.providerCode());
      }
   }

   public void requireExecutionAvailable(WorkflowExecutionPreparer.PreparedWorkflow workflow, CreationSourceResolver.ResolvedSource source) {
      if (source == null) {
         throw unavailable("PAINTING_TO_MUSIC_REQUIRES_SINGLE_DIRECT_PAINTING_SOURCE");
      }

      this.requireExecutionTransformCount(workflow.transforms().size());
      this.requireMusicWorkflowScope(
         workflow.transforms().stream().map(WorkflowExecutionPreparer.PreparedTransform::operation).toList(),
         workflow.sourceModality(),
         source.sourcePainting() != null && source.sourceAsset() == null
            || source.sourcePainting() == null && source.sourceAsset() != null
               && "USER_UPLOAD".equals(source.sourceAsset().getSourceType())
               && "PAINTING".equals(source.sourceAsset().getSemanticType())
      );

      for (WorkflowExecutionPreparer.PreparedTransform transform : workflow.transforms()) {
         this.requireExecutionAvailable(transform.operation(), transform.providerCode());
      }
   }

   public void requireExecutionAvailable(List<WorkflowOperation> operations, String sourceModality, Long sourcePaintingId, Long sourceAssetId) {
      this.requireExecutionTransformCount(operations == null ? 0 : operations.size());

      WorkflowModality source;
      try {
         source = WorkflowModality.valueOf(sourceModality);
      } catch (IllegalArgumentException | NullPointerException exception) {
         throw unavailable("PAINTING_TO_MUSIC_REQUIRES_SINGLE_DIRECT_PAINTING_SOURCE");
      }

      // Ownership, source type and bytes are revalidated by the materializer before SEND_STARTED.
      this.requireMusicWorkflowScope(operations, source, (sourcePaintingId != null) != (sourceAssetId != null));
   }

   public void requireExecutionTransformCount(int transformCount) {
      if (transformCount < 1 || transformCount > this.properties.getMaxExecutionTransformSteps()) {
         throw unavailable("CREATION_WORKFLOW_STEP_LIMIT_EXCEEDED");
      }
   }

   public void requireExecutionAvailable(WorkflowOperation operation, String providerCode) {
      CreationExecutionCapabilityService.ExecutionAvailability availability = this.availability(operation, providerCode);
      if (!availability.available()) {
         if (operation == WorkflowOperation.PAINTING_TO_VIDEO) {
            throw new ApiV1Exception(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.CREATION_VIDEO_RESERVED, "绘画转视频功能保留且当前不可用");
         } else if ("CREATION_RECOVERY_NOT_READY".equals(availability.reason())) {
            throw new ApiV1Exception(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.CREATION_RECOVERY_NOT_READY, "创作恢复尚未准备就绪");
         } else {
            throw unavailable(availability.reason());
         }
      }
   }

   public CreationExecutionCapabilityService.ExecutionAvailability availability(WorkflowOperation operation, String providerCode) {
      if (operation == null || providerCode == null || providerCode.isBlank()) {
         return new CreationExecutionCapabilityService.ExecutionAvailability(false, "CREATION_OPERATION_UNAVAILABLE");
      }

      if (operation == WorkflowOperation.PAINTING_TO_VIDEO) {
         return new CreationExecutionCapabilityService.ExecutionAvailability(false, "RESERVED_FOR_FUTURE_IMPLEMENTATION");
      }

      if (this.properties.isEnabled() && !this.properties.isOperationEnabled(operation)) {
         return new CreationExecutionCapabilityService.ExecutionAvailability(false, "CREATION_OPERATION_DISABLED_BY_CONFIGURATION");
      }

      if (this.properties.isEnabled() && !this.recoveryGate.isOpen()) {
         return new CreationExecutionCapabilityService.ExecutionAvailability(false, "CREATION_RECOVERY_NOT_READY");
      }

      WorkflowOperationCapability capability = this.workflowCapabilities.require(operation);
      if (!capability.definitionEnabled() || !capability.allowsProvider(providerCode)) {
         return new CreationExecutionCapabilityService.ExecutionAvailability(false, "CREATION_OPERATION_UNAVAILABLE");
      }

      if (this.adapters.find(operation, providerCode).isEmpty()) {
         return new CreationExecutionCapabilityService.ExecutionAvailability(false, "PROVIDER_ADAPTER_NOT_REGISTERED");
      }

      ProviderReadiness readiness = this.adapters.readiness(operation, providerCode);
      return readiness.state() != ProviderReadinessState.READY_FOR_CONTROLLED_EXECUTION
         ? new CreationExecutionCapabilityService.ExecutionAvailability(false, this.safeReadinessReason(readiness.state()))
         : new CreationExecutionCapabilityService.ExecutionAvailability(true, "READY_FOR_CONTROLLED_EXECUTION");
   }

   private void requireMusicWorkflowScope(List<WorkflowOperation> operations, WorkflowModality sourceModality, boolean hasDirectPainting) {
      if (operations != null && operations.contains(WorkflowOperation.PAINTING_TO_MUSIC)) {
         if (operations.size() != 1 || sourceModality != WorkflowModality.PAINTING || !hasDirectPainting) {
            throw unavailable("PAINTING_TO_MUSIC_REQUIRES_SINGLE_DIRECT_PAINTING_SOURCE");
         }
      }
   }

   private String safeReadinessReason(ProviderReadinessState state) {
      return switch (state) {
         case FEATURE_DISABLED -> "CREATION_PROVIDER_FEATURE_DISABLED";
         case CONFIGURATION_MISSING -> "CREATION_PROVIDER_CONFIGURATION_MISSING";
         case CONFIGURATION_INVALID -> "CREATION_PROVIDER_CONFIGURATION_INVALID";
         case RESERVED_DISABLED -> "RESERVED_FOR_FUTURE_IMPLEMENTATION";
         case ADAPTER_IMPLEMENTED, INTERNAL_SERVICE_NOT_VALIDATED -> "CREATION_PROVIDER_NOT_READY";
         case READY_FOR_CONTROLLED_EXECUTION -> "READY_FOR_CONTROLLED_EXECUTION";
      };
   }

   private static ApiV1Exception unavailable(String reason) {
      String message = switch (reason) {
         case "PAINTING_TO_MUSIC_REQUIRES_SINGLE_DIRECT_PAINTING_SOURCE" -> "音乐创作仅支持一幅画廊国画或本人上传国画直接生成音乐";
         case "CREATION_WORKFLOW_STEP_LIMIT_EXCEEDED" -> "当前流程的转换步骤超过本运行环境支持范围";
         default -> "创作操作当前不可执行";
      };
      return new ApiV1Exception(HttpStatus.CONFLICT, ApiErrorCode.CREATION_OPERATION_UNAVAILABLE, message);
   }

   @Autowired
   public CreationExecutionCapabilityService(
      final WorkflowCapabilityRegistry workflowCapabilities,
      final ProviderAdapterRegistry adapters,
      final CreationExecutionProperties properties,
      final CreationRecoveryGate recoveryGate
   ) {
      this.workflowCapabilities = workflowCapabilities;
      this.adapters = adapters;
      this.properties = properties;
      this.recoveryGate = recoveryGate;
   }

   public record ExecutionAvailability(boolean available, String reason) {
   }
}
