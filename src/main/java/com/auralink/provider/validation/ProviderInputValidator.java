package com.auralink.provider.validation;

import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.creation.provider.PaintingMetadataContext;
import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;
import com.auralink.creation.provider.ProviderImageInput;
import com.auralink.creation.provider.ProviderTextInput;
import com.auralink.provider.artifact.ProviderArtifact;

import lombok.RequiredArgsConstructor;

/** Validates already-resolved typed inputs before any provider request is made. */
@Component
@RequiredArgsConstructor
public class ProviderInputValidator {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final CreationProviderProperties properties;

    public String validateText(ProviderTextInput input) {
        if (input == null) {
            throw invalid("Provider text input is required");
        }
        String text = input.text().trim();
        if (text.isEmpty()) {
            throw invalid("Provider text input must not be blank");
        }
        if (text.length() > properties.getMaxTextChars()) {
            throw invalid("Provider text input exceeds the configured character limit");
        }
        requireSafeText(text, "Provider text input contains unsupported control characters");
        return text;
    }

    public ProviderArtifact validateImage(ProviderImageInput input) {
        if (input == null || input.artifact() == null) {
            throw invalid("Provider image input is required");
        }
        ProviderArtifact artifact = input.artifact();
        if (!artifact.isAvailable()
                || artifact.byteLength() < 1
                || artifact.byteLength() > properties.getMaxImageInputBytes()
                || !("image/jpeg".equals(artifact.mimeType()) || "image/png".equals(artifact.mimeType()))
                || artifact.width() == null || artifact.width() < 1
                || artifact.height() == null || artifact.height() < 1
                || artifact.sha256() == null || !SHA256.matcher(artifact.sha256()).matches()) {
            throw invalid("Provider image input failed validation");
        }
        validateMetadata(input.paintingMetadata());
        return artifact;
    }

    public void validateMetadata(PaintingMetadataContext metadata) {
        if (metadata == null) {
            return;
        }
        requireOptionalPaintingId(metadata.paintingId());
        long total = 0;
        total += requireOptionalSafe(metadata.title());
        total += requireOptionalSafe(metadata.author());
        total += requireOptionalSafe(metadata.dynasty());
        total += requireOptionalSafe(metadata.category());
        total += requireOptionalSafe(metadata.subject());
        total += requireOptionalSafe(metadata.paintingSchool());
        total += requireOptionalSafe(metadata.style());
        total += requireOptionalSafe(metadata.composition());
        total += requireOptionalSafe(metadata.artisticConception());
        total += requireOptionalSafe(metadata.generatedText());
        total += requireOptionalSafe(metadata.musicSceneDescription());
        if (total > properties.getMaxTextChars()) {
            throw invalid("Painting metadata exceeds the configured total character limit");
        }
    }

    public void requireSafeText(String value, String message) {
        if (value == null) {
            throw invalid(message);
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\u0000'
                    || (Character.isISOControl(character)
                    && character != '\n' && character != '\r' && character != '\t')) {
                throw invalid(message);
            }
        }
    }

    private int requireOptionalSafe(String value) {
        if (value == null) {
            return 0;
        }
        if (value.length() > properties.getMaxTextChars()) {
            throw invalid("Painting metadata exceeds the configured character limit");
        }
        requireSafeText(value, "Painting metadata contains unsupported control characters");
        return value.length();
    }

    private void requireOptionalPaintingId(String value) {
        if (value == null) {
            return;
        }
        requireSafeText(value, "Painting identifier contains unsupported control characters");
        try {
            if (!java.util.UUID.fromString(value).toString().equals(value.toLowerCase(java.util.Locale.ROOT))) {
                throw invalid("Painting identifier is not a public UUID");
            }
        } catch (IllegalArgumentException exception) {
            throw invalid("Painting identifier is not a public UUID");
        }
    }

    private ProviderExecutionException invalid(String message) {
        return new ProviderExecutionException(
                ProviderErrorCategory.PROVIDER_REJECTED,
                message);
    }
}
