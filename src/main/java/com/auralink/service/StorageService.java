package com.auralink.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.apache.commons.io.FilenameUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.auralink.config.properties.StorageProperties;
import com.auralink.exception.InvalidStoragePathException;
import com.auralink.exception.StorageException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {

    private final StorageProperties storageConfig;
    private final SafeRemoteResourceFetcher remoteResourceFetcher;

    // 允许的图片文件扩展名
    private static final Set<String> ALLOWED_IMAGE_EXTENSIONS = new HashSet<>(
            Arrays.asList("jpg", "jpeg", "png", "gif", "bmp", "webp"));

    // 允许的音频文件扩展名
    private static final Set<String> ALLOWED_AUDIO_EXTENSIONS = new HashSet<>(
            Arrays.asList("mp3", "wav", "flac", "aac", "ogg", "m4a"));

    // 允许的视频文件扩展名
    private static final Set<String> ALLOWED_VIDEO_EXTENSIONS = new HashSet<>(
            Arrays.asList("mp4", "avi", "mov", "mkv", "webm", "flv"));

    // 允许的文档文件扩展名
    private static final Set<String> ALLOWED_DOCUMENT_EXTENSIONS = new HashSet<>(
            Arrays.asList("txt", "pdf", "doc", "docx", "json", "xml"));

    // 文件类型映射
    private static final Map<String, Set<String>> CONTENT_TYPE_MAPPING = new HashMap<>();

    static {
        CONTENT_TYPE_MAPPING.put("image", ALLOWED_IMAGE_EXTENSIONS);
        CONTENT_TYPE_MAPPING.put("audio", ALLOWED_AUDIO_EXTENSIONS);
        CONTENT_TYPE_MAPPING.put("video", ALLOWED_VIDEO_EXTENSIONS);
        CONTENT_TYPE_MAPPING.put("document", ALLOWED_DOCUMENT_EXTENSIONS);
    }

    public String storeFile(MultipartFile file) throws IOException {
        // 检查文件是否为空
        if (file.isEmpty()) {
            throw new IllegalArgumentException("无法存储空文件");
        }

        // 获取文件名和扩展名
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isEmpty()) {
            throw new IllegalArgumentException("文件名为空");
        }

        String extension = FilenameUtils.getExtension(originalFilename).toLowerCase();

        // 验证文件类型
        if (!ALLOWED_IMAGE_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("不支持的文件类型: " + extension + "。请上传jpg、jpeg、png、gif、bmp或webp格式的图片");
        }

        // 生成唯一文件名
        String newFilename = UUID.randomUUID().toString() + "." + extension;

        try {
            // 获取存储路径
            Path uploadDir = Paths.get(storageConfig.getUploadDir());

            // 确保目录存在
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
                log.info("创建上传目录: {}", uploadDir);
            }

            // 存储文件，使用REPLACE_EXISTING选项覆盖可能存在的同名文件
            Path targetPath = uploadDir.resolve(newFilename);
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

            log.info("文件已上传: {}, 原始文件名: {}, 大小: {} 字节",
                    targetPath, originalFilename, file.getSize());

            return targetPath.toAbsolutePath().toString();
        } catch (IOException e) {
            log.error("文件存储失败: type={}", e.getClass().getSimpleName());
            throw new StorageException("文件存储失败", e);
        }
    }

    public File getFile(String filePath) {
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            log.warn("请求的文件不存在: {}", filePath);
            return null;
        }
        return file;
    }

    public void cleanupOldFiles() {
        log.info("开始清理过期文件...");

        try {
            Path uploadDir = Paths.get(storageConfig.getUploadDir());
            LocalDateTime now = LocalDateTime.now();

            if (Files.exists(uploadDir)) {
                try (Stream<Path> files = Files.list(uploadDir)) {
                    files.filter(Files::isRegularFile).forEach(file -> {
                        try {
                            LocalDateTime fileCreated = LocalDateTime.ofInstant(
                                    Files.getLastModifiedTime(file).toInstant(),
                                    java.time.ZoneId.systemDefault());

                            // 删除超过24小时的文件
                            long hoursBetween = ChronoUnit.HOURS.between(fileCreated, now);
                            if (hoursBetween > 24) {
                                Files.delete(file);
                                log.info("已删除过期文件: {}", file);
                            }
                        } catch (IOException e) {
                            log.error("清理文件时出错: {}", e.getMessage());
                        }
                    });
                }
            }
        } catch (IOException e) {
            log.error("清理过期文件出错: {}", e.getMessage());
        }
    }

    /**
     * 从Base64数据保存文件
     */
    public String storeBase64File(String base64Data, String contentType, String fileExtension, Long userId) throws IOException {
        if (base64Data == null || base64Data.trim().isEmpty()) {
            throw new IllegalArgumentException("Base64数据不能为空");
        }

        // 验证文件类型
        validateFileType(contentType, fileExtension);

        // 解码Base64数据
        byte[] decodedBytes;
        try {
            // 处理可能包含data URL前缀的情况
            String cleanBase64 = base64Data;
            if (base64Data.contains(",")) {
                cleanBase64 = base64Data.substring(base64Data.indexOf(",") + 1);
            }
            decodedBytes = Base64.getDecoder().decode(cleanBase64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无效的Base64数据格式");
        }

        // 生成存储路径
        String filePath = generateFilePath(contentType, fileExtension, userId);
        Path targetPath = Paths.get(filePath);

        // 确保目录存在
        Files.createDirectories(targetPath.getParent());

        // 保存文件
        Files.write(targetPath, decodedBytes);

        log.info("Base64文件已保存: {}, 类型: {}, 大小: {} 字节", filePath, contentType, decodedBytes.length);

        return getRelativeFilePath(filePath);
    }

    /**
     * 从远程URL下载并保存文件
     */
    public String storeRemoteFile(String remoteUrl, String contentType, String fileExtension, Long userId) throws IOException {
        if (remoteUrl == null || remoteUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("远程URL不能为空");
        }

        // 验证文件类型
        validateFileType(contentType, fileExtension);

        // 生成存储路径
        String filePath = generateFilePath(contentType, fileExtension, userId);
        Path targetPath = Paths.get(filePath);

        // 确保目录存在
        Files.createDirectories(targetPath.getParent());

        // 通过受限的 HTTP(S) 获取边界下载；临时文件只有完整通过大小与地址校验后才会落盘。
        try {
            remoteResourceFetcher.fetchTo(remoteUrl, targetPath);
        } catch (IOException e) {
            throw new IOException("远程资源下载或安全校验失败", e);
        }

        log.info("远程文件已下载并保存: {}, 类型: {}", filePath, contentType);

        return getRelativeFilePath(filePath);
    }

    /**
     * 验证文件类型
     */
    private void validateFileType(String contentType, String fileExtension) {
        if (contentType == null || contentType.trim().isEmpty()) {
            throw new IllegalArgumentException("内容类型不能为空");
        }

        Set<String> allowedExtensions = CONTENT_TYPE_MAPPING.get(contentType.toLowerCase());
        if (allowedExtensions == null) {
            throw new IllegalArgumentException("不支持的内容类型: " + contentType);
        }

        if (fileExtension != null && !fileExtension.trim().isEmpty()) {
            String cleanExtension = fileExtension.toLowerCase().replaceAll("^\\.", "");
            if (!allowedExtensions.contains(cleanExtension)) {
                throw new IllegalArgumentException("文件扩展名 " + fileExtension + " 与内容类型 " + contentType + " 不匹配");
            }
        }
    }

    /**
     * 生成有组织的文件存储路径
     * 格式: {storageDir}/{contentType}/{yyyy-MM}/{userId}/{filename}
     */
    private String generateFilePath(String contentType, String fileExtension, Long userId) {
        String extension = (fileExtension != null && !fileExtension.trim().isEmpty())
                ? fileExtension.replaceAll("^\\.", "")
                : getDefaultExtension(contentType);

        String fileName = UUID.randomUUID().toString() + "." + extension;
        String monthFolder = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));

        return Paths.get(storageConfig.getUploadDir(),
                        contentType.toLowerCase(),
                        monthFolder,
                        userId.toString(),
                        fileName).toString();
    }

    /**
     * 获取默认文件扩展名
     */
    private String getDefaultExtension(String contentType) {
        Set<String> extensions = CONTENT_TYPE_MAPPING.get(contentType.toLowerCase());
        if (extensions != null && !extensions.isEmpty()) {
            return extensions.iterator().next(); // 返回第一个扩展名作为默认值
        }
        return "bin"; // 默认二进制文件扩展名
    }

    /**
     * 获取相对文件路径（用于API返回）
     */
    private String getRelativeFilePath(String absolutePath) {
        Path storageRoot = getStorageRoot();
        Path storedPath = Paths.get(absolutePath).toAbsolutePath().normalize();
        if (storedPath.startsWith(storageRoot)) {
            return storageRoot.relativize(storedPath).toString().replace("\\", "/");
        }
        throw new InvalidStoragePathException("存储结果不在配置的存储目录中");
    }

    /**
     * 将不可信的相对路径安全解析到存储根目录。
     *
     * <p>除了词法规范化，还会检查已存在的路径及其最近的已存在父目录的
     * 真实路径，从而阻止通过符号链接逃逸。</p>
     */
    public Path resolveStoredFile(String relativePath) {
        if (relativePath == null
                || relativePath.isBlank()
                || relativePath.indexOf('\0') >= 0
                || relativePath.indexOf('\r') >= 0
                || relativePath.indexOf('\n') >= 0) {
            throw new InvalidStoragePathException("文件路径不能为空");
        }
        if (relativePath.indexOf('\\') >= 0
                || relativePath.startsWith("//")
                || relativePath.matches("^[A-Za-z]:[/\\\\].*")) {
            throw new InvalidStoragePathException("不允许绝对路径或非标准路径分隔符");
        }

        final Path requested;
        try {
            requested = Paths.get(relativePath);
        } catch (InvalidPathException e) {
            throw new InvalidStoragePathException("文件路径格式无效", e);
        }
        if (requested.isAbsolute()) {
            throw new InvalidStoragePathException("不允许绝对文件路径");
        }
        for (Path segment : requested) {
            if ("..".equals(segment.toString())) {
                throw new InvalidStoragePathException("文件路径不得包含上级目录跳转");
            }
        }

        Path storageRoot = getStorageRoot();
        Path candidate = storageRoot.resolve(requested).normalize();
        if (!candidate.startsWith(storageRoot)) {
            throw new InvalidStoragePathException("文件路径超出配置的存储目录");
        }

        return resolveCanonicalContainedPath(storageRoot, candidate);
    }

    private Path getStorageRoot() {
        return Paths.get(storageConfig.getUploadDir()).toAbsolutePath().normalize();
    }

    private Path resolveCanonicalContainedPath(Path storageRoot, Path candidate) {
        try {
            if (!Files.exists(storageRoot, LinkOption.NOFOLLOW_LINKS)) {
                return candidate;
            }
            Path realRoot = storageRoot.toRealPath();
            Path existing = candidate;
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing == null) {
                throw new InvalidStoragePathException("文件路径通过符号链接超出配置的存储目录");
            }
            Path realExisting = existing.toRealPath();
            if (!realExisting.startsWith(realRoot)) {
                throw new InvalidStoragePathException("文件路径通过符号链接超出配置的存储目录");
            }
            // Existing files are served through their checked canonical path rather
            // than re-traversing a caller-controlled symlink after validation.
            return Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)
                    ? realExisting
                    : candidate;
        } catch (InvalidStoragePathException e) {
            throw e;
        } catch (IOException e) {
            throw new InvalidStoragePathException("无法验证文件存储路径", e);
        }
    }
}
