package com.auralink.creation.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.auralink.provider.qwen.QwenResponseShapeDiagnostic;
import com.auralink.provider.qwen.QwenResponseValidationCode;
import com.auralink.provider.qwen.QwenResponseValidationDiagnostic;
import com.auralink.provider.qwen.QwenResponseValidationStage;
import com.auralink.provider.qwen.QwenSafeValueType;

class ProviderExecutionExceptionTest {

    @Test
    void carriesOptionalTypedSafeDiagnosticWithoutCauseOrProviderContent() {
        QwenResponseShapeDiagnostic shape = QwenResponseShapeDiagnostic.builder()
                .contentPresent(true)
                .contentType(QwenSafeValueType.STRING)
                .contentLength(123)
                .lineCount(5)
                .build();
        QwenResponseValidationDiagnostic diagnostic = new QwenResponseValidationDiagnostic(
                QwenResponseValidationStage.POEM_SCHEMA,
                QwenResponseValidationCode.QWEN_LINES_COUNT_INVALID,
                shape);

        ProviderExecutionException failure = ProviderExecutionException.fromSafeDiagnostic(
                ProviderErrorCategory.PROVIDER_INVALID_RESPONSE,
                "Qwen response failed strict validation",
                diagnostic);

        assertThat(failure.category()).isEqualTo(ProviderErrorCategory.PROVIDER_INVALID_RESPONSE);
        assertThat(failure.safeDiagnostic()).isSameAs(diagnostic);
        assertThat(failure.providerHttpStatus()).isNull();
        assertThat(failure.providerErrorCode()).isNull();
        assertThat(failure.safeRequestId()).isNull();
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getMessage() + failure.safeDiagnostic())
                .doesNotContain("PRIVATE_POEM_TEXT", "PRIVATE_RAW_RESPONSE");
    }

    @Test
    void keepsExistingHttpDiagnosticsUnchangedAndSeparate() {
        ProviderExecutionException failure = ProviderExecutionException.fromProviderResponse(
                ProviderErrorCategory.PROVIDER_REJECTED,
                "Provider rejected the request",
                400,
                "InvalidParameter",
                "sha256:0123456789abcdef0123456789abcdef");

        assertThat(failure.providerHttpStatus()).isEqualTo(400);
        assertThat(failure.providerErrorCode()).isEqualTo("InvalidParameter");
        assertThat(failure.safeRequestId())
                .isEqualTo("sha256:0123456789abcdef0123456789abcdef");
        assertThat(failure.safeDiagnostic()).isNull();
    }

    @Test
    void responseShapeRejectsNegativeValuesAndSaturatesOversizedValues() {
        assertThatThrownBy(() -> QwenResponseShapeDiagnostic.builder().lineCount(-1))
                .isInstanceOf(IllegalArgumentException.class);

        QwenResponseShapeDiagnostic shape = QwenResponseShapeDiagnostic.builder()
                .contentLength(Long.MAX_VALUE)
                .build();
        assertThat(shape.contentLength())
                .isEqualTo(QwenResponseShapeDiagnostic.MAX_SAFE_COUNT_OR_LENGTH);
    }
}
