package com.auralink.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import com.auralink.config.properties.StorageProperties;
import com.auralink.dto.ApiResponse;
import com.auralink.dto.GenerateMusicRequest;
import com.auralink.dto.ImageDescriptionRequest;
import com.auralink.dto.RecordApiUsageRequest;
import com.auralink.dto.UploadResultRequest;
import com.auralink.exception.InvalidStoragePathException;
import com.auralink.service.GenerationService;
import com.auralink.service.StorageService;
import com.auralink.service.UploadSessionService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {

    private static final Pattern DANGEROUS_ENCODED_PATH = Pattern.compile(
            "(?i)%(?:25|2e|2f|5c|00|0d|0a)");

    private final StorageService storageService;
    private final GenerationService generationService;
    private final UploadSessionService uploadSessionService;
    private final StorageProperties storageConfig;

    @PostMapping("/upload-image")
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "session", required = false) String sessionId) {
        try {
            String filepath = storageService.storeFile(file);
            log.info("文件上传成功: {}", filepath);

            String relativePath = toRelativeUploadPath(filepath);
            String imageUrl = buildFileUrl(relativePath);

            if (sessionId != null && !sessionId.trim().isEmpty()) {
                uploadSessionService.save(sessionId.trim(), relativePath);
            }

            Map<String, Object> result = new HashMap<>();
            // Legacy creation models forward filepath directly to the Python image service.
            // Keep that contract usable without disclosing a server-local absolute path.
            result.put("filepath", imageUrl);
            result.put("relativePath", relativePath);
            // Retain the legacy field name without disclosing a server-local path.
            result.put("absolutePath", relativePath);
            result.put("imageUrl", imageUrl);

            return ResponseEntity.ok(ApiResponse.success("文件上传成功", result));
        } catch (IOException e) {
            log.error("文件上传失败: type={}", e.getClass().getSimpleName());
            return ResponseEntity.badRequest().body(ApiResponse.error("文件上传失败"));
        }
    }

    @PostMapping("/upload-session/{sessionId}/image")
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadImageForSession(
            @PathVariable String sessionId,
            @RequestParam("file") MultipartFile file) {
        try {
            if (sessionId == null || sessionId.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(ApiResponse.error("sessionId 不能为空"));
            }

            String filepath = storageService.storeFile(file);
            String relativePath = toRelativeUploadPath(filepath);
            uploadSessionService.save(sessionId.trim(), relativePath);

            Map<String, Object> result = new HashMap<>();
            result.put("sessionId", sessionId.trim());
            result.put("filepath", relativePath);
            // Retain the legacy field name without disclosing a server-local path.
            result.put("absolutePath", relativePath);
            result.put("imageUrl", buildFileUrl(relativePath));
            result.put("status", "uploaded");

            return ResponseEntity.ok(ApiResponse.success("上传成功", result));
        } catch (IOException e) {
            log.error("会话上传失败: type={}", e.getClass().getSimpleName());
            return ResponseEntity.badRequest().body(ApiResponse.error("会话上传失败"));
        }
    }

    @GetMapping("/upload-session/{sessionId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getUploadSession(@PathVariable String sessionId) {
        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", sessionId);

        String filepath = uploadSessionService.get(sessionId);
        if (filepath == null || filepath.isBlank()) {
            result.put("status", "pending");
            return ResponseEntity.ok(ApiResponse.success("会话存在但尚未上传图片", result));
        }

        result.put("status", "uploaded");
        result.put("filepath", filepath);
        result.put("imageUrl", buildFileUrl(filepath));
        return ResponseEntity.ok(ApiResponse.success("已获取会话上传结果", result));
    }

    @PostMapping("/describe-image")
    public ResponseEntity<ApiResponse<Map<String, Object>>> describeImage(
            @Valid @RequestBody ImageDescriptionRequest request) {

        Map<String, Object> result = generationService.generateImageDescription(request);

        if ((Boolean) result.getOrDefault("success", false)) {
            return ResponseEntity.ok(ApiResponse.success("图像描述生成成功", result));
        } else {
            return ResponseEntity.badRequest().body(ApiResponse.error((String) result.get("message")));
        }
    }

    @PostMapping("/generate-music")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generateMusic(
            @Valid @RequestBody GenerateMusicRequest request) {

        Map<String, Object> result = generationService.generateMusic(request);

        if ((Boolean) result.getOrDefault("success", false)) {
            return ResponseEntity.ok(ApiResponse.success("音乐生成成功", result));
        } else {
            return ResponseEntity.badRequest().body(ApiResponse.error((String) result.get("message")));
        }
    }

    @PostMapping("/cleanup")
    public ResponseEntity<ApiResponse<Void>> cleanup() {
        try {
            storageService.cleanupOldFiles();
            return ResponseEntity.ok(ApiResponse.success("清理完成", null));
        } catch (Exception e) {
            log.error("清理临时文件时出错: type={}", e.getClass().getSimpleName());
            return ResponseEntity.badRequest().body(ApiResponse.error("清理临时文件时出错"));
        }
    }

    @GetMapping("/audios/{filename:.+}")
    public ResponseEntity<Resource> serveAudio(@PathVariable String filename) {
        try {
            Path filePath = Paths.get(storageConfig.getAudioDir()).resolve(filename);
            Resource resource = new UrlResource(filePath.toUri());

            if (resource.exists() && resource.isReadable()) {
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resource.getFilename() + "\"")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(resource);
            } else {
                log.error("无法读取文件: {}", filename);
                return ResponseEntity.notFound().build();
            }
        } catch (IOException e) {
            log.error("获取音频文件时出错: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    // 统一的文件服务接口 - 支持所有类型的生成内容
    @GetMapping("/files/**")
    public ResponseEntity<Resource> serveFile(HttpServletRequest request) {
        try {
            // 获取完整的文件路径（去掉 /api/files/ 前缀）
            String relativePath = extractAndDecodeFilePath(request.getRequestURI());
            Path filePath = storageService.resolveStoredFile(relativePath);
            Resource resource = new UrlResource(filePath.toUri());

            if (resource.exists() && resource.isReadable()) {
                // 根据文件扩展名确定Content-Type
                String contentType = determineContentType(filePath);

                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + resource.getFilename() + "\"")
                        .contentType(MediaType.parseMediaType(contentType))
                        .body(resource);
            } else {
                log.error("无法读取文件: {}", relativePath);
                return ResponseEntity.notFound().build();
            }
        } catch (InvalidStoragePathException | IllegalArgumentException e) {
            log.warn("拒绝不安全的文件路径请求");
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("获取文件时出错: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    // 确定文件的Content-Type
    private String determineContentType(Path filePath) {
        String fileName = filePath.getFileName().toString().toLowerCase();

        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (fileName.endsWith(".png")) {
            return "image/png";
        } else if (fileName.endsWith(".gif")) {
            return "image/gif";
        } else if (fileName.endsWith(".webp")) {
            return "image/webp";
        } else if (fileName.endsWith(".bmp")) {
            return "image/bmp";
        } else if (fileName.endsWith(".mp3")) {
            return "audio/mpeg";
        } else if (fileName.endsWith(".wav")) {
            return "audio/wav";
        } else if (fileName.endsWith(".flac")) {
            return "audio/flac";
        } else if (fileName.endsWith(".aac")) {
            return "audio/aac";
        } else if (fileName.endsWith(".ogg")) {
            return "audio/ogg";
        } else if (fileName.endsWith(".m4a")) {
            return "audio/mp4";
        } else if (fileName.endsWith(".mp4")) {
            return "video/mp4";
        } else if (fileName.endsWith(".avi")) {
            return "video/avi";
        } else if (fileName.endsWith(".mov")) {
            return "video/quicktime";
        } else if (fileName.endsWith(".mkv")) {
            return "video/x-matroska";
        } else if (fileName.endsWith(".webm")) {
            return "video/webm";
        } else if (fileName.endsWith(".txt")) {
            return "text/plain";
        } else if (fileName.endsWith(".json")) {
            return "application/json";
        } else if (fileName.endsWith(".xml")) {
            return "application/xml";
        } else if (fileName.endsWith(".pdf")) {
            return "application/pdf";
        } else {
            return "application/octet-stream";
        }
    }

    // 模型服务的转发API
    @GetMapping("/models")
    public ResponseEntity<Map<String, Object>> getModels() {
        Map<String, Object> models = generationService.getModels();
        return ResponseEntity.ok(models);
    }

    // 记录API使用情况
    @PostMapping("/record")
    public ResponseEntity<ApiResponse<Map<String, Object>>> recordApiUsage(
            @Valid @RequestBody RecordApiUsageRequest request) {
        try {
            Map<String, Object> result = generationService.recordApiUsage(request);
            return ResponseEntity.ok(ApiResponse.success("API使用记录保存成功", result));
        } catch (Exception e) {
            log.error("记录API使用情况时出错: type={}", e.getClass().getSimpleName());
            return ResponseEntity.badRequest().body(ApiResponse.error("记录API使用情况时出错"));
        }
    }

    // 上传第三方API生成的结果
    @PostMapping("/upload-result")
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadResult(
            @Valid @RequestBody UploadResultRequest request) {
        try {
            // 验证内容
            if (!request.hasValidContent()) {
                return ResponseEntity.badRequest().body(
                    ApiResponse.error("请提供Base64数据或远程URL"));
            }

            Map<String, Object> result = generationService.uploadResult(request);
            return ResponseEntity.ok(ApiResponse.success("结果上传成功", result));
        } catch (Exception e) {
            log.error("上传结果失败: type={}", e.getClass().getSimpleName());
            return ResponseEntity.badRequest().body(ApiResponse.error("上传结果失败"));
        }
    }

    private String toRelativeUploadPath(String filepath) {
        Path absoluteUploadDir = Paths.get(storageConfig.getUploadDir()).toAbsolutePath().normalize();
        Path absoluteFilePath = Paths.get(filepath).toAbsolutePath().normalize();
        if (absoluteFilePath.startsWith(absoluteUploadDir)) {
            return absoluteUploadDir.relativize(absoluteFilePath).toString().replace("\\", "/");
        }
        return absoluteFilePath.getFileName().toString();
    }

    private String buildFileUrl(String relativePath) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/files/")
                .path(relativePath)
                .toUriString();
    }

    private String extractAndDecodeFilePath(String requestUri) {
        int marker = requestUri.indexOf("/files/");
        if (marker < 0) {
            throw new InvalidStoragePathException("文件路径格式无效");
        }
        String encodedPath = requestUri.substring(marker + 7);
        // Reject separator/dot double encoding before a single controlled decode.
        if (DANGEROUS_ENCODED_PATH.matcher(encodedPath).find()) {
            throw new InvalidStoragePathException("编码的文件路径不允许访问");
        }
        try {
            return UriUtils.decode(encodedPath, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new InvalidStoragePathException("文件路径编码无效", e);
        }
    }
}
