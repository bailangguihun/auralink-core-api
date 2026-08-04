package com.auralink.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerateMusicRequest {

    @NotBlank(message = "图片URL不能为空")
    private String imageUrl;

    @NotBlank(message = "模型大小不能为空")
    private String modelSize;

    private Boolean useFastGenerate = false;

    @Min(value = 10, message = "音乐时长最短为10秒")
    @Max(value = 120, message = "音乐时长最长为120秒")
    private Integer duration = 30;

    private String textDescription;
}
