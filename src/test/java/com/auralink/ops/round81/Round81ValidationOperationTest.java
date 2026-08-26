package com.auralink.ops.round81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class Round81ValidationOperationTest {

    @ParameterizedTest
    @CsvSource({
        "text-to-painting,VALIDATE_ONE_LIVE_TEXT_TO_PAINTING,1,0,0",
        "image-to-painting,VALIDATE_ONE_LIVE_IMAGE_TO_PAINTING,1,0,0",
        "poem-to-painting,VALIDATE_ONE_LIVE_POEM_TO_PAINTING,1,1,0",
        "painting-to-poem,VALIDATE_ONE_LIVE_PAINTING_TO_POEM,0,1,0",
        "painting-to-music,VALIDATE_ONE_LIVE_PAINTING_TO_MUSIC,0,0,1"
    })
    void freezesOperationSpecificConfirmationAndCallBudget(
            String token,
            String confirmation,
            int seedream,
            int qwen,
            int vmm) {
        Round81ValidationOperation operation = Round81ValidationOperation.fromToken(token);

        assertThat(operation.confirmation()).isEqualTo(confirmation);
        assertThat(operation.expectedCalls(Round81ProviderFamily.SEEDREAM)).isEqualTo(seedream);
        assertThat(operation.expectedCalls(Round81ProviderFamily.QWEN)).isEqualTo(qwen);
        assertThat(operation.expectedCalls(Round81ProviderFamily.VMM)).isEqualTo(vmm);
    }

    @ParameterizedTest
    @ValueSource(strings = {"all", "painting-to-video", "TEXT_TO_PAINTING", "", "unknown"})
    void rejectsUnsupportedOrBatchOperation(String token) {
        assertThatThrownBy(() -> Round81ValidationOperation.fromToken(token))
                .isInstanceOf(Round81ValidationException.class)
                .hasMessageNotContaining("provider");
    }
}
