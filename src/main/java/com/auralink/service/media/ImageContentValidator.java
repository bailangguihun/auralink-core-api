package com.auralink.service.media;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.springframework.stereotype.Component;

import com.auralink.config.properties.MediaAssetProperties;

import lombok.RequiredArgsConstructor;

/**
 * Validates the actual bytes of images accepted by the 2.0 resource layer.
 *
 * <p>Only JPEG and PNG are currently accepted. Dimensions are obtained before
 * a full decode so an excessive pixel count is rejected before allocating the
 * decoded raster. File signatures and terminal markers are checked in addition
 * to ImageIO decoding to reject renamed text and simple appended-payload
 * polyglots.</p>
 */
@Component
@RequiredArgsConstructor
public class ImageContentValidator {

    private static final byte[] PNG_SIGNATURE = new byte[] {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };
    private static final byte[] PNG_IEND = new byte[] {
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e, 0x44,
            (byte) 0xae, 0x42, 0x60, (byte) 0x82
    };

    private static final Set<String> JPEG_EXTENSIONS = Set.of("jpg", "jpeg");
    private static final Set<String> PNG_EXTENSIONS = Set.of("png");

    private final MediaAssetProperties properties;

    /** Validate a caller upload, including declared MIME and filename extension. */
    public ValidatedImage validateUpload(Path path, String declaredMimeType, String originalFilename) {
        ValidatedImage image = validateTrustedImage(path);
        validateDeclaredMime(declaredMimeType, image.mimeType());
        validateFilenameExtension(originalFilename, image.formatName());
        return image;
    }

    /** Validate trusted/internal image bytes without relying on external metadata. */
    public ValidatedImage validateTrustedImage(Path path) {
        requireRegularFile(path);
        DetectedFormat signatureFormat = detectSignatureAndTerminalMarker(path);

        try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
            if (input == null) {
                throw new InvalidImageContentException("Image bytes cannot be decoded");
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new InvalidImageContentException("Unsupported or invalid image bytes");
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                DetectedFormat readerFormat = DetectedFormat.fromImageIoName(reader.getFormatName());
                if (readerFormat != signatureFormat) {
                    throw new InvalidImageContentException("Image signature and decoder format disagree");
                }

                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                validateDimensions(width, height);

                BufferedImage decoded = reader.read(0);
                if (decoded == null
                        || decoded.getWidth() != width
                        || decoded.getHeight() != height) {
                    throw new InvalidImageContentException("Image could not be fully decoded");
                }

                return new ValidatedImage(
                        signatureFormat.formatName,
                        signatureFormat.mimeType,
                        signatureFormat.extension,
                        width,
                        height);
            } finally {
                reader.dispose();
            }
        } catch (InvalidImageContentException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new InvalidImageContentException("Image could not be safely decoded", exception);
        }
    }

    private void requireRegularFile(Path path) {
        if (path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new InvalidImageContentException("Image resource is not a regular file");
        }
    }

    private DetectedFormat detectSignatureAndTerminalMarker(Path path) {
        try {
            long size = Files.size(path);
            if (size < PNG_IEND.length) {
                throw new InvalidImageContentException("Image data is incomplete");
            }

            byte[] prefix = readAt(path, 0, PNG_SIGNATURE.length);
            if (startsWith(prefix, PNG_SIGNATURE)) {
                byte[] suffix = readAt(path, size - PNG_IEND.length, PNG_IEND.length);
                if (!startsWith(suffix, PNG_IEND)) {
                    throw new InvalidImageContentException("PNG terminal marker is missing");
                }
                return DetectedFormat.PNG;
            }

            if ((prefix[0] & 0xff) == 0xff
                    && (prefix[1] & 0xff) == 0xd8
                    && (prefix[2] & 0xff) == 0xff) {
                byte[] suffix = readAt(path, size - 2, 2);
                if ((suffix[0] & 0xff) != 0xff || (suffix[1] & 0xff) != 0xd9) {
                    throw new InvalidImageContentException("JPEG terminal marker is missing");
                }
                return DetectedFormat.JPEG;
            }

            throw new InvalidImageContentException("Only JPEG and PNG image bytes are accepted");
        } catch (InvalidImageContentException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new InvalidImageContentException("Image bytes could not be inspected", exception);
        }
    }

    private byte[] readAt(Path path, long position, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            channel.position(position);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    throw new InvalidImageContentException("Image data is incomplete");
                }
            }
        }
        return buffer.array();
    }

    private boolean startsWith(byte[] bytes, byte[] expected) {
        if (bytes.length < expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (bytes[index] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private void validateDimensions(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new InvalidImageContentException("Image dimensions must be positive");
        }

        final long pixels;
        try {
            pixels = Math.multiplyExact((long) width, (long) height);
        } catch (ArithmeticException exception) {
            throw new InvalidImageContentException("Image dimensions exceed the configured limit", exception);
        }
        if (pixels > properties.getMaxImagePixels()) {
            throw new InvalidImageContentException("Image pixel count exceeds the configured limit");
        }
    }

    private void validateDeclaredMime(String declaredMimeType, String detectedMimeType) {
        if (declaredMimeType == null || declaredMimeType.isBlank()) {
            throw new InvalidImageContentException("Image MIME type is required");
        }
        String normalized = declaredMimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!detectedMimeType.equals(normalized)) {
            throw new InvalidImageContentException("Declared MIME type does not match image bytes");
        }
    }

    private void validateFilenameExtension(String originalFilename, String detectedFormat) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new InvalidImageContentException("Image filename must include a supported extension");
        }

        String basename = originalFilename.replace('\\', '/');
        basename = basename.substring(basename.lastIndexOf('/') + 1);
        int dot = basename.lastIndexOf('.');
        if (dot < 0 || dot == basename.length() - 1) {
            throw new InvalidImageContentException("Image filename must include a supported extension");
        }
        String extension = basename.substring(dot + 1).toLowerCase(Locale.ROOT);
        Set<String> expected = "JPEG".equals(detectedFormat) ? JPEG_EXTENSIONS : PNG_EXTENSIONS;
        if (!expected.contains(extension)) {
            throw new InvalidImageContentException("Filename extension does not match image bytes");
        }
    }

    public record ValidatedImage(
            String formatName,
            String mimeType,
            String fileExtension,
            int width,
            int height) {
    }

    private enum DetectedFormat {
        JPEG("JPEG", "image/jpeg", "jpg"),
        PNG("PNG", "image/png", "png");

        private final String formatName;
        private final String mimeType;
        private final String extension;

        DetectedFormat(String formatName, String mimeType, String extension) {
            this.formatName = formatName;
            this.mimeType = mimeType;
            this.extension = extension;
        }

        private static DetectedFormat fromImageIoName(String name) {
            if (name == null) {
                throw new InvalidImageContentException("Image decoder did not identify a format");
            }
            return switch (name.trim().toUpperCase(Locale.ROOT)) {
                case "JPEG", "JPG" -> JPEG;
                case "PNG" -> PNG;
                default -> throw new InvalidImageContentException("Only JPEG and PNG are accepted");
            };
        }
    }
}
