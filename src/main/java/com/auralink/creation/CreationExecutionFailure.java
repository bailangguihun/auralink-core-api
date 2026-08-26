package com.auralink.creation;

import com.auralink.creation.provider.ProviderErrorCategory;

/**
 * Small, stable, non-secret error vocabulary persisted for asynchronous
 * Creation execution.  Provider messages, URLs, prompts, paths and exception
 * text are intentionally never copied into a Creation row.
 */
public record CreationExecutionFailure(String code, String message) {

    private static final int MAX_MESSAGE_LENGTH = 240;

    public CreationExecutionFailure {
        if (code == null || code.isBlank() || message == null || message.isBlank()) {
            throw new IllegalArgumentException("Safe Creation execution failure is required");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            message = message.substring(0, MAX_MESSAGE_LENGTH);
        }
    }

    public static CreationExecutionFailure featureDisabled() {
        return new CreationExecutionFailure("CREATION_FEATURE_DISABLED", "创作执行功能当前未启用");
    }

    public static CreationExecutionFailure operationUnavailable() {
        return new CreationExecutionFailure("CREATION_OPERATION_UNAVAILABLE", "创作操作当前不可执行");
    }

    public static CreationExecutionFailure snapshotInvalid() {
        return new CreationExecutionFailure("CREATION_SNAPSHOT_INVALID", "创作工作流快照无效");
    }

    public static CreationExecutionFailure stepMismatch() {
        return new CreationExecutionFailure("CREATION_STEP_MISMATCH", "创作步骤与工作流快照不一致");
    }

    public static CreationExecutionFailure inputInvalid() {
        return new CreationExecutionFailure("CREATION_INPUT_INVALID", "创作输入当前不可用或无效");
    }

    public static CreationExecutionFailure resultInvalid() {
        return new CreationExecutionFailure("PROVIDER_OUTPUT_INVALID", "服务返回结果无效");
    }

    public static CreationExecutionFailure persistenceFailed() {
        return new CreationExecutionFailure("CREATION_RESULT_PERSISTENCE_FAILED", "创作结果保存失败");
    }

    public static CreationExecutionFailure interrupted() {
        return new CreationExecutionFailure("CREATION_EXECUTION_INTERRUPTED", "创作执行被中断");
    }

    public static CreationExecutionFailure fromProvider(ProviderErrorCategory category) {
        if (category == null) {
            return new CreationExecutionFailure("PROVIDER_EXECUTION_FAILED", "创作服务执行失败");
        }
        return switch (category) {
            case PROVIDER_FEATURE_DISABLED -> featureDisabled();
            case PROVIDER_CONFIGURATION_MISSING -> new CreationExecutionFailure(
                    "CREATION_PROVIDER_CONFIGURATION_MISSING", "创作服务配置不可用");
            case PROVIDER_CONFIGURATION_INVALID -> new CreationExecutionFailure(
                    "CREATION_PROVIDER_CONFIGURATION_INVALID", "创作服务配置无效");
            case PROVIDER_TIMEOUT -> new CreationExecutionFailure(
                    "PROVIDER_TIMEOUT", "创作服务请求超时");
            case PROVIDER_RATE_LIMITED -> new CreationExecutionFailure(
                    "PROVIDER_RATE_LIMITED", "创作服务当前繁忙");
            case PROVIDER_REJECTED -> new CreationExecutionFailure(
                    "PROVIDER_REJECTED", "创作服务拒绝了本次请求");
            case PROVIDER_INVALID_RESPONSE, PROVIDER_OUTPUT_INVALID -> resultInvalid();
            case PROVIDER_UNAVAILABLE, PROVIDER_CAPACITY_EXCEEDED -> new CreationExecutionFailure(
                    "PROVIDER_UNAVAILABLE", "创作服务当前不可用");
            case PROVIDER_INTERNAL_CONTRACT_ERROR -> new CreationExecutionFailure(
                    "PROVIDER_CONTRACT_ERROR", "创作服务返回结果不符合约定");
        };
    }
}
