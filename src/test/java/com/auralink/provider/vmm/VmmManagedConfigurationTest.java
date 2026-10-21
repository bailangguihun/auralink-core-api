package com.auralink.provider.vmm;

import static org.assertj.core.api.Assertions.*;
import com.auralink.config.properties.VmmManagedProperties;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.*;
import org.springframework.core.io.ClassPathResource;

class VmmManagedConfigurationTest {
   @Test void shippedDefaultsBindDisabledWithoutPrivateDependencies() throws Exception {
      var environment = new StandardEnvironment();
      environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
      environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
      for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
         environment.getPropertySources().addLast(source);
      }
      var properties = Binder.get(environment).bind("auralink.vmm-managed", VmmManagedProperties.class).get();
      assertThat(properties.isEnabled()).isFalse();
      assertThat(properties.getTokenFile()).isEmpty();
      assertThat(properties.getAcceptanceOwnerId()).isNull();
      assertThat(properties.getAcceptanceSourcePaintingId()).isNull();
      assertThat(properties.getAcceptanceDurationSeconds()).isNull();
   }
}
