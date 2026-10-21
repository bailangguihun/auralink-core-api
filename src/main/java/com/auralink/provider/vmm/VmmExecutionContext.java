package com.auralink.provider.vmm;

/** Database-derived identifiers, never deserialized from a browser request. */
public record VmmExecutionContext(long ownerId, long creationId, long stepId, int retryVersion, Long sourcePaintingId) {
   public VmmExecutionContext {
      if (ownerId < 1 || creationId < 1 || stepId < 1 || retryVersion < 0 || sourcePaintingId == null || sourcePaintingId < 1) {
         throw new IllegalArgumentException("Managed VMM execution identity is incomplete");
      }
   }
}
