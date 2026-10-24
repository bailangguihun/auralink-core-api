package com.auralink.provider.vmm;

/** Database-derived identifiers, never deserialized from a browser request. */
public record VmmExecutionContext(long ownerId, long creationId, long stepId, int retryVersion, Long sourcePaintingId, Long sourceAssetId) {
   public VmmExecutionContext {
      if (ownerId < 1 || creationId < 1 || stepId < 1 || retryVersion < 0
         || (sourcePaintingId == null) == (sourceAssetId == null)
         || sourcePaintingId != null && sourcePaintingId < 1 || sourceAssetId != null && sourceAssetId < 1) {
         throw new IllegalArgumentException("Managed VMM execution identity is incomplete");
      }
   }
   public VmmExecutionContext(long ownerId, long creationId, long stepId, int retryVersion, Long sourcePaintingId) {
      this(ownerId, creationId, stepId, retryVersion, sourcePaintingId, null);
   }
   public String sourceKind() { return sourcePaintingId != null ? "CATALOG_PAINTING" : "OWNED_UPLOAD"; }
}
