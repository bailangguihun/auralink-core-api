package com.auralink.api.v1.painting;

import com.fasterxml.jackson.annotation.JsonInclude;

public record PaintingImageResponse(
   String assetId, String mimeType, Long fileSize, Integer width, Integer height, String contentUrl, String downloadUrl,
   @JsonInclude(JsonInclude.Include.NON_NULL) String thumbnailUrl
) {
   public PaintingImageResponse(String assetId, String mimeType, Long fileSize, Integer width, Integer height, String contentUrl, String downloadUrl) {
      this(assetId, mimeType, fileSize, width, height, contentUrl, downloadUrl, null);
   }
}
