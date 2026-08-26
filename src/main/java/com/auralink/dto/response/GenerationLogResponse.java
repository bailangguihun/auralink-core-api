package com.auralink.dto.response;

import java.time.LocalDateTime;

import com.auralink.entity.GenerationLog;

/**
 * Legacy-compatible generation-log payload without JPA entity exposure.
 */
public record GenerationLogResponse(
        Long id,
        UserSummaryResponse user,
        String taskType,
        String type,
        String apiSource,
        String apiProvider,
        String inputData,
        String outputData,
        String imageUrl,
        String resultUrl,
        String description,
        String modelSize,
        boolean useFastGenerate,
        Integer duration,
        Long processingTimeMs,
        boolean success,
        String errorMessage,
        String metadata,
        LocalDateTime createdAt) {

    public static GenerationLogResponse from(GenerationLog log) {
        return new GenerationLogResponse(
                log.getId(),
                UserSummaryResponse.from(log.getUser()),
                log.getTaskType(),
                log.getType(),
                log.getApiSource(),
                log.getApiProvider(),
                log.getInputData(),
                log.getOutputData(),
                log.getImageUrl(),
                log.getResultUrl(),
                log.getDescription(),
                log.getModelSize(),
                log.isUseFastGenerate(),
                log.getDuration(),
                log.getProcessingTimeMs(),
                log.isSuccess(),
                log.getErrorMessage(),
                log.getMetadata(),
                log.getCreatedAt());
    }
}
