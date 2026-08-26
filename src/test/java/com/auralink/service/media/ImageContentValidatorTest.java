package com.auralink.service.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.auralink.config.properties.MediaAssetProperties;
import com.auralink.service.media.ImageContentValidator.ValidatedImage;

class ImageContentValidatorTest {

    @TempDir
    Path temporaryDirectory;

    private MediaAssetProperties properties;
    private ImageContentValidator validator;

    @BeforeEach
    void setUp() {
        properties = new MediaAssetProperties();
        validator = new ImageContentValidator(properties);
    }

    @Test
    void acceptsFullyDecodedJpegAndPngWhoseMetadataMatches() throws Exception {
        Path jpeg = image("valid.jpg", "JPEG", 3, 2);
        Path png = image("valid.png", "PNG", 2, 3);

        ValidatedImage jpegResult = validator.validateUpload(jpeg, "image/jpeg", "photo.jpeg");
        ValidatedImage pngResult = validator.validateUpload(png, "image/png; charset=binary", "ink.png");

        assertThat(jpegResult).isEqualTo(new ValidatedImage("JPEG", "image/jpeg", "jpg", 3, 2));
        assertThat(pngResult).isEqualTo(new ValidatedImage("PNG", "image/png", "png", 2, 3));
    }

    @Test
    void actualBytesRemainAuthoritativeButWrongDeclaredMetadataIsRejected() throws Exception {
        Path jpeg = image("actual.jpg", "JPEG", 2, 2);

        assertThatThrownBy(() -> validator.validateUpload(jpeg, "image/png", "actual.jpg"))
                .isInstanceOf(InvalidImageContentException.class)
                .hasMessageContaining("MIME");
        assertThatThrownBy(() -> validator.validateUpload(jpeg, "image/jpeg", "actual.png"))
                .isInstanceOf(InvalidImageContentException.class)
                .hasMessageContaining("extension");
        assertThatThrownBy(() -> validator.validateUpload(
                jpeg, "application/octet-stream", "actual.jpg"))
                .isInstanceOf(InvalidImageContentException.class)
                .hasMessageContaining("MIME");
    }

    @Test
    void rejectsRenamedTextAndAppendedPayloadPolyglots() throws Exception {
        Path fake = temporaryDirectory.resolve("fake.jpg");
        Files.writeString(fake, "this is not a jpeg image");
        assertThatThrownBy(() -> validator.validateUpload(fake, "image/jpeg", "fake.jpg"))
                .isInstanceOf(InvalidImageContentException.class);

        Path appendedJpeg = image("appended.jpg", "JPEG", 2, 2);
        Files.write(appendedJpeg, new byte[] {0x41, 0x42}, StandardOpenOption.APPEND);
        assertThatThrownBy(() -> validator.validateUpload(appendedJpeg, "image/jpeg", "appended.jpg"))
                .isInstanceOf(InvalidImageContentException.class)
                .hasMessageContaining("terminal marker");

        Path appendedPng = image("appended.png", "PNG", 2, 2);
        Files.write(appendedPng, new byte[] {0x41}, StandardOpenOption.APPEND);
        assertThatThrownBy(() -> validator.validateUpload(appendedPng, "image/png", "appended.png"))
                .isInstanceOf(InvalidImageContentException.class)
                .hasMessageContaining("terminal marker");
    }

    @Test
    void rejectsPixelCountBeforePermittingLargeRaster() throws Exception {
        properties.setMaxImagePixels(5);
        Path png = image("too-many-pixels.png", "PNG", 3, 2);

        assertThatThrownBy(() -> validator.validateUpload(png, "image/png", "large.png"))
                .isInstanceOf(InvalidImageContentException.class)
                .hasMessageContaining("pixel count");
    }

    private Path image(String filename, String format, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.BLACK.getRGB());
        Path path = temporaryDirectory.resolve(filename);
        assertThat(ImageIO.write(image, format, path.toFile())).isTrue();
        return path;
    }
}
