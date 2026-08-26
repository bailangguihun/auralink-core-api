package com.auralink.security.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.entity.MediaAsset;
import com.auralink.entity.User;
import com.auralink.media.MediaAssetValues;

class MediaAssetAccessPolicyTest {

    private final MediaAssetAccessPolicy policy = new MediaAssetAccessPolicy();

    @Test
    void activePublicAssetIsReadableWithoutAuthentication() {
        MediaAsset asset = asset(MediaAssetValues.Visibility.PUBLIC, MediaAssetValues.Status.ACTIVE, null);

        assertThat(policy.canRead(asset, null)).isTrue();
    }

    @Test
    void activePrivateAssetIsReadableOnlyByItsPersistedOwnerId() {
        User owner = User.builder().id(7L).username("owner").build();
        User sameOwnerIdentity = User.builder().id(7L).username("renamed-owner").build();
        User other = User.builder().id(8L).username("other").build();
        MediaAsset asset = asset(MediaAssetValues.Visibility.PRIVATE, MediaAssetValues.Status.ACTIVE, owner);

        assertThat(policy.canRead(asset, sameOwnerIdentity)).isTrue();
        assertThat(policy.canRead(asset, null)).isFalse();
        assertThat(policy.canRead(asset, other)).isFalse();
    }

    @Test
    void deletedFailedUnknownVisibilityAndMissingAssetsAreUnavailableToEveryone() {
        User owner = User.builder().id(7L).build();

        assertThat(policy.canRead(asset("PRIVATE", MediaAssetValues.Status.DELETED, owner), owner)).isFalse();
        assertThat(policy.canRead(asset("PUBLIC", MediaAssetValues.Status.FAILED, null), owner)).isFalse();
        assertThat(policy.canRead(asset("UNRECOGNIZED", MediaAssetValues.Status.ACTIVE, owner), owner)).isFalse();
        assertThat(policy.canRead(null, owner)).isFalse();
    }

    @Test
    void denialUsesTheSameAssetNotFoundContract() {
        User owner = User.builder().id(7L).build();
        User other = User.builder().id(8L).build();
        MediaAsset privateAsset = asset("PRIVATE", "ACTIVE", owner);

        assertThatThrownBy(() -> policy.requireReadable(privateAsset, other))
                .isInstanceOfSatisfying(ApiV1Exception.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ApiErrorCode.ASSET_NOT_FOUND);
                    assertThat(exception.getStatus().value()).isEqualTo(404);
                });
    }

    private MediaAsset asset(String visibility, String status, User owner) {
        return MediaAsset.builder()
                .ownerUser(owner)
                .storageKey("managed/private/test.png")
                .originalFilename("test.png")
                .assetType("IMAGE")
                .semanticType("IMAGE")
                .sourceType(owner == null ? "CATALOG_REFERENCE" : "USER_UPLOAD")
                .visibility(visibility)
                .status(status)
                .build();
    }
}
