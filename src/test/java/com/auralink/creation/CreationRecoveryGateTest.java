package com.auralink.creation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CreationRecoveryGateTest {

    @Test
    void startsClosedAndNeverReopensAfterShutdownBegins() {
        CreationRecoveryGate gate = new CreationRecoveryGate();
        assertThat(gate.isOpen()).isFalse();
        gate.openAfterRecovery();
        assertThat(gate.isOpen()).isTrue();
        gate.beginShutdown();
        gate.openAfterRecovery();
        assertThat(gate.isOpen()).isFalse();
        assertThat(gate.tryBeginProviderCall()).isFalse();
    }
}
