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
public class UploadResultRequest {

    @NotNull(message = "日志ID不能为空")
    private Long logId; // 关联的GenerationLog ID

    @NotBlank(message = "内容类型不能为空")
    private String contentType; // "image", "audio", "video", "text", "json", etc.

    private String fileName; // 建议的文件名（可选）

    private String fileExtension; // 文件扩展名（如 .jpg, .mp3, .mp4）

    // 数据来源方式（二选一）
    private String base64Data; // Base64编码的二进制数据

    private String remoteUrl; // 远程URL（系统会下载并保存）

    // 可选的元数据
    private String description; // 内容描述

    private String metadata; // 其他元数据（JSON字符串）

    // 验证方法
    public boolean hasValidContent() {
        return (base64Data != null && !base64Data.trim().isEmpty()) ||
               (remoteUrl != null && !remoteUrl.trim().isEmpty());
    }
}