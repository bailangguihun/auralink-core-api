package com.auralink.provider.vmm;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.config.properties.ProviderProperties;
import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;
import com.auralink.creation.provider.ProviderReadiness;
import com.auralink.creation.provider.ProviderReadinessState;
import com.auralink.provider.validation.CreationProviderConfigurationChecks;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class VmmEndpointPolicy implements VmmEndpointResolver {
   private final CreationProviderProperties creationProperties;
   private final ProviderProperties.Provider provider;
   private final VmmManagedGate managedGate;

   public VmmEndpointPolicy(CreationProviderProperties creationProperties, ProviderProperties providerProperties) {
      this(creationProperties, providerProperties, null);
   }

   @Autowired
   public VmmEndpointPolicy(CreationProviderProperties creationProperties, ProviderProperties providerProperties, VmmManagedGate managedGate) {
      this.managedGate = managedGate;
      this.creationProperties = creationProperties;
      this.provider = providerProperties.getPaintingMusic();
   }

   public ProviderReadiness readiness() {
      if (!this.creationProperties.isEnabled()) {
         return this.readiness(ProviderReadinessState.FEATURE_DISABLED, "CREATION_PROVIDERS_DISABLED");
      }

      if (!this.isBlank(this.provider.getBaseUrl()) && !this.isBlank(this.provider.getOutputRoot())) {
         try {
            this.validateBase(this.provider.getBaseUrl());
            CreationProviderConfigurationChecks.requireVmm(this.creationProperties);
            this.validateExistingOutputRoot(this.validateConfiguredOutputRoot(this.provider.getOutputRoot()));
            if (managedGate != null && managedGate.enabled()) return managedGate.readiness(resolveGenerationEndpoint());
            return this.readiness(ProviderReadinessState.READY_FOR_CONTROLLED_EXECUTION, "VMM_CONFIGURATION_VALIDATED_NO_AUTOMATIC_HEALTH_CHECK");
         } catch (ProviderExecutionException exception) {
            return this.readiness(ProviderReadinessState.CONFIGURATION_INVALID, "PROVIDER_CONFIGURATION_INVALID");
         }
      } else {
         return this.readiness(ProviderReadinessState.CONFIGURATION_MISSING, "REQUIRED_CONFIGURATION_MISSING");
      }
   }

   @Override
   public URI resolveGenerationEndpoint() {
      this.requireFeatureEnabled();
      this.requirePresentConfiguration();
      URI base = this.validateBase(this.provider.getBaseUrl());
      CreationProviderConfigurationChecks.requireVmm(this.creationProperties);
      return URI.create(base.toString() + "/api/generate_with_image");
   }

   @Override
   public Path resolveOutputRoot() {
      this.requireFeatureEnabled();
      this.requirePresentConfiguration();
      CreationProviderConfigurationChecks.requireVmm(this.creationProperties);
      return this.validateExistingOutputRoot(this.validateConfiguredOutputRoot(this.provider.getOutputRoot()));
   }

   private URI validateBase(String rawBase) {
      URI uri;
      try {
         uri = new URI(rawBase.trim());
      } catch (URISyntaxException | NullPointerException exception) {
         throw this.invalid("VMM service URL is invalid", exception);
      }

      String scheme = uri.getScheme();
      String host = uri.getHost();
      String path = uri.getPath();
      if (scheme != null
         && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
         && host != null
         && this.isPrivateOrLoopbackLiteral(host)
         && uri.getUserInfo() == null
         && uri.getQuery() == null
         && uri.getFragment() == null
         && uri.getPort() != 0
         && uri.getPort() <= 65535
         && (path == null || path.isEmpty() || "/".equals(path))) {
         String normalizedHost = host.contains(":") ? "[" + host + "]" : host.toLowerCase(Locale.ROOT);
         String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
         return URI.create(scheme.toLowerCase(Locale.ROOT) + "://" + normalizedHost + port);
      } else {
         throw this.invalid("VMM service URL must be an internal loopback/private root", null);
      }
   }

   private Path validateConfiguredOutputRoot(String rawRoot) {
      try {
         Path root = Path.of(rawRoot.trim());
         if (!root.isAbsolute()) {
            throw this.invalid("VMM output root must be absolute", null);
         } else {
            return root.normalize();
         }
      } catch (RuntimeException exception) {
         if (exception instanceof ProviderExecutionException providerException) {
            throw providerException;
         } else {
            throw this.invalid("VMM output root is invalid", exception);
         }
      }
   }

   private Path validateExistingOutputRoot(Path root) {
      try {
         if (!Files.isSymbolicLink(root) && Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && root.toRealPath().equals(root)) {
            return root;
         } else {
            throw this.invalid("VMM output root is unavailable or unsafe", null);
         }
      } catch (IOException exception) {
         throw this.invalid("VMM output root is unavailable or unsafe", exception);
      }
   }

   private boolean isPrivateOrLoopbackLiteral(String host) {
      if (host.equalsIgnoreCase("localhost")) {
         return true;
      }

      if (!host.matches("[0-9.]+") && !host.contains(":")) {
         return false;
      }

      try {
         InetAddress address = InetAddress.getByName(host);
         return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress();
      } catch (UnknownHostException exception) {
         return false;
      }
   }

   private void requireFeatureEnabled() {
      if (!this.creationProperties.isEnabled()) {
         throw new ProviderExecutionException(ProviderErrorCategory.PROVIDER_FEATURE_DISABLED, "Creation providers are disabled");
      }
   }

   private void requirePresentConfiguration() {
      if (this.isBlank(this.provider.getBaseUrl()) || this.isBlank(this.provider.getOutputRoot())) {
         throw new ProviderExecutionException(ProviderErrorCategory.PROVIDER_CONFIGURATION_MISSING, "VMM configuration is incomplete");
      }
   }

   private boolean isBlank(String value) {
      return value == null || value.isBlank();
   }

   private ProviderReadiness readiness(ProviderReadinessState state, String reason) {
      return new ProviderReadiness(state, reason);
   }

   private ProviderExecutionException invalid(String message, Throwable cause) {
      return cause == null
         ? new ProviderExecutionException(ProviderErrorCategory.PROVIDER_CONFIGURATION_INVALID, message)
         : new ProviderExecutionException(ProviderErrorCategory.PROVIDER_CONFIGURATION_INVALID, message, cause);
   }
}
