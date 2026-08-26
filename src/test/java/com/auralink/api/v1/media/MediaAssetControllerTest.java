package com.auralink.api.v1.media;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.api.v1.error.ApiV1ExceptionHandler;
import com.auralink.config.properties.MediaAssetProperties;
import com.auralink.entity.MediaAsset;
import com.auralink.entity.User;
import com.auralink.media.MediaAssetValues;
import com.auralink.service.media.MediaAssetService;
import com.auralink.service.media.MediaAssetService.AccessibleMediaAssetContent;

class MediaAssetControllerTest {

    private static final String ASSET_ID = "2f8f56f2-7d57-4b39-98c5-bc9d59c70f36";
    private static final String SHA256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @TempDir
    Path tempDir;

    private MediaAssetService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(MediaAssetService.class);
        MediaAssetProperties properties = new MediaAssetProperties();
        properties.setPublicCacheSeconds(60);
        MediaAssetController controller = new MediaAssetController(service, properties);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiV1ExceptionHandler())
                .build();
    }

    @Test
    void uploadReturnsSafeCreatedDto() throws Exception {
        MediaAsset asset = asset(MediaAssetValues.Visibility.PRIVATE, "painting.png", "image/png");
        when(service.storeAuthenticatedImage(any(), eq(MediaAssetValues.SemanticType.PAINTING)))
                .thenReturn(asset);
        MockMultipartFile file = new MockMultipartFile(
                "file", "painting.png", "image/png", new byte[] {1, 2, 3});

        mockMvc.perform(multipart("/api/v1/assets/uploads")
                        .file(file)
                        .param("semanticType", "PAINTING"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/assets/" + ASSET_ID))
                .andExpect(jsonPath("$.assetId").value(ASSET_ID))
                .andExpect(jsonPath("$.contentUrl").value(
                        "/api/v1/assets/" + ASSET_ID + "/content"))
                .andExpect(jsonPath("$.downloadUrl").value(
                        "/api/v1/assets/" + ASSET_ID + "/download"))
                .andExpect(jsonPath("$.storageKey").doesNotExist())
                .andExpect(jsonPath("$.ownerUser").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist());

        verify(service).storeAuthenticatedImage(any(), eq(MediaAssetValues.SemanticType.PAINTING));
    }

    @Test
    void metadataNeverExposesStorageOrOwnerFields() throws Exception {
        when(service.getAccessibleAsset(ASSET_ID))
                .thenReturn(asset(MediaAssetValues.Visibility.PUBLIC, "catalog.png", "image/png"));

        mockMvc.perform(get("/api/v1/assets/{assetId}", ASSET_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(ASSET_ID))
                .andExpect(jsonPath("$.sourceType").value(MediaAssetValues.SourceType.CATALOG_REFERENCE))
                .andExpect(jsonPath("$.storageKey").doesNotExist())
                .andExpect(jsonPath("$.ownerUser").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void filesystemResourceSupportsRangeEtagAndPublicCaching() throws Exception {
        byte[] bytes = "abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path file = Files.write(tempDir.resolve("catalog.png"), bytes);
        MediaAsset asset = asset(MediaAssetValues.Visibility.PUBLIC, "catalog.png", "image/png");
        when(service.getAccessibleContent(ASSET_ID)).thenReturn(
                new AccessibleMediaAssetContent(asset, new FileSystemResource(file), bytes.length));

        mockMvc.perform(get("/api/v1/assets/{assetId}/content", ASSET_ID)
                        .header(HttpHeaders.RANGE, "bytes=1-3"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 1-3/6"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 3))
                .andExpect(header().string(HttpHeaders.ETAG, '"' + SHA256 + '"'))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=60")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("public")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline"))
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes("bcd".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void etagSupportsConditionalGetWithoutOpeningAStreamResource() throws Exception {
        byte[] bytes = new byte[] {1, 2, 3};
        Path file = Files.write(tempDir.resolve("private.png"), bytes);
        MediaAsset asset = asset(MediaAssetValues.Visibility.PRIVATE, "private.png", "image/png");
        when(service.getAccessibleContent(ASSET_ID)).thenReturn(
                new AccessibleMediaAssetContent(asset, new FileSystemResource(file), bytes.length));

        mockMvc.perform(get("/api/v1/assets/{assetId}/content", ASSET_ID)
                        .header(HttpHeaders.IF_NONE_MATCH, '"' + SHA256 + '"'))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, '"' + SHA256 + '"'))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(content().bytes(new byte[0]));
    }

    @Test
    void downloadDispositionCannotInjectResponseHeaders() throws Exception {
        byte[] bytes = new byte[] {1, 2, 3};
        Path file = Files.write(tempDir.resolve("asset.png"), bytes);
        MediaAsset asset = asset(
                MediaAssetValues.Visibility.PRIVATE,
                "../../evil\";\r\nX-Injected: yes.png",
                "image/png");
        when(service.getAccessibleContent(ASSET_ID)).thenReturn(
                new AccessibleMediaAssetContent(asset, new FileSystemResource(file), bytes.length));

        MvcResult result = mockMvc.perform(get("/api/v1/assets/{assetId}/download", ASSET_ID))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andReturn();

        String disposition = result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertFalse(disposition.contains("\r"));
        assertFalse(disposition.contains("\n"));
        assertFalse(result.getResponse().containsHeader("X-Injected"));
    }

    @Test
    void activeGeneratedFileContentIsForcedToOpaqueAttachment() throws Exception {
        byte[] bytes = "<script>alert(1)</script>"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path file = Files.write(tempDir.resolve("generated.html"), bytes);
        MediaAsset asset = asset(MediaAssetValues.Visibility.PRIVATE, "generated.html", "text/html");
        asset.setAssetType(MediaAssetValues.AssetType.FILE);
        asset.setSemanticType(MediaAssetValues.SemanticType.OTHER);
        asset.setSourceType(MediaAssetValues.SourceType.GENERATED);
        when(service.getAccessibleContent(ASSET_ID)).thenReturn(
                new AccessibleMediaAssetContent(asset, new FileSystemResource(file), bytes.length));

        mockMvc.perform(get("/api/v1/assets/{assetId}/content", ASSET_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
                .andExpect(content().bytes(bytes));
    }

    @Test
    void controlledAssetErrorsUseTheV1Envelope() throws Exception {
        when(service.getAccessibleAsset("not-a-uuid")).thenThrow(new ApiV1Exception(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.INVALID_ASSET_ID,
                "资源标识格式无效"));

        mockMvc.perform(get("/api/v1/assets/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ASSET_ID"))
                .andExpect(jsonPath("$.message").value("资源标识格式无效"));
    }

    private MediaAsset asset(String visibility, String filename, String mimeType) {
        User owner = MediaAssetValues.Visibility.PRIVATE.equals(visibility)
                ? User.builder().id(7L).username("owner").password("test-only")
                        .fullName("Owner").email("owner@example.test").build()
                : null;
        return MediaAsset.builder()
                .id(99L)
                .publicId(ASSET_ID)
                .ownerUser(owner)
                .storageKey("internal/not-exposed.png")
                .originalFilename(filename)
                .mimeType(mimeType)
                .fileSize(6L)
                .sha256(SHA256)
                .width(1)
                .height(1)
                .assetType(MediaAssetValues.AssetType.IMAGE)
                .semanticType(MediaAssetValues.SemanticType.PAINTING)
                .sourceType(owner == null
                        ? MediaAssetValues.SourceType.CATALOG_REFERENCE
                        : MediaAssetValues.SourceType.USER_UPLOAD)
                .visibility(visibility)
                .status(MediaAssetValues.Status.ACTIVE)
                .createdAt(LocalDateTime.of(2026, 8, 11, 10, 0))
                .updatedAt(LocalDateTime.of(2026, 8, 11, 10, 0))
                .build();
    }
}
