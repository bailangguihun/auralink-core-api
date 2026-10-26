package com.auralink.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.auralink.assistant.AssistantConfiguration;
import com.auralink.config.properties.CreationExecutionProperties;
import com.auralink.creation.CreationResultPersistenceService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

/** Exercise Spring's real constructor resolution; no DB, provider, or submission. */
class CreationClockConfigurationTest {
   private ApplicationContextRunner context(Class<?>... configurations) {
      return new ApplicationContextRunner()
         .withPropertyValues("auralink.assistant.enabled=true")
         .withInitializer(context -> {
            var registry = (org.springframework.context.support.GenericApplicationContext) context;
            // Keep the actual AssistantConfiguration and its clock bean. Its
            // storage beans remain lazy, so this wiring test cannot open a DB.
            context.addBeanFactoryPostProcessor(factory -> {
               for (String name : factory.getBeanDefinitionNames()) {
                  factory.getBeanDefinition(name).setLazyInit(true);
               }
            });
            for (Class<?> type : CreationResultPersistenceService.class.getConstructors()[0].getParameterTypes()) {
               if (type == CreationExecutionProperties.class) {
                  registry.registerBean(CreationExecutionProperties.class);
               } else if (type != Clock.class) {
                  registerMock(registry, type);
               }
            }
            registry.registerBean(CreationResultPersistenceService.class);
         })
         .withUserConfiguration(configurations);
   }

   private static <T> void registerMock(org.springframework.context.support.GenericApplicationContext context,
                                      Class<T> type) {
      context.registerBean(type, () -> mock(type));
   }

   @Test
   void creationRegisteredBeforeAssistantHasUnambiguousClock() {
      context(CreationClockConfiguration.class, AssistantConfiguration.class).run(context -> {
         assertThat(context).hasNotFailed();
         var service = context.getBean(CreationResultPersistenceService.class);
         assertThat(ReflectionTestUtils.getField(service, "clock")).isSameAs(context.getBean("creationClock"));
         assertThat(context.getBeanNamesForType(Clock.class)).containsExactlyInAnyOrder("creationClock", "assistantClock");
         assertThat(context.getBean("assistantClock", Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
      });
   }

   @Test
   void assistantRegisteredBeforeCreationPreservesExistingFallback() {
      context(AssistantConfiguration.class, CreationClockConfiguration.class).run(context -> {
         assertThat(context).hasNotFailed();
         var service = context.getBean(CreationResultPersistenceService.class);
         assertThat(context).doesNotHaveBean("creationClock");
         assertThat(ReflectionTestUtils.getField(service, "clock")).isSameAs(context.getBean("assistantClock"));
      });
   }

   @Test
   void creationWithoutAssistantUsesDefaultUtcClock() {
      context(CreationClockConfiguration.class).run(context -> {
         var service = context.getBean(CreationResultPersistenceService.class);
         var clock = (Clock) ReflectionTestUtils.getField(service, "clock");
         assertThat(clock).isSameAs(context.getBean("creationClock"));
         assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
      });
   }

   @Test
   void existingCustomClockStillPreventsDefaultRegistration() {
      Clock clock = Clock.fixed(Instant.parse("2026-10-26T07:40:58Z"), ZoneOffset.UTC);
      context(CreationClockConfiguration.class).withBean("customClock", Clock.class, () -> clock).run(context -> {
         var service = context.getBean(CreationResultPersistenceService.class);
         assertThat(context).doesNotHaveBean("creationClock");
         assertThat(ReflectionTestUtils.getField(service, "clock")).isSameAs(clock);
      });
   }
}
