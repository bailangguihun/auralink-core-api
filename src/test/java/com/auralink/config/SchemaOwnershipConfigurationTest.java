package com.auralink.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class SchemaOwnershipConfigurationTest {

    @Test
    void safeDefaultsDisableSchemaMutationAndEnableForeignKeysPerConnection() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("AURALINK_JWT_SECRET=test-only-schema-ownership-secret")
                .withUserConfiguration(EmptyConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"))
                            .isEqualTo("none");
                    assertThat(context.getEnvironment().getProperty("spring.flyway.enabled", Boolean.class))
                            .isFalse();
                    assertThat(context.getEnvironment().getProperty("spring.flyway.baseline-on-migrate", Boolean.class))
                            .isFalse();
                    assertThat(context.getEnvironment().getProperty("spring.flyway.baseline-version"))
                            .isEqualTo("1");
                    assertThat(context.getEnvironment().getProperty("spring.flyway.locations"))
                            .isEqualTo("classpath:db/migration");
                    assertThat(context.getEnvironment().getProperty(
                            "spring.datasource.hikari.connection-init-sql"))
                            .isEqualTo("PRAGMA foreign_keys=ON");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfiguration {
    }
}
