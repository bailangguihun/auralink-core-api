package com.auralink.ops.round51;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class Round51ActivationCommandTest {

    @Test
    void refusesNonServerLocalRootBeforeStartingSpring() {
        Path temporaryRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();

        assertThat(temporaryRoot).isNotEqualTo(Round51ActivationCommand.SERVER_LOCAL_ROOT);
        assertThatThrownBy(() -> Round51ActivationCommand.verifyServerLocalProject(temporaryRoot, temporaryRoot))
                .isInstanceOfAny(Round51ActivationException.class, java.io.IOException.class);
    }

    @Test
    void invocationWithoutExactProjectRootIsRejectedBeforeSpringStarts() {
        assertThat(Round51ActivationCommand.run(new String[0])).isEqualTo(2);
        assertThat(Round51ActivationCommand.run(new String[] {"--project-root=/tmp"})).isEqualTo(2);
    }
}
