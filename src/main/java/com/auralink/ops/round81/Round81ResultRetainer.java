package com.auralink.ops.round81;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.creation.provider.ProviderBinaryOutput;
import com.auralink.creation.provider.ProviderExecutionResult;
import com.auralink.creation.provider.ProviderTextOutput;
import com.auralink.provider.artifact.AudioOutputValidator;
import com.auralink.service.media.ImageContentValidator;
import com.auralink.service.media.ImageContentValidator.ValidatedImage;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Copies exactly one validated output into the private operator-review run. */
final class Round81ResultRetainer {

    private final ObjectMapper objectMapper;
    private final CreationProviderProperties properties;
    private final ImageContentValidator imageValidator;
    private final AudioOutputValidator audioValidator;

    Round81ResultRetainer(
            ObjectMapper objectMapper,
            CreationProviderProperties properties,
            ImageContentValidator imageValidator,
            AudioOutputValidator audioValidator) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.imageValidator = imageValidator;
        this.audioValidator = audioValidator;
    }

    Round81RetainedResult retain(
            Round81ValidationOperation operation,
            ProviderExecutionResult result,
            Path runDirectory) {
        requireResultContract(operation, result);
        if (result.output() instanceof ProviderBinaryOutput binary) {
            return retainBinary(operation, binary, runDirectory);
        }
        if (result.output() instanceof ProviderTextOutput text) {
            return retainPoem(operation, text, runDirectory);
        }
        throw new Round81ValidationException(
                "PROVIDER_OUTPUT_CONTRACT_INVALID", "Provider output type is not supported by validation");
    }

    private Round81RetainedResult retainBinary(
            Round81ValidationOperation operation,
            ProviderBinaryOutput output,
            Path runDirectory) {
        String extension = output.artifact().fileExtension();
        String fileName = operation.outputModality() == com.auralink.workflow.WorkflowModality.AUDIO
                ? "validated-result.wav"
                : "validated-result." + extension;
        long maximum = operation.outputModality() == com.auralink.workflow.WorkflowModality.AUDIO
                ? properties.getMaxAudioOutputBytes()
                : properties.getMaxImageOutputBytes();
        Path retained;
        try (InputStream input = output.artifact().openStream()) {
            retained = Round81PrivateFiles.copyArtifact(
                    runDirectory, fileName, input, maximum, output.sha256());
        } catch (Round81ValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new Round81ValidationException(
                    "PRIVATE_RESULT_WRITE_FAILED", "Validated provider output could not be retained", exception);
        }

        try {
            Integer width = null;
            Integer height = null;
            String reviewState;
            if (operation.outputModality() == com.auralink.workflow.WorkflowModality.AUDIO) {
                audioValidator.validateWave(retained, "audio/wav", maximum);
                reviewState = "OPERATOR_AUDIO_REVIEW_REQUIRED";
            } else {
                ValidatedImage image = imageValidator.validateTrustedImage(retained);
                if (!image.mimeType().equals(output.mimeType())
                        || output.width() == null || output.height() == null
                        || image.width() != output.width().intValue()
                        || image.height() != output.height().intValue()) {
                    throw new Round81ValidationException(
                            "PRIVATE_RESULT_VALIDATION_FAILED", "Retained provider image metadata changed");
                }
                width = image.width();
                height = image.height();
                reviewState = "OPERATOR_REVIEW_REQUIRED";
            }
            if (!Round81PrivateFiles.sha256(retained).equals(output.sha256())) {
                throw new Round81ValidationException(
                        "PRIVATE_RESULT_CHECKSUM_MISMATCH", "Retained provider output checksum changed");
            }
            if (Files.size(retained) != output.byteLength()) {
                throw new Round81ValidationException(
                        "PRIVATE_RESULT_VALIDATION_FAILED", "Retained provider output length changed");
            }
            Round81RetainedResult retainedResult = new Round81RetainedResult(
                    fileName,
                    output.mimeType(),
                    output.byteLength(),
                    output.sha256(),
                    width,
                    height,
                    "STRUCTURALLY_VALID",
                    reviewState);
            Round81PrivateFiles.writeJson(
                    objectMapper, runDirectory, "result-metadata.json", retainedResult);
            return retainedResult;
        } catch (Round81ValidationException exception) {
            Round81PrivateFiles.deleteDirectFile(runDirectory, fileName);
            throw exception;
        } catch (RuntimeException exception) {
            Round81PrivateFiles.deleteDirectFile(runDirectory, fileName);
            throw new Round81ValidationException(
                    "PRIVATE_RESULT_VALIDATION_FAILED", "Retained provider output failed validation", exception);
        } catch (java.io.IOException exception) {
            Round81PrivateFiles.deleteDirectFile(runDirectory, fileName);
            throw new Round81ValidationException(
                    "PRIVATE_RESULT_VALIDATION_FAILED", "Retained provider output could not be inspected", exception);
        }
    }

    private Round81RetainedResult retainPoem(
            Round81ValidationOperation operation,
            ProviderTextOutput output,
            Path runDirectory) {
        if (operation != Round81ValidationOperation.PAINTING_TO_POEM
                || !"1".equals(output.schemaVersion())
                || output.lines() == null || output.lines().size() != 4
                || output.lines().stream().anyMatch(line -> line == null || line.isBlank())
                || output.text() == null || output.text().isBlank()) {
            throw new Round81ValidationException(
                    "PROVIDER_OUTPUT_CONTRACT_INVALID", "Validated poem output is structurally invalid");
        }
        Map<String, Object> poem = new LinkedHashMap<>();
        poem.put("schemaVersion", output.schemaVersion());
        poem.put("title", output.title());
        poem.put("lines", output.lines());
        poem.put("text", output.text());
        try {
            Path file = Round81PrivateFiles.writeJson(objectMapper, runDirectory, "validated-poem.json", poem);
            String sha256 = Round81PrivateFiles.sha256(file);
            long bytes = Files.size(file);
            Round81RetainedResult retained = new Round81RetainedResult(
                    "validated-poem.json",
                    "application/json",
                    bytes,
                    sha256,
                    null,
                    null,
                    "STRUCTURALLY_VALID",
                    "OPERATOR_REVIEW_REQUIRED");
            Round81PrivateFiles.writeJson(objectMapper, runDirectory, "result-metadata.json", retained);
            return retained;
        } catch (Round81ValidationException exception) {
            Round81PrivateFiles.deleteDirectFile(runDirectory, "validated-poem.json");
            throw exception;
        } catch (RuntimeException exception) {
            Round81PrivateFiles.deleteDirectFile(runDirectory, "validated-poem.json");
            throw new Round81ValidationException(
                    "PRIVATE_RESULT_VALIDATION_FAILED", "Retained poem failed validation", exception);
        } catch (java.io.IOException exception) {
            Round81PrivateFiles.deleteDirectFile(runDirectory, "validated-poem.json");
            throw new Round81ValidationException(
                    "PRIVATE_RESULT_VALIDATION_FAILED", "Retained poem could not be inspected", exception);
        }
    }

    private void requireResultContract(
            Round81ValidationOperation operation,
            ProviderExecutionResult result) {
        if (result == null
                || result.operation() != operation.workflowOperation()
                || !operation.providerCode().equals(result.providerCode())
                || result.outputModality() != operation.outputModality()) {
            throw new Round81ValidationException(
                    "PROVIDER_OUTPUT_CONTRACT_INVALID", "Provider result does not match the reviewed operation");
        }
    }
}
