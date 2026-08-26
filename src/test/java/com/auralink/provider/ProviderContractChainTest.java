package com.auralink.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProviderContractChainTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void exercisesAllFiveEnabledFlowsWithExactLocalMockCallCounts() throws Exception {
        PackagedProviderContractHarness.ContractCounts counts =
                PackagedProviderContractHarness.run(temporaryDirectory);

        assertThat(counts).isEqualTo(
                new PackagedProviderContractHarness.ContractCounts(1, 1, 1, 1, 1, 1));
    }
}
