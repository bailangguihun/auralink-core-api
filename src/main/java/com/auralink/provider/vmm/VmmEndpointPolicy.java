package com.auralink.provider.vmm;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.config.properties.ProviderProperties;
import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;
import com.auralink.creation.provider.ProviderReadiness;
import com.auralink.creation.provider.ProviderReadinessState;
import com.auralink.provider.validation.CreationProviderConfigurationChecks;

/** Loopback/private-only VMM service configuration without automatic health calls. */
@Component
public class VmmEndpointPolicy implements VmmEndpointResolver {

    private final CreationProviderProperties creationProperties;
    private final ProviderProperties.Provider provider;

    public VmmEndpointPolicy(
            CreationProviderProperties creationProperties,
            ProviderProperties providerProperties) {
        this.creationProperties = creationProperties;
        this.provider = providerProperties.getPaintingMusic();
    }

    public ProviderReadiness readiness() {
        if (!creationProperties.isEnabled()) {
            return readiness(ProviderReadinessState.FEATURE_DISABLED, "CREATION_PROVIDERS_DISABLED");
        }
        if (isBlank(provider.getBaseUrl()) || isBlank(provider.getOutputRoot())) {
            return readiness(ProviderReadinessState.CONFIGURATION_MISSING, "REQUIRED_CONFIGURATION_MISSING");
        }
        try {
            validateBase(provider.getBaseUrl());
            CreationProviderConfigurationChecks.requireVmm(creationProperties);
            validateExistingOutputRoot(validateConfiguredOutputRoot(provider.getOutputRoot()));
            return readiness(
                    ProviderReadinessState.INTERNAL_SERVICE_NOT_VALIDATED,
                    "VMM_HEALTH_NOT_AUTOMATICALLY_CHECKED");
        } catch (ProviderExecutionException exception) {
            return readiness(ProviderReadinessState.CONFIGURATION_INVALID, "PROVIDER_CONFIGURATION_INVALID");
        }
    }

    @Override
    public URI resolveGenerationEndpoint() {
        requireFeatureEnabled();
        requirePresentConfiguration();
        URI base = validateBase(provider.getBaseUrl());
        CreationProviderConfigurationChecks.requireVmm(creationProperties);
        return URI.create(base.toString() + "/api/generate_with_image");
    }

    @Override
    public Path resolveOutputRoot() {
        requireFeatureEnabled();
        requirePresentConfiguration();
        CreationProviderConfigurationChecks.requireVmm(creationProperties);
        return validateExistingOutputRoot(validateConfiguredOutputRoot(provider.getOutputRoot()));
    }

    private URI validateBase(String rawBase) {
        final URI uri;
        try {
            uri = new URI(rawBase.trim());
        } catch (URISyntaxException | NullPointerException exception) {
            throw invalid("VMM service URL is invalid", exception);
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        String path = uri.getPath();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || host == null || !isPrivateOrLoopbackLiteral(host)
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535
                || !(path == null || path.isEmpty() || "/".equals(path))) {
            throw invalid("VMM service URL must be an internal loopback/private root", null);
        }
        String normalizedHost = host.contains(":") ? "[" + host + "]" : host.toLowerCase(Locale.ROOT);
        String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
        return URI.create(scheme.toLowerCase(Locale.ROOT) + "://" + normalizedHost + port);
    }

    private Path validateConfiguredOutputRoot(String rawRoot) {
        try {
            Path root = Path.of(rawRoot.trim());
            if (!root.isAbsolute()) {
                throw invalid("VMM output root must be absolute", null);
            }
            return root.normalize();
        } catch (RuntimeException exception) {
            if (exception instanceof ProviderExecutionException providerException) {
                throw providerException;
            }
            throw invalid("VMM output root is invalid", exception);
        }
    }

    private Path validateExistingOutputRoot(Path root) {
        try {
            if (java.nio.file.Files.isSymbolicLink(root)
                    || !java.nio.file.Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || !root.toRealPath().equals(root)) {
                throw invalid("VMM output root is unavailable or unsafe", null);
            }
            return root;
        } catch (java.io.IOException exception) {
            throw invalid("VMM output root is unavailable or unsafe", exception);
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
            return address.isLoopbackAddress()
                    || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }

    private void requireFeatureEnabled() {
        if (!creationProperties.isEnabled()) {
            throw new ProviderExecutionException(
                    ProviderErrorCategory.PROVIDER_FEATURE_DISABLED,
                    "Creation providers are disabled");
        }
    }

    private void requirePresentConfiguration() {
        if (isBlank(provider.getBaseUrl()) || isBlank(provider.getOutputRoot())) {
            throw new ProviderExecutionException(
                    ProviderErrorCategory.PROVIDER_CONFIGURATION_MISSING,
                    "VMM configuration is incomplete");
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
                ? new ProviderExecutionException(
                        ProviderErrorCategory.PROVIDER_CONFIGURATION_INVALID, message)
                : new ProviderExecutionException(
                        ProviderErrorCategory.PROVIDER_CONFIGURATION_INVALID, message, cause);
    }
}
