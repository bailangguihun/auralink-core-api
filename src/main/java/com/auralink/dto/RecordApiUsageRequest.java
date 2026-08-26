package com.auralink.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecordApiUsageRequest {

    @NotBlank(message = "任务类型不能为空")
    private String taskType; // "IMAGE_DESCRIPTION", "MUSIC_GENERATION", "TEXT_TO_IMAGE", "IMAGE_TO_VIDEO", "AUDIO_GENERATION", "MULTIMODAL_CHAT", "VIDEO_UNDERSTANDING", etc.

    @NotBlank(message = "API来源不能为空")
    private String apiSource; // "LOCAL", "THIRD_PARTY", "VMM", "NONVMM"

    private String apiProvider; // 第三方API提供商名称（如果是第三方API）

    private String inputData; // 详细的输入信息（JSON字符串）

    private String outputData; // 详细的输出信息（JSON字符串）

    private String imageUrl; // 图像URL（可选，为了兼容性）

    private String resultUrl; // 结果URL（可选，为了兼容性）

    private String description; // 描述信息（可选，为了兼容性）

    private String modelSize; // 模型大小（可选，为了兼容性）

    private Boolean useFastGenerate; // 是否使用快速生成（可选，为了兼容性）

    private Integer duration; // 持续时间（可选，为了兼容性）

    private Long processingTimeMs; // 处理时间（毫秒）

    @NotNull(message = "成功状态不能为空")
    private Boolean success; // 是否成功

    private String errorMessage; // 错误信息（如果失败）

    private String metadata; // 其他元数据信息（JSON字符串）
}