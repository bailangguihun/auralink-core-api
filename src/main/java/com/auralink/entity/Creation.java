package com.auralink.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

@Entity
@Table(
   name = "creations",
   uniqueConstraints = @UniqueConstraint(name = "uq_creations_public_id", columnNames = "public_id"),
   indexes = {
         @Index(name = "idx_creations_user_created", columnList = "user_id, created_at"),
         @Index(name = "idx_creations_user_status", columnList = "user_id, status"),
         @Index(name = "idx_creations_workflow", columnList = "workflow_id"),
         @Index(name = "idx_creations_source_painting", columnList = "source_painting_id"),
         @Index(name = "idx_creations_source_asset", columnList = "source_asset_id"),
         @Index(name = "idx_creations_final_asset", columnList = "final_asset_id"),
         @Index(name = "idx_creations_status_created_id", columnList = "status, created_at, id"),
         @Index(name = "idx_creations_status_lease_id", columnList = "status, lease_expires_at, id"),
         @Index(name = "idx_creations_user_created_public", columnList = "user_id, created_at, public_id")
   }
)
public class Creation extends BasePublicIdEntity {
   @ManyToOne(fetch = FetchType.LAZY, optional = false)
   @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_creations_user"))
   private User user;
   @ManyToOne(fetch = FetchType.LAZY, optional = true)
   @JoinColumn(name = "workflow_id", foreignKey = @ForeignKey(name = "fk_creations_workflow"))
   private UserWorkflow workflow;
   @Column(name = "workflow_snapshot", nullable = false, columnDefinition = "TEXT")
   private String workflowSnapshot;
   @Column(name = "source_modality", nullable = false, length = 64)
   private String sourceModality;
   @Column(name = "source_text", columnDefinition = "TEXT")
   private String sourceText;
   @ManyToOne(fetch = FetchType.LAZY, optional = true)
   @JoinColumn(name = "source_painting_id", foreignKey = @ForeignKey(name = "fk_creations_source_painting"))
   private Painting sourcePainting;
   @ManyToOne(fetch = FetchType.LAZY, optional = true)
   @JoinColumn(name = "source_asset_id", foreignKey = @ForeignKey(name = "fk_creations_source_asset"))
   private MediaAsset sourceAsset;
   @Column(nullable = false, length = 64)
   private String status;
   @Column(name = "final_modality", length = 64)
   private String finalModality;
   @ManyToOne(fetch = FetchType.LAZY, optional = true)
   @JoinColumn(name = "final_asset_id", foreignKey = @ForeignKey(name = "fk_creations_final_asset"))
   private MediaAsset finalAsset;
   @Column(name = "final_output_json", columnDefinition = "TEXT")
   private String finalOutputJson;
   @Column(name = "created_at", nullable = false)
   private LocalDateTime createdAt;
   @Column(name = "started_at")
   private LocalDateTime startedAt;
   @Column(name = "finished_at")
   private LocalDateTime finishedAt;
   @Column(name = "error_code", length = 128)
   private String errorCode;
   @Column(name = "error_message", columnDefinition = "TEXT")
   private String errorMessage;
   @Column(name = "updated_at", nullable = false)
   private LocalDateTime updatedAt;
   @Column(name = "history_deleted_at")
   private LocalDateTime historyDeletedAt;

   public LocalDateTime getHistoryDeletedAt() { return this.historyDeletedAt; }

   @Column(name = "claim_token", length = 64)
   private String claimToken;
   @Column(name = "lease_expires_at")
   private LocalDateTime leaseExpiresAt;
   @Column(name = "retry_version", nullable = false)
   private int retryVersion;

   @PrePersist
   protected void initializeTimestamps() {
      LocalDateTime now = LocalDateTime.now();
      if (this.createdAt == null) {
         this.createdAt = now;
      }

      if (this.updatedAt == null) {
         this.updatedAt = now;
      }
   }

   @PreUpdate
   protected void updateTimestamp() {
      this.updatedAt = LocalDateTime.now();
   }

   private static int $default$retryVersion() {
      return 0;
   }

   protected Creation(final Creation.CreationBuilder<?, ?> b) {
      super(b);
      this.user = b.user;
      this.workflow = b.workflow;
      this.workflowSnapshot = b.workflowSnapshot;
      this.sourceModality = b.sourceModality;
      this.sourceText = b.sourceText;
      this.sourcePainting = b.sourcePainting;
      this.sourceAsset = b.sourceAsset;
      this.status = b.status;
      this.finalModality = b.finalModality;
      this.finalAsset = b.finalAsset;
      this.finalOutputJson = b.finalOutputJson;
      this.createdAt = b.createdAt;
      this.startedAt = b.startedAt;
      this.finishedAt = b.finishedAt;
      this.errorCode = b.errorCode;
      this.errorMessage = b.errorMessage;
      this.updatedAt = b.updatedAt;
      this.claimToken = b.claimToken;
      this.leaseExpiresAt = b.leaseExpiresAt;
      if (b.retryVersion$set) {
         this.retryVersion = b.retryVersion$value;
      } else {
         this.retryVersion = $default$retryVersion();
      }
   }

   public static Creation.CreationBuilder<?, ?> builder() {
      return new Creation.CreationBuilderImpl();
   }

   public User getUser() {
      return this.user;
   }

   public UserWorkflow getWorkflow() {
      return this.workflow;
   }

   public String getWorkflowSnapshot() {
      return this.workflowSnapshot;
   }

   public String getSourceModality() {
      return this.sourceModality;
   }

   public String getSourceText() {
      return this.sourceText;
   }

   public Painting getSourcePainting() {
      return this.sourcePainting;
   }

   public MediaAsset getSourceAsset() {
      return this.sourceAsset;
   }

   public String getStatus() {
      return this.status;
   }

   public String getFinalModality() {
      return this.finalModality;
   }

   public MediaAsset getFinalAsset() {
      return this.finalAsset;
   }

   public String getFinalOutputJson() {
      return this.finalOutputJson;
   }

   public LocalDateTime getCreatedAt() {
      return this.createdAt;
   }

   public LocalDateTime getStartedAt() {
      return this.startedAt;
   }

   public LocalDateTime getFinishedAt() {
      return this.finishedAt;
   }

   public String getErrorCode() {
      return this.errorCode;
   }

   public String getErrorMessage() {
      return this.errorMessage;
   }

   public LocalDateTime getUpdatedAt() {
      return this.updatedAt;
   }

   public String getClaimToken() {
      return this.claimToken;
   }

   public LocalDateTime getLeaseExpiresAt() {
      return this.leaseExpiresAt;
   }

   public int getRetryVersion() {
      return this.retryVersion;
   }

   public void setUser(final User user) {
      this.user = user;
   }

   public void setWorkflow(final UserWorkflow workflow) {
      this.workflow = workflow;
   }

   public void setWorkflowSnapshot(final String workflowSnapshot) {
      this.workflowSnapshot = workflowSnapshot;
   }

   public void setSourceModality(final String sourceModality) {
      this.sourceModality = sourceModality;
   }

   public void setSourceText(final String sourceText) {
      this.sourceText = sourceText;
   }

   public void setSourcePainting(final Painting sourcePainting) {
      this.sourcePainting = sourcePainting;
   }

   public void setSourceAsset(final MediaAsset sourceAsset) {
      this.sourceAsset = sourceAsset;
   }

   public void setStatus(final String status) {
      this.status = status;
   }

   public void setFinalModality(final String finalModality) {
      this.finalModality = finalModality;
   }

   public void setFinalAsset(final MediaAsset finalAsset) {
      this.finalAsset = finalAsset;
   }

   public void setFinalOutputJson(final String finalOutputJson) {
      this.finalOutputJson = finalOutputJson;
   }

   public void setCreatedAt(final LocalDateTime createdAt) {
      this.createdAt = createdAt;
   }

   public void setStartedAt(final LocalDateTime startedAt) {
      this.startedAt = startedAt;
   }

   public void setFinishedAt(final LocalDateTime finishedAt) {
      this.finishedAt = finishedAt;
   }

   public void setErrorCode(final String errorCode) {
      this.errorCode = errorCode;
   }

   public void setErrorMessage(final String errorMessage) {
      this.errorMessage = errorMessage;
   }

   public void setUpdatedAt(final LocalDateTime updatedAt) {
      this.updatedAt = updatedAt;
   }

   public void setClaimToken(final String claimToken) {
      this.claimToken = claimToken;
   }

   public void setLeaseExpiresAt(final LocalDateTime leaseExpiresAt) {
      this.leaseExpiresAt = leaseExpiresAt;
   }

   public void setRetryVersion(final int retryVersion) {
      this.retryVersion = retryVersion;
   }

   public Creation() {
      this.retryVersion = $default$retryVersion();
   }

   public Creation(
      final User user,
      final UserWorkflow workflow,
      final String workflowSnapshot,
      final String sourceModality,
      final String sourceText,
      final Painting sourcePainting,
      final MediaAsset sourceAsset,
      final String status,
      final String finalModality,
      final MediaAsset finalAsset,
      final String finalOutputJson,
      final LocalDateTime createdAt,
      final LocalDateTime startedAt,
      final LocalDateTime finishedAt,
      final String errorCode,
      final String errorMessage,
      final LocalDateTime updatedAt,
      final String claimToken,
      final LocalDateTime leaseExpiresAt,
      final int retryVersion
   ) {
      this.user = user;
      this.workflow = workflow;
      this.workflowSnapshot = workflowSnapshot;
      this.sourceModality = sourceModality;
      this.sourceText = sourceText;
      this.sourcePainting = sourcePainting;
      this.sourceAsset = sourceAsset;
      this.status = status;
      this.finalModality = finalModality;
      this.finalAsset = finalAsset;
      this.finalOutputJson = finalOutputJson;
      this.createdAt = createdAt;
      this.startedAt = startedAt;
      this.finishedAt = finishedAt;
      this.errorCode = errorCode;
      this.errorMessage = errorMessage;
      this.updatedAt = updatedAt;
      this.claimToken = claimToken;
      this.leaseExpiresAt = leaseExpiresAt;
      this.retryVersion = retryVersion;
   }

   public abstract static class CreationBuilder<C extends Creation, B extends Creation.CreationBuilder<C, B>>
      extends BasePublicIdEntity.BasePublicIdEntityBuilder<C, B> {
      private User user;
      private UserWorkflow workflow;
      private String workflowSnapshot;
      private String sourceModality;
      private String sourceText;
      private Painting sourcePainting;
      private MediaAsset sourceAsset;
      private String status;
      private String finalModality;
      private MediaAsset finalAsset;
      private String finalOutputJson;
      private LocalDateTime createdAt;
      private LocalDateTime startedAt;
      private LocalDateTime finishedAt;
      private String errorCode;
      private String errorMessage;
      private LocalDateTime updatedAt;
      private String claimToken;
      private LocalDateTime leaseExpiresAt;
      private boolean retryVersion$set;
      private int retryVersion$value;

      public B user(final User user) {
         this.user = user;
         return this.self();
      }

      public B workflow(final UserWorkflow workflow) {
         this.workflow = workflow;
         return this.self();
      }

      public B workflowSnapshot(final String workflowSnapshot) {
         this.workflowSnapshot = workflowSnapshot;
         return this.self();
      }

      public B sourceModality(final String sourceModality) {
         this.sourceModality = sourceModality;
         return this.self();
      }

      public B sourceText(final String sourceText) {
         this.sourceText = sourceText;
         return this.self();
      }

      public B sourcePainting(final Painting sourcePainting) {
         this.sourcePainting = sourcePainting;
         return this.self();
      }

      public B sourceAsset(final MediaAsset sourceAsset) {
         this.sourceAsset = sourceAsset;
         return this.self();
      }

      public B status(final String status) {
         this.status = status;
         return this.self();
      }

      public B finalModality(final String finalModality) {
         this.finalModality = finalModality;
         return this.self();
      }

      public B finalAsset(final MediaAsset finalAsset) {
         this.finalAsset = finalAsset;
         return this.self();
      }

      public B finalOutputJson(final String finalOutputJson) {
         this.finalOutputJson = finalOutputJson;
         return this.self();
      }

      public B createdAt(final LocalDateTime createdAt) {
         this.createdAt = createdAt;
         return this.self();
      }

      public B startedAt(final LocalDateTime startedAt) {
         this.startedAt = startedAt;
         return this.self();
      }

      public B finishedAt(final LocalDateTime finishedAt) {
         this.finishedAt = finishedAt;
         return this.self();
      }

      public B errorCode(final String errorCode) {
         this.errorCode = errorCode;
         return this.self();
      }

      public B errorMessage(final String errorMessage) {
         this.errorMessage = errorMessage;
         return this.self();
      }

      public B updatedAt(final LocalDateTime updatedAt) {
         this.updatedAt = updatedAt;
         return this.self();
      }

      public B claimToken(final String claimToken) {
         this.claimToken = claimToken;
         return this.self();
      }

      public B leaseExpiresAt(final LocalDateTime leaseExpiresAt) {
         this.leaseExpiresAt = leaseExpiresAt;
         return this.self();
      }

      public B retryVersion(final int retryVersion) {
         this.retryVersion$value = retryVersion;
         this.retryVersion$set = true;
         return this.self();
      }

      protected abstract B self();

      public abstract C build();

      @Override
      public String toString() {
         return "Creation.CreationBuilder(super="
            + super.toString()
            + ", user="
            + this.user
            + ", workflow="
            + this.workflow
            + ", workflowSnapshot="
            + this.workflowSnapshot
            + ", sourceModality="
            + this.sourceModality
            + ", sourceText="
            + this.sourceText
            + ", sourcePainting="
            + this.sourcePainting
            + ", sourceAsset="
            + this.sourceAsset
            + ", status="
            + this.status
            + ", finalModality="
            + this.finalModality
            + ", finalAsset="
            + this.finalAsset
            + ", finalOutputJson="
            + this.finalOutputJson
            + ", createdAt="
            + this.createdAt
            + ", startedAt="
            + this.startedAt
            + ", finishedAt="
            + this.finishedAt
            + ", errorCode="
            + this.errorCode
            + ", errorMessage="
            + this.errorMessage
            + ", updatedAt="
            + this.updatedAt
            + ", claimToken="
            + this.claimToken
            + ", leaseExpiresAt="
            + this.leaseExpiresAt
            + ", retryVersion$value="
            + this.retryVersion$value
            + ")";
      }
   }

   private static final class CreationBuilderImpl extends Creation.CreationBuilder<Creation, Creation.CreationBuilderImpl> {
      protected Creation.CreationBuilderImpl self() {
         return this;
      }

      @Override
      public Creation build() {
         return new Creation(this);
      }
   }
}
