package com.auralink.service.media;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;

import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.config.properties.MediaAssetProperties;
import com.auralink.entity.MediaAsset;
import com.auralink.entity.User;
import com.auralink.exception.StorageException;
import com.auralink.repository.MediaAssetRepository;
import com.auralink.repository.UserRepository;
import com.auralink.security.access.MediaAssetAccessPolicy;
import com.auralink.service.CurrentUserService;
import com.auralink.service.media.ImageContentValidator.ValidatedImage;
import com.auralink.service.media.MediaAssetStorageService.StagedMediaFile;
import com.auralink.service.media.MediaAssetStorageService.StoredMediaFile;

class MediaAssetServiceFailureTest {

    private MediaAssetRepository mediaAssets;
    private CurrentUserService currentUsers;
    private MediaAssetStorageService storage;
    private ImageContentValidator images;
    private MediaAssetService service;
    private User owner;

    @BeforeEach
    void setUp() {
        mediaAssets = mock(MediaAssetRepository.class);
        UserRepository users = mock(UserRepository.class);
        currentUsers = mock(CurrentUserService.class);
        MediaAssetAccessPolicy accessPolicy = mock(MediaAssetAccessPolicy.class);
        storage = mock(MediaAssetStorageService.class);
        images = mock(ImageContentValidator.class);
        MediaAssetProperties properties = new MediaAssetProperties();
        service = new MediaAssetService(
                mediaAssets, users, currentUsers, accessPolicy, storage, images, properties);

        owner = User.builder()
                .id(7L)
                .username("failure-owner")
                .password("test-hash")
                .fullName("Failure Owner")
                .email("failure-owner@example.test")
                .build();
        when(currentUsers.requireCurrentUser()).thenReturn(owner);
    }

    @Test
    void storageFailureOccursBeforePersistenceAndLeavesNoRow() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "painting.png", "image/png", new byte[] {1, 2, 3});
        when(storage.stageUserUpload(any())).thenThrow(new StorageException("synthetic write failure"));

        assertThatThrownBy(() -> service.storeAuthenticatedImage(file, "IMAGE"))
                .isInstanceOfSatisfying(ApiV1Exception.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getCode())
                                .isEqualTo(ApiErrorCode.ASSET_STORAGE_ERROR));

        verify(mediaAssets, never()).saveAndFlush(any(MediaAsset.class));
    }

    @Test
    void databasePersistenceFailureDeletesOnlyTheNewManagedFile() throws Exception {
        byte[] bytes = new byte[] {1, 2, 3};
        MockMultipartFile file = new MockMultipartFile(
                "file", "painting.png", "image/png", bytes);
        StagedMediaFile staged = new StagedMediaFile(
                Path.of("/tmp/round4-synthetic-stage"), bytes.length, "a".repeat(64));
        when(storage.stageUserUpload(any())).thenReturn(staged);
        when(images.validateUpload(staged.path(), "image/png", "painting.png"))
                .thenReturn(new ValidatedImage("PNG", "image/png", "png", 1, 1));
        when(storage.commitManaged(any(StagedMediaFile.class), anyString()))
                .thenAnswer(invocation -> new StoredMediaFile(
                        invocation.getArgument(1), bytes.length, "a".repeat(64)));
        when(mediaAssets.saveAndFlush(any(MediaAsset.class)))
                .thenThrow(new DataIntegrityViolationException("synthetic persistence failure"));

        assertThatThrownBy(() -> service.storeAuthenticatedImage(file, "PAINTING"))
                .isInstanceOfSatisfying(ApiV1Exception.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getCode())
                                .isEqualTo(ApiErrorCode.ASSET_STORAGE_ERROR));

        verify(storage).deleteManaged(anyString());
        verify(storage, never()).discardStaged(staged);
    }
}
