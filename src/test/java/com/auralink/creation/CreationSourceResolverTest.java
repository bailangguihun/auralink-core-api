package com.auralink.creation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.auralink.api.v1.creation.CreationSourceRequest;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.entity.MediaAsset;
import com.auralink.entity.Painting;
import com.auralink.entity.User;
import com.auralink.media.MediaAssetValues;
import com.auralink.repository.MediaAssetRepository;
import com.auralink.repository.PaintingRepository;
import com.auralink.workflow.WorkflowModality;

@ExtendWith(MockitoExtension.class)
class CreationSourceResolverTest {

    @Mock private MediaAssetRepository mediaAssets;
    @Mock private PaintingRepository paintings;

    private CreationSourceResolver resolver;
    private User owner;

    @BeforeEach
    void setUp() {
        CreationProviderProperties properties = new CreationProviderProperties();
        properties.setMaxTextChars(20_000);
        resolver = new CreationSourceResolver(mediaAssets, paintings, properties);
        owner = new User();
        owner.setId(17L);
    }

    @Test
    void acceptsBoundedTextDescriptionAndPoemOnlyInSourceText() {
        CreationSourceResolver.ResolvedSource text = resolver.resolve(
                source(WorkflowModality.TEXT_DESCRIPTION, "山水相依", null, null),
                WorkflowModality.TEXT_DESCRIPTION,
                owner);
        assertEquals(WorkflowModality.TEXT_DESCRIPTION, text.modality());
        assertEquals("山水相依", text.sourceText());
        assertEquals(null, text.sourceAsset());
        assertEquals(null, text.sourcePainting());

        CreationSourceResolver.ResolvedSource poem = resolver.resolve(
                source(WorkflowModality.POEM, "明月松间照", null, null), WorkflowModality.POEM, owner);
        assertEquals("明月松间照", poem.sourceText());
    }

    @Test
    void rejectsSourceMismatchBlankOversizedAndUnsupportedControlText() {
        ApiV1Exception mismatch = assertThrows(ApiV1Exception.class,
                () -> resolver.resolve(source(WorkflowModality.TEXT_DESCRIPTION, "valid", null, null),
                        WorkflowModality.POEM, owner));
        assertEquals("CREATION_SOURCE_MISMATCH", mismatch.getCode().name());
        assertInvalid(source(WorkflowModality.TEXT_DESCRIPTION, "   ", null, null), WorkflowModality.TEXT_DESCRIPTION);
        assertInvalid(source(WorkflowModality.TEXT_DESCRIPTION, "x".repeat(20_001), null, null),
                WorkflowModality.TEXT_DESCRIPTION);
        assertInvalid(source(WorkflowModality.TEXT_DESCRIPTION, "bad\u0000text", null, null),
                WorkflowModality.TEXT_DESCRIPTION);
    }

    @Test
    void resolvesOnlyAnActivePrivateImageOwnedByTheAuthenticatedUser() {
        String assetId = UUID.randomUUID().toString();
        MediaAsset asset = new MediaAsset();
        asset.setVisibility(MediaAssetValues.Visibility.PRIVATE);
        when(mediaAssets.findByPublicIdAndOwnerUser_IdAndStatusAndAssetType(
                assetId,
                owner.getId(),
                MediaAssetValues.Status.ACTIVE,
                MediaAssetValues.AssetType.IMAGE)).thenReturn(Optional.of(asset));

        CreationSourceResolver.ResolvedSource resolved = resolver.resolve(
                source(WorkflowModality.IMAGE, null, assetId, null), WorkflowModality.IMAGE, owner);
        assertSame(asset, resolved.sourceAsset());

        String otherUsersAssetId = UUID.randomUUID().toString();
        when(mediaAssets.findByPublicIdAndOwnerUser_IdAndStatusAndAssetType(
                eq(otherUsersAssetId), eq(owner.getId()), eq(MediaAssetValues.Status.ACTIVE),
                eq(MediaAssetValues.AssetType.IMAGE))).thenReturn(Optional.empty());
        assertInvalid(source(WorkflowModality.IMAGE, null, otherUsersAssetId, null),
                WorkflowModality.IMAGE);
    }

    @Test
    void resolvesOnlyAnActiveOfficialPaintingWithAnActivePublicCatalogImage() {
        String paintingId = UUID.randomUUID().toString();
        MediaAsset catalogImage = new MediaAsset();
        catalogImage.setStatus(MediaAssetValues.Status.ACTIVE);
        catalogImage.setAssetType(MediaAssetValues.AssetType.IMAGE);
        catalogImage.setSourceType(MediaAssetValues.SourceType.CATALOG_REFERENCE);
        catalogImage.setVisibility(MediaAssetValues.Visibility.PUBLIC);
        Painting painting = new Painting();
        painting.setImageAvailable(true);
        painting.setImageAsset(catalogImage);
        when(paintings.findByPublicIdAndStatus(paintingId, "ACTIVE")).thenReturn(Optional.of(painting));

        CreationSourceResolver.ResolvedSource resolved = resolver.resolve(
                source(WorkflowModality.PAINTING, null, null, paintingId), WorkflowModality.PAINTING, owner);
        assertSame(painting, resolved.sourcePainting());

        catalogImage.setVisibility(MediaAssetValues.Visibility.PRIVATE);
        assertInvalid(source(WorkflowModality.PAINTING, null, null, paintingId), WorkflowModality.PAINTING);
    }

    private static CreationSourceRequest source(
            WorkflowModality modality,
            String text,
            String assetId,
            String paintingId) {
        CreationSourceRequest request = new CreationSourceRequest();
        request.setModality(modality.name());
        if (text != null) {
            request.setText(text);
        }
        if (assetId != null) {
            request.setAssetId(assetId);
        }
        if (paintingId != null) {
            request.setPaintingId(paintingId);
        }
        return request;
    }

    private void assertInvalid(CreationSourceRequest request, WorkflowModality expected) {
        ApiV1Exception exception = assertThrows(ApiV1Exception.class,
                () -> resolver.resolve(request, expected, owner));
        assertEquals("CREATION_SOURCE_INVALID", exception.getCode().name());
    }
}
