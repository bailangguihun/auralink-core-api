package com.auralink.provider.qwen;

import java.util.Objects;

import com.auralink.creation.provider.ProviderSafeDiagnostic;

/** Typed Qwen diagnostic containing no request, response, prompt, or generated text. */
public record QwenResponseValidationDiagnostic(
        QwenResponseValidationStage validationStage,
        QwenResponseValidationCode validationCode,
        QwenResponseShapeDiagnostic responseShape)
        implements ProviderSafeDiagnostic<
                QwenResponseValidationStage,
                QwenResponseValidationCode,
                QwenResponseShapeDiagnostic> {

    public QwenResponseValidationDiagnostic {
        validationStage = Objects.requireNonNull(validationStage, "validationStage");
        validationCode = Objects.requireNonNull(validationCode, "validationCode");
        responseShape = Objects.requireNonNull(responseShape, "responseShape");
    }
}
