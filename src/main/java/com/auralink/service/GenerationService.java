package com.auralink.service;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.auralink.config.properties.ServiceProperties;
import com.auralink.dto.GenerateMusicRequest;
import com.auralink.dto.ImageDescriptionRequest;
import com.auralink.dto.RecordApiUsageRequest;
import com.auralink.dto.UploadResultRequest;
import com.auralink.entity.GenerationLog;
import com.auralink.entity.User;
import com.auralink.repository.GenerationLogRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class GenerationService {

    private final RestTemplate restTemplate;
    private final ServiceProperties serviceConfig;
    private final GenerationLogRepository generationLogRepository;
    private final StorageService storageService;

    // 服务健康状态缓存，避免重复检查
    private boolean vmmServiceHealthy = false;
    private boolean nonvmmServiceHealthy = false;
    private long lastVmmHealthCheck = 0;
    private long lastNonvmmHealthCheck = 0;
    private static final long HEALTH_CHECK_INTERVAL = 60000; // 1分钟检查一次

    public Map<String, Object> getModels() {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> vmmResult = new HashMap<>();
        Map<String, Object> nonVmmResult = new HashMap<>();

        try {
            // 检查VMM服务健康状态
            boolean isVmmHealthy = checkVmmServiceHealth();
            vmmResult.put("available", isVmmHealthy);
            vmmResult.put("models", isVmmHealthy ? new String[]{"small"} : new String[]{});

            // 检查非VMM服务健康状态
            boolean isNonVmmHealthy = checkNonvmmServiceHealth();
            nonVmmResult.put("available", isNonVmmHealthy);
            nonVmmResult.put("models", isNonVmmHealthy ? new String[]{"small", "medium", "large"} : new String[]{});
        } catch (Exception e) {
            log.error("获取模型信息时出错: {}", e.getMessage());
            vmmResult.put("available", false);
            vmmResult.put("models", new String[]{});
            nonVmmResult.put("available", false);
            nonVmmResult.put("models", new String[]{});
        }

        result.put("vmm", vmmResult);
        result.put("nonvmm", nonVmmResult);

        return result;
    }

    public Map<String, Object> generateImageDescription(ImageDescriptionRequest request) {
        log.info("生成图像描述: {}", request.getImageUrl());
        User currentUser = getCurrentUser();

        Map<String, Object> requestData = new HashMap<>();
        requestData.put("image", request.getImageUrl());

        Map<String, Object> result = new HashMap<>();
        boolean success = false;
        String description = "";
        String errorMessage = "";

        // 检查非VMM服务状态
        if (!checkNonvmmServiceHealth()) {
            errorMessage = "描述服务不可用，请稍后再试";
            log.error(errorMessage);

            result.put("success", false);
            result.put("message", errorMessage);

            // 记录失败日志
            saveGenerationLog(currentUser, "IMAGE_TO_TEXT", request.getImageUrl(),
                    null, null, "small", false, false, errorMessage);

            return result;
        }

        try {
            // 调用非VMM服务的描述生成API
            String serviceUrl = serviceConfig.getNonvmmUrl() + "/describe_image";
            log.info("发送图像描述请求到: {}", serviceUrl);

            // 设置超时重试次数
            int maxRetries = 2;
            int currentRetry = 0;
            boolean requestSuccessful = false;
            ResponseEntity<Map> response = null;

            while (currentRetry <= maxRetries && !requestSuccessful) {
                try {
                    response = restTemplate.postForEntity(serviceUrl, requestData, Map.class);
                    requestSuccessful = true;
                } catch (RestClientException ex) {
                    currentRetry++;
                    if (currentRetry <= maxRetries) {
                        log.warn("请求图像描述失败，尝试重试 ({}/{}): {}", currentRetry, maxRetries, ex.getMessage());
                        TimeUnit.SECONDS.sleep(1); // 等待1秒后重试
                    } else {
                        throw ex; // 重试次数用尽，抛出异常
                    }
                }
            }

            if (requestSuccessful && response != null && response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                Map<String, Object> responseBody = response.getBody();
                description = (String) responseBody.get("description");
                success = true;
                result.put("success", true);
                result.put("description", description);
                result.put("message", "图像描述生成成功");
            } else {
                String statusCode = response != null ? response.getStatusCode().toString() : "未知";
                errorMessage = "描述服务返回错误: " + statusCode;
                result.put("success", false);
                result.put("message", errorMessage);
            }
        } catch (ResourceAccessException e) {
            // 网络连接异常
            if (e.getCause() instanceof SocketTimeoutException) {
                errorMessage = "描述服务响应超时，请稍后再试";
            } else {
                errorMessage = "无法连接到描述服务";
            }
            log.error("描述服务连接异常: type={}", e.getClass().getSimpleName());
            result.put("success", false);
            result.put("message", errorMessage);
        } catch (Exception e) {
            log.error("生成图像描述时发生错误: type={}", e.getClass().getSimpleName());
            errorMessage = "生成图像描述时发生错误";
            result.put("success", false);
            result.put("message", errorMessage);
        }

        // 记录生成日志
        saveGenerationLog(currentUser, "IMAGE_TO_TEXT", request.getImageUrl(),
                null, description, "small", false, success, errorMessage);

        return result;
    }

    public Map<String, Object> generateMusic(GenerateMusicRequest request) {
        log.info("生成音乐: modelSize={}, useFastGenerate={}, duration={}",
                request.getModelSize(), request.getUseFastGenerate(), request.getDuration());

        User currentUser = getCurrentUser();

        Map<String, Object> requestData = new HashMap<>();
	requestData.put("image", convertImageToBase64(request.getImageUrl()));
        requestData.put("duration", request.getDuration());

        boolean useFastGenerate = request.getUseFastGenerate() != null ? request.getUseFastGenerate() : false;

        Map<String, Object> result = new HashMap<>();
        boolean success = false;
        String resultUrl = "";
        String description = "";
        String errorMessage = "";

        try {
            String serviceUrl;
            boolean serviceHealthy;

            if (useFastGenerate) {
                // 检查VMM服务健康状态
                serviceHealthy = checkVmmServiceHealth();

                if (!serviceHealthy) {
                    errorMessage = "快速生成服务不可用，请尝试使用标准模式";
                    log.error(errorMessage);
                    result.put("success", false);
                    result.put("message", errorMessage);

                    saveGenerationLog(currentUser, "IMAGE_TO_MUSIC", request.getImageUrl(),
                            null, null, request.getModelSize(), useFastGenerate,
                            request.getDuration(), false, errorMessage);

                    return result;
                }

                // VMM模式
                if (request.getTextDescription() != null && !request.getTextDescription().trim().isEmpty()) {
                    serviceUrl = serviceConfig.getVmmUrl() + "/api/generate_with_image_and_text";
                    requestData.put("text_description", request.getTextDescription());
                    log.info("使用VMM模式(图像+文本)生成音乐，转发请求到: {}", serviceUrl);
                } else {
                    serviceUrl = serviceConfig.getVmmUrl() + "/api/generate_with_image";
                    log.info("使用VMM模式(仅图像)生成音乐，转发请求到: {}", serviceUrl);
                }
            } else {
                // 检查非VMM服务健康状态
                serviceHealthy = checkNonvmmServiceHealth();

                if (!serviceHealthy) {
                    errorMessage = "标准生成服务不可用，请尝试使用快速模式";
                    log.error(errorMessage);
                    result.put("success", false);
                    result.put("message", errorMessage);

                    saveGenerationLog(currentUser, "IMAGE_TO_MUSIC", request.getImageUrl(),
                            null, null, request.getModelSize(), useFastGenerate,
                            request.getDuration(), false, errorMessage);

                    return result;
                }

                // 非VMM模式
                serviceUrl = serviceConfig.getNonvmmUrl() + "/generate";
                requestData.put("modelSize", request.getModelSize());
                log.info("使用非VMM模式生成音乐，模型: {}, 转发请求到: {}", request.getModelSize(), serviceUrl);
            }

            // 设置超时重试逻辑
            int maxRetries = 1; // 音乐生成耗时较长，仅重试一次
            int currentRetry = 0;
            boolean requestSuccessful = false;
            ResponseEntity<Map> response = null;

            while (currentRetry <= maxRetries && !requestSuccessful) {
                try {
                    response = restTemplate.postForEntity(serviceUrl, requestData, Map.class);
                    requestSuccessful = true;
                } catch (RestClientException ex) {
                    currentRetry++;
                    if (currentRetry <= maxRetries) {
                        log.warn("生成音乐请求失败，尝试重试 ({}/{}): {}", currentRetry, maxRetries, ex.getMessage());
                        TimeUnit.SECONDS.sleep(2); // 等待2秒后重试
                    } else {
                        throw ex; // 重试次数用尽，抛出异常
                    }
                }
            }

            if (response != null && response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                Map<String, Object> responseBody = response.getBody();
                success = true;

                // 根据不同服务返回的字段进行适配
                if (responseBody.containsKey("fileName")) {
                    resultUrl = (String) responseBody.get("fileName");
                } else if (responseBody.containsKey("audio_url")) {
                    resultUrl = ((String) responseBody.get("audio_url")).replace("/audios/", "");
                }

                // 如果返回了文本描述，也一并返回
                if (responseBody.containsKey("text_description")) {
                    description = (String) responseBody.get("text_description");
                    result.put("description", description);
                }

                result.put("success", true);
                result.put("fileName", resultUrl);
                result.put("message", "音乐生成成功");
            } else {
                String statusCode = response != null ? response.getStatusCode().toString() : "未知";
                errorMessage = "模型服务返回错误: " + statusCode;
                result.put("success", false);
                result.put("message", errorMessage);
            }
        } catch (ResourceAccessException e) {
            // 网络连接异常
            if (e.getCause() instanceof SocketTimeoutException) {
                errorMessage = "音乐生成服务响应超时，请稍后再试";
            } else {
                errorMessage = "无法连接到音乐生成服务";
            }
            log.error("音乐生成服务连接异常: type={}", e.getClass().getSimpleName());
            result.put("success", false);
            result.put("message", errorMessage);
        } catch (Exception e) {
            log.error("生成音乐时发生错误: type={}", e.getClass().getSimpleName());
            errorMessage = "生成音乐时发生错误";
            result.put("success", false);
            result.put("message", errorMessage);
        }

        // 记录生成日志
        saveGenerationLog(currentUser, "IMAGE_TO_MUSIC", request.getImageUrl(),
                resultUrl, description, request.getModelSize(), useFastGenerate,
                request.getDuration(), success, errorMessage);

        return result;
    }

    /**
     * 检查VMM服务健康状态
     */
    private boolean checkVmmServiceHealth() {
        long currentTime = System.currentTimeMillis();

        // 如果上次检查后不到1分钟，则使用缓存结果
        if (currentTime - lastVmmHealthCheck < HEALTH_CHECK_INTERVAL) {
            return vmmServiceHealthy;
        }

        try {
            ResponseEntity<Object> response = restTemplate.exchange(
                    serviceConfig.getVmmUrl() + "/health",
                    HttpMethod.GET,
                    null,
                    Object.class);

            vmmServiceHealthy = response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.warn("VMM服务健康检查失败: {}", e.getMessage());
            vmmServiceHealthy = false;
        }

        lastVmmHealthCheck = currentTime;
        return vmmServiceHealthy;
    }

    /**
     * 检查非VMM服务健康状态
     */
    private boolean checkNonvmmServiceHealth() {
        long currentTime = System.currentTimeMillis();

        // 如果上次检查后不到1分钟，则使用缓存结果
        if (currentTime - lastNonvmmHealthCheck < HEALTH_CHECK_INTERVAL) {
            return nonvmmServiceHealthy;
        }

        try {
            ResponseEntity<Object> response = restTemplate.exchange(
                    serviceConfig.getNonvmmUrl() + "/health",
                    HttpMethod.GET,
                    null,
                    Object.class);

            nonvmmServiceHealthy = response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.warn("非VMM服务健康检查失败: {}", e.getMessage());
            nonvmmServiceHealthy = false;
        }

        lastNonvmmHealthCheck = currentTime;
        return nonvmmServiceHealthy;
    }

    /**
     * 记录API使用情况
     */
    public Map<String, Object> recordApiUsage(RecordApiUsageRequest request) {
        User currentUser = getCurrentUser();

        GenerationLog log = GenerationLog.builder()
                .user(currentUser)
                .taskType(request.getTaskType())
                .apiSource(request.getApiSource())
                .apiProvider(request.getApiProvider())
                .inputData(request.getInputData())
                .outputData(request.getOutputData())
                .imageUrl(request.getImageUrl())
                .resultUrl(request.getResultUrl())
                .description(request.getDescription())
                .modelSize(request.getModelSize() != null ? request.getModelSize() : "unknown")
                .useFastGenerate(request.getUseFastGenerate() != null ? request.getUseFastGenerate() : false)
                .duration(request.getDuration())
                .processingTimeMs(request.getProcessingTimeMs())
                .success(request.getSuccess())
                .errorMessage(request.getErrorMessage())
                .metadata(request.getMetadata())
                .build();

        GenerationLog savedLog = generationLogRepository.save(log);

        Map<String, Object> result = new HashMap<>();
        result.put("logId", savedLog.getId());
        result.put("success", true);
        result.put("message", "API使用记录保存成功");

        return result;
    }

    /**
     * 保存图像描述生成日志
     */
    private void saveGenerationLog(User user, String type, String imageUrl,
                                 String resultUrl, String description, String modelSize,
                                 boolean useFastGenerate, boolean success, String errorMessage) {
        GenerationLog log = GenerationLog.builder()
                .user(user)
                .taskType(type)
                .apiSource("画音智链-墨韵弦思")  // 图像描述使用非VMM服务
                .apiProvider("画音智链墨韵弦思模型")  // 设置本地非VMM提供商
                .imageUrl(imageUrl)
                .resultUrl(resultUrl)
                .description(description)
                .modelSize(modelSize)
                .useFastGenerate(useFastGenerate)
                .success(success)
                .errorMessage(errorMessage)
                .build();

        generationLogRepository.save(log);
    }

    /**
     * 保存音乐生成日志
     */
    private void saveGenerationLog(User user, String type, String imageUrl,
                                 String resultUrl, String description, String modelSize,
                                 boolean useFastGenerate, Integer duration,
                                 boolean success, String errorMessage) {
        // 根据生成模式设置不同的API来源和提供商
        String apiSource = useFastGenerate ? "画音智链-墨韵音声-快速生成" : "画音智链-墨韵音声-标准生成";
        String apiProvider = useFastGenerate ? "画音智链墨韵音声模型" : "画音智链墨韵音声模型";

        GenerationLog log = GenerationLog.builder()
                .user(user)
                .taskType(type)
                .apiSource(apiSource)
                .apiProvider(apiProvider)
                .imageUrl(imageUrl)
                .resultUrl(resultUrl)
                .description(description)
                .modelSize(modelSize)
                .useFastGenerate(useFastGenerate)
                .duration(duration)
                .success(success)
                .errorMessage(errorMessage)
                .build();

        generationLogRepository.save(log);
    }

    /**
     * 上传第三方API生成的结果
     */
    public Map<String, Object> uploadResult(UploadResultRequest request) {
        User currentUser = getCurrentUser();
        Map<String, Object> result = new HashMap<>();

        try {
            // 查找对应的GenerationLog记录
            GenerationLog generationLog = generationLogRepository.findById(request.getLogId())
                    .orElseThrow(() -> new IllegalArgumentException("找不到ID为 " + request.getLogId() + " 的日志记录"));

            // 验证日志属于当前用户
            if (!generationLog.getUser().getId().equals(currentUser.getId())) {
                throw new IllegalArgumentException("无权限访问该日志记录");
            }

            String relativePath = null;

            // 根据提供的数据类型保存文件
            if (request.getBase64Data() != null && !request.getBase64Data().trim().isEmpty()) {
                // 从Base64数据保存文件
                relativePath = storageService.storeBase64File(
                        request.getBase64Data(),
                        request.getContentType(),
                        request.getFileExtension(),
                        currentUser.getId()
                );

            } else if (request.getRemoteUrl() != null && !request.getRemoteUrl().trim().isEmpty()) {
                // 从远程URL下载并保存文件
                relativePath = storageService.storeRemoteFile(
                        request.getRemoteUrl(),
                        request.getContentType(),
                        request.getFileExtension(),
                        currentUser.getId()
                );
            }

            if (relativePath != null) {
                // 更新GenerationLog记录的resultUrl
                generationLog.setResultUrl(relativePath);

                // 如果提供了描述信息，也更新description
                if (request.getDescription() != null && !request.getDescription().trim().isEmpty()) {
                    generationLog.setDescription(request.getDescription());
                }

                // 保存更新后的日志
                generationLogRepository.save(generationLog);

                result.put("success", true);
                result.put("relativePath", relativePath);
                // Retain the legacy field name while returning a non-sensitive relative reference.
                result.put("absolutePath", relativePath);
                result.put("logId", request.getLogId());
                result.put("contentType", request.getContentType());

                log.info("结果文件已保存并关联到日志记录 {}: {}", request.getLogId(), relativePath);
            } else {
                result.put("success", false);
                result.put("message", "文件保存失败");
            }

        } catch (Exception e) {
            // Remote URLs may contain sensitive query values; do not log the exception/caller URL.
            log.error("上传结果失败: type={}", e.getClass().getSimpleName());
            result.put("success", false);
            result.put("message", "结果文件保存失败");
        }

        return result;
    }

    private User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (User) authentication.getPrincipal();
    }
    private String convertImageToBase64(String imageUrl) {
        try {
            if (imageUrl == null || imageUrl.isBlank()) {
                throw new IllegalArgumentException("图片引用不能为空");
            }

            // Legacy callers send either a public /api/files URL or a path. The old
            // implementation intentionally used only the basename, so retain that
            // contract while resolving it against the configured, contained root.
            String normalizedReference = imageUrl.replace('\\', '/');
            int queryIndex = normalizedReference.indexOf('?');
            if (queryIndex >= 0) {
                normalizedReference = normalizedReference.substring(0, queryIndex);
            }
            int fragmentIndex = normalizedReference.indexOf('#');
            if (fragmentIndex >= 0) {
                normalizedReference = normalizedReference.substring(0, fragmentIndex);
            }
            String filename = normalizedReference.substring(normalizedReference.lastIndexOf('/') + 1);
            Path storedImage = storageService.resolveStoredFile(filename);
            if (!Files.isRegularFile(storedImage)) {
                throw new IllegalArgumentException("图片不存在");
            }

            String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(storedImage));
            String lowerFilename = filename.toLowerCase(Locale.ROOT);
            String mime = lowerFilename.endsWith(".jpg") || lowerFilename.endsWith(".jpeg")
                    ? "image/jpeg"
                    : "image/png";

            return "data:" + mime + ";base64," + base64;
        } catch (Exception exception) {
            throw new IllegalArgumentException("图片转换base64失败", exception);
        }
    }
}
