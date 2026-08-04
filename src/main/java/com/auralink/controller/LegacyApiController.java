package com.auralink.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.auralink.dto.ImageDescriptionRequest;
import com.auralink.service.GenerationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 旧版API控制器，保持与原Python后端的兼容性
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class LegacyApiController {

    private final GenerationService generationService;

    @GetMapping("/models")
    public ResponseEntity<Map<String, Object>> getModels() {
        Map<String, Object> models = generationService.getModels();
        return ResponseEntity.ok(models);
    }

    @PostMapping("/describe_image")
    public ResponseEntity<Map<String, Object>> describeImage(@RequestBody Map<String, Object> request) {
        log.info("旧版API - 描述图像: {}", request.get("image"));

        ImageDescriptionRequest req = new ImageDescriptionRequest();
        req.setImageUrl((String) request.get("image"));

        Map<String, Object> result = generationService.generateImageDescription(req);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generateMusic(@RequestBody Map<String, Object> request) {
        log.info("旧版API - 生成音乐: {}", request);

        // 从请求中提取参数
        String imageUrl = (String) request.get("image");
        String modelSize = (String) request.getOrDefault("modelSize", "small");
        Integer duration = (Integer) request.getOrDefault("duration", 30);
        String mode = (String) request.getOrDefault("mode", "nonvmm");
        String textDescription = (String) request.get("text_description");

        // 设置请求对象
        com.auralink.dto.GenerateMusicRequest req = com.auralink.dto.GenerateMusicRequest.builder()
                .imageUrl(imageUrl)
                .modelSize(modelSize)
                .useFastGenerate("vmm".equalsIgnoreCase(mode))
                .duration(duration)
                .textDescription(textDescription)
                .build();

        Map<String, Object> result = generationService.generateMusic(req);
        return ResponseEntity.ok(result);
    }
}
