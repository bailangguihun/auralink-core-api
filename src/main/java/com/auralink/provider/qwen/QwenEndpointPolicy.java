package com.auralink.provider.qwen;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.config.properties.ProviderProperties;
import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;
import com.auralink.creation.provider.ProviderReadiness;
import com.auralink.creation.provider.ProviderReadinessState;
import com.auralink.provider.validation.CreationProviderConfigurationChecks;

/** Exact reviewed Alibaba Model Studio regional OpenAI-compatible roots. */
@Component
public class QwenEndpointPolicy implements QwenEndpointResolver {

    private static final Set<String> APPROVED_HOSTS = Set.of(
            "dashscope.aliyuncs.com",
            "dashscope-intl.aliyuncs.com");
    private static final String APPROVED_ROOT_PATH = "/compatible-mode/v1";
    private static final Pattern MODEL = Pattern.compile("qwen3-vl-plus(?:-[A-Za-z0-9._-]{1,96})?");

    private final CreationProviderProperties creationProperties;
    private final ProviderProperties.Provider provider;

    public QwenEndpointPolicy(
            CreationProviderProperties creationProperties,
            ProviderProperties providerProperties) {
        this.creationProperties = creationProperties;
        this.provider = providerProperties.getQwen();
    }

    public ProviderReadiness readiness() {
        if (!creationProperties.isEnabled()) {
            return readiness(ProviderReadinessState.FEATURE_DISABLED, "CREATION_PROVIDERS_DISABLED");
        }
        if (isBlank(provider.getApiKey()) || isBlank(provider.getBaseUrl()) || isBlank(provider.getModel())) {
            return readiness(ProviderReadinessState.CONFIGURATION_MISSING, "REQUIRED_CONFIGURATION_MISSING");
        }
        try {
            requireValidModel(provider.getModel());
            validateBase(provider.getBaseUrl());
            CreationProviderConfigurationChecks.requireQwen(creationProperties);
            return readiness(
                    ProviderReadinessState.READY_FOR_CONTROLLED_EXECUTION,
                    "CONFIGURATION_VALIDATED_WITHOUT_PROVIDER_CALL");
        } catch (ProviderExecutionException exception) {
            return readiness(ProviderReadinessState.CONFIGURATION_INVALID, "PROVIDER_CONFIGURATION_INVALID");
        }
    }

    @Override
    public URI resolveChatCompletionsEndpoint() {
        requireFeatureEnabled();
        requirePresentConfiguration();
        requireValidModel(provider.getModel());
        URI base = validateBase(provider.getBaseUrl());
        CreationProviderConfigurationChecks.requireQwen(creationProperties);
        return URI.create(base.toString() + "/chat/completions");
    }

    private URI validateBase(String rawBase) {
        final URI uri;
        try {
            uri = new URI(rawBase.trim());
        } catch (URISyntaxException | NullPointerException exception) {
            throw invalid("Qwen base URL is invalid", exception);
        }
        String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath();
        while (path != null && path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || host == null || !APPROVED_HOSTS.contains(host)
                || uri.getPort() != -1
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || !APPROVED_ROOT_PATH.equals(path)) {
            throw invalid("Qwen base URL is not an approved regional root", null);
        }
        return URI.create("https://" + host + APPROVED_ROOT_PATH);
    }

    private void requireFeatureEnabled() {
        if (!creationProperties.isEnabled()) {
            throw new ProviderExecutionException(
                    ProviderErrorCategory.PROVIDER_FEATURE_DISABLED,
                    "Creation providers are disabled");
        }
    }

    private void requirePresentConfiguration() {
        if (isBlank(provider.getApiKey()) || isBlank(provider.getBaseUrl()) || isBlank(provider.getModel())) {
            throw new ProviderExecutionException(
                    ProviderErrorCategory.PROVIDER_CONFIGURATION_MISSING,
                    "Qwen configuration is incomplete");
        }
    }

    private void requireValidModel(String model) {
        if (model == null || !MODEL.matcher(model.trim()).matches()) {
            throw invalid("Qwen model configuration is invalid", null);
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
