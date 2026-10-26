package com.auralink.ops.round51;

import com.auralink.Application;
import com.auralink.config.CreationClockConfiguration;
import com.auralink.assistant.AssistantExecutionRuntime;
import com.auralink.assistant.AssistantStore;
import com.auralink.creation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Clock;
import java.util.Map;
import org.springframework.boot.SpringApplication;

/** External test launcher, never packaged as an application class or bean. */
public final class FullApplicationStartupProbe {
   public static void main(String[] args) throws Exception {
      Path root = Path.of("").toAbsolutePath().normalize();
      Round51MaintenanceEnvironmentPostProcessor.installAutomaticPathsForCurrentThread(
         root.resolve(".round51-maintenance"), root.resolve(".round51-startup-gate"), root);
      var context = SpringApplication.run(Boolean.getBoolean("fixture.creation-first")
         ? new Class<?>[]{CreationClockConfiguration.class, Application.class} : new Class<?>[]{Application.class}, args);
      var env = context.getEnvironment();
      if (!"false".equals(env.getProperty("spring.main.lazy-initialization"))) throw new AssertionError("lazy initialization forbidden");
      for (String name : new String[]{"assistant", "assistant.execution", "creations", "creation-providers", "workflows", "vmm-managed"}) {
         if (!"true".equals(env.getProperty("auralink." + name + ".enabled"))) throw new AssertionError("feature not enabled: " + name);
      }
      var store = context.getBean(AssistantStore.class);
      var execution = context.getBean(AssistantExecutionRuntime.class);
      if (!execution.available() || !"READY".equals(execution.state())) throw new AssertionError("Assistant execution not ready");
      var gate = context.getBean(CreationRecoveryGate.class);
      long deadline = System.nanoTime() + 10_000_000_000L;
      while (!gate.isOpen() && System.nanoTime() < deadline) Thread.sleep(50);
      if (!gate.isOpen()) throw new AssertionError("Creation recovery did not open dispatcher");
      for (Class<?> type : new Class<?>[]{CreationResultPersistenceService.class, CreationSubmissionService.class,
            CreationRecoveryTransactionService.class, CreationLeaseHeartbeatService.class, CreationExecutionLifecycle.class}) {
         context.getBean(type); // Real eager Spring wiring, no fixture substitute.
      }
      if (!context.getBean(CreationExecutionLifecycle.class).isRunning()) throw new AssertionError("Creation lifecycle not running");
      new ObjectMapper().writeValue(root.resolve("SPRING_READY.json").toFile(), Map.of(
         "status", "READY", "assistantStore", store.getClass().getName(), "assistantExecution", execution.state(),
         "creationRecoveryGate", gate.isOpen(), "clockBeans", context.getBeanNamesForType(Clock.class),
         "beanCount", context.getBeanDefinitionCount(), "lazyInitialization", false));
   }
}
