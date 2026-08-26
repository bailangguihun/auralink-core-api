package com.auralink.ops.round81;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

import org.springframework.core.env.Environment;

/** Strict loopback-only transport override used solely by the packaged Mock harness. */
final class Round81MockSupport {

    static final String ENABLE_TOKEN = "LOCAL_LOOPBACK_ONLY";
    private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "::1", "localhost");

    private final Environment environment;

    Round81MockSupport(Environment environment) {
        this.environment = environment;
    }

    boolean enabled() {
        return ENABLE_TOKEN.equals(environment.getProperty("auralink.round81.mock-mode", ""));
    }

    URI endpoint(String fixedPath) {
        URI base = requireBase();
        if (fixedPath == null || !fixedPath.startsWith("/") || fixedPath.contains("..")) {
            throw new Round81ValidationException(
                    "MOCK_ENDPOINT_INVALID", "Mock endpoint path is invalid");
        }
        return URI.create(base.toString() + fixedPath);
    }

    URI generatedImage() {
        return endpoint("/mock/generated.png");
    }

    void requireEnabled() {
        if (!enabled()) {
            throw new Round81ValidationException(
                    "MOCK_MODE_REFUSED", "Packaged Mock mode is not enabled");
        }
    }

    private URI requireBase() {
        requireEnabled();
        String raw = environment.getProperty("auralink.round81.mock-base-url", "");
        final URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException | NullPointerException exception) {
            throw new Round81ValidationException(
                    "MOCK_ENDPOINT_INVALID", "Mock endpoint configuration is invalid", exception);
        }
        String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath();
        if (!"http".equalsIgnoreCase(uri.getScheme())
                || host == null || !LOOPBACK_HOSTS.contains(host)
                || uri.getPort() < 1024 || uri.getPort() > 65535
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (path != null && !path.isEmpty() && !"/".equals(path))) {
            throw new Round81ValidationException(
                    "MOCK_ENDPOINT_INVALID", "Mock endpoint must be an explicit loopback HTTP origin");
        }
        return URI.create("http://" + hostForUri(host) + ":" + uri.getPort());
    }

    private String hostForUri(String host) {
        return host.contains(":") ? "[" + host + "]" : host;
    }
}
