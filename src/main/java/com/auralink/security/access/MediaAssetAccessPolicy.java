package com.auralink.security.access;

import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.entity.MediaAsset;
import com.auralink.entity.User;
import com.auralink.media.MediaAssetValues;

/**
 * Per-resource read policy for MediaAsset metadata and bytes.
 *
 * <p>All denial paths intentionally become the same not-found response, so a
 * caller cannot discover another user's private asset.</p>
 */
@Component
public class MediaAssetAccessPolicy {

    public void requireReadable(MediaAsset asset, User currentUser) {
        if (!canRead(asset, currentUser)) {
            throw assetNotFound();
        }
    }

    public boolean canRead(MediaAsset asset, User currentUser) {
        if (asset == null || !MediaAssetValues.Status.ACTIVE.equals(asset.getStatus())) {
            return false;
        }
        if (MediaAssetValues.Visibility.PUBLIC.equals(asset.getVisibility())) {
            return true;
        }
        if (!MediaAssetValues.Visibility.PRIVATE.equals(asset.getVisibility()) || currentUser == null) {
            return false;
        }

        User owner = asset.getOwnerUser();
        return owner != null
                && owner.getId() != null
                && Objects.equals(owner.getId(), currentUser.getId());
    }

    public static ApiV1Exception assetNotFound() {
        return new ApiV1Exception(
                HttpStatus.NOT_FOUND,
                ApiErrorCode.ASSET_NOT_FOUND,
                "资源不存在");
    }
}
