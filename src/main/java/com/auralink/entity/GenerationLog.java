package com.auralink.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "generation_logs")
public class GenerationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // 扩展的任务类型，支持更多跨模态方法
    @Column(nullable = false)
    private String taskType; // "IMAGE_DESCRIPTION", "MUSIC_GENERATION", "TEXT_TO_IMAGE", "IMAGE_TO_VIDEO", "AUDIO_GENERATION", "MULTIMODAL_CHAT", "VIDEO_UNDERSTANDING", etc.

    // API来源类型
    @Column(nullable = false)
    private String apiSource; // "LOCAL", "THIRD_PARTY", "VMM", "NONVMM"

    // 第三方API提供商（如果是第三方API）
    @Column
    private String apiProvider; // "OpenAI", "Anthropic", "Google", "Stability", etc.

    // 详细的输入信息（JSON格式）
    @Column(columnDefinition = "TEXT")
    private String inputData;

    // 详细的输出信息（JSON格式）
    @Column(columnDefinition = "TEXT")
    private String outputData;

    // 原有字段保持兼容性
    @Column(length = 1024)
    private String imageUrl;

    @Column(length = 1024)
    private String resultUrl;

    @Column(length = 1024)
    private String description;

    @Column(nullable = false)
    private String modelSize;

    @Column(nullable = false)
    private boolean useFastGenerate;

    @Column
    private Integer duration;

    // 处理时间（毫秒）
    @Column
    private Long processingTimeMs;

    // 请求状态
    @Column(nullable = false)
    private boolean success;

    @Column(length = 1024)
    private String errorMessage;

    // 元数据信息（JSON格式）
    @Column(columnDefinition = "TEXT")
    private String metadata;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // 为了向后兼容，保留type字段的getter方法
    public String getType() {
        return this.taskType;
    }

    public void setType(String type) {
        this.taskType = type;
    }
}