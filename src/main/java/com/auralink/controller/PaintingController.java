package com.auralink.controller;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.auralink.config.AppConfig.PaintingConfig;
import com.auralink.dto.ApiResponse;
import com.auralink.service.PaintingCatalogService;
import com.auralink.service.PaintingCatalogService.PaintingPage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/paintings")
@RequiredArgsConstructor
public class PaintingController {

    private final PaintingCatalogService paintingCatalogService;
    private final PaintingConfig paintingConfig;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> listPaintings(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String dynasty,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        PaintingPage page = paintingCatalogService.search(query, dynasty, limit, offset);

        List<Map<String, Object>> items = page.items().stream()
                .map(this::withImageUrl)
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", page.total());
        result.put("limit", page.limit());
        result.put("offset", page.offset());
        result.put("items", items);
        return ResponseEntity.ok(ApiResponse.success("Paintings loaded", result));
    }

    @GetMapping("/images/{fileName:.+}")
    public ResponseEntity<Resource> servePaintingImage(@PathVariable String fileName) {
        try {
            Optional<Path> resolvedPath = paintingCatalogService.resolveImagePath(fileName);
            if (resolvedPath.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            Path imagePath = resolvedPath.get();
            Resource resource = new UrlResource(imagePath.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                return ResponseEntity.notFound().build();
            }

            String contentType = Files.probeContentType(imagePath);
            if (contentType == null || contentType.isBlank()) {
                contentType = MediaType.IMAGE_JPEG_VALUE;
            }

            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noCache())
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + imagePath.getFileName() + "\"")
                    .contentType(MediaType.parseMediaType(contentType))
                    .body(resource);
        } catch (IOException ex) {
            log.error("Failed to serve painting image: {}", fileName, ex);
            return ResponseEntity.internalServerError().build();
        }
    }

    private Map<String, Object> withImageUrl(Map<String, Object> item) {
        Map<String, Object> result = new LinkedHashMap<>(item);
        String imageFileName = extractImageFileName(item);
        if (!StringUtils.hasText(imageFileName)) {
            result.put("imageUrl", "");
            return result;
        }

        if (StringUtils.hasText(paintingConfig.getImageBaseUrl())) {
            String baseUrl = paintingConfig.getImageBaseUrl().trim();
            String encodedName = URLEncoder.encode(imageFileName, StandardCharsets.UTF_8).replace("+", "%20");
            String imageUrl = baseUrl.endsWith("/") ? baseUrl + encodedName : baseUrl + "/" + encodedName;
            result.put("imageUrl", imageUrl);
            return result;
        }

        String imageUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/paintings/images/")
                .pathSegment(imageFileName)
                .toUriString();
        result.put("imageUrl", imageUrl);
        return result;
    }

    private String extractImageFileName(Map<String, Object> item) {
        Object imageFileName = item.get("imageFileName");
        if (imageFileName instanceof String value && StringUtils.hasText(value)) {
            return value.trim();
        }

        Object storageName = item.get("imageStorageName");
        if (!(storageName instanceof String value) || !StringUtils.hasText(value)) {
            return "";
        }

        String normalized = value.trim()
                .replace('（', '(')
                .replace('）', ')')
                .replaceAll("\\s+", " ");
        if (normalized.toLowerCase().endsWith(".jpg") || normalized.toLowerCase().endsWith(".jpeg")) {
            return normalized;
        }
        return normalized + ".jpg";
    }
}
