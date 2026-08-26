package com.auralink.compat;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import com.auralink.controller.ApiController;
import com.auralink.controller.AuthController;
import com.auralink.controller.HealthController;
import com.auralink.controller.PaintingController;
import com.auralink.controller.UserController;

class LegacyRouteContractTest {

    @Test
    void frozenLegacyRoutesRetainTheirMethodsAndPaths() {
        assertRoute(AuthController.class, "login", RequestMethod.POST, "/api/auth/login");
        assertRoute(AuthController.class, "register", RequestMethod.POST, "/api/auth/register");
        assertRoute(HealthController.class, "healthCheck", RequestMethod.GET, "/api/health");
        assertRoute(ApiController.class, "uploadImage", RequestMethod.POST, "/api/upload-image");
        assertRoute(ApiController.class, "uploadImageForSession", RequestMethod.POST,
                "/api/upload-session/{sessionId}/image");
        assertRoute(ApiController.class, "describeImage", RequestMethod.POST, "/api/describe-image");
        assertRoute(ApiController.class, "generateMusic", RequestMethod.POST, "/api/generate-music");
        assertRoute(ApiController.class, "recordApiUsage", RequestMethod.POST, "/api/record");
        assertRoute(ApiController.class, "uploadResult", RequestMethod.POST, "/api/upload-result");
        assertRoute(ApiController.class, "serveFile", RequestMethod.GET, "/api/files/**");
        assertRoute(ApiController.class, "serveAudio", RequestMethod.GET, "/api/audios/{filename:.+}");
        assertRoute(ApiController.class, "getModels", RequestMethod.GET, "/api/models");
        assertRoute(PaintingController.class, "listPaintings", RequestMethod.GET,
                "/api/paintings");
        assertRoute(PaintingController.class, "servePaintingImage", RequestMethod.GET,
                "/api/paintings/images/{fileName:.+}");
        assertRoute(UserController.class, "getLogs", RequestMethod.GET, "/api/user/logs");
        assertRoute(UserController.class, "getProfile", RequestMethod.GET, "/api/user/profile");
    }

    private static void assertRoute(
            Class<?> controllerType,
            String methodName,
            RequestMethod expectedMethod,
            String expectedPath) {
        Method handler = Arrays.stream(controllerType.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName))
                .reduce((first, second) -> {
                    throw new AssertionError("Ambiguous handler method: " + methodName);
                })
                .orElseThrow(() -> new AssertionError("Missing handler method: " + methodName));

        RequestMapping handlerMapping = AnnotatedElementUtils.findMergedAnnotation(handler, RequestMapping.class);
        assertThat(handlerMapping).as("mapping for %s.%s", controllerType.getSimpleName(), methodName).isNotNull();
        assertThat(handlerMapping.method()).contains(expectedMethod);

        RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(controllerType, RequestMapping.class);
        Set<String> paths = combine(
                classMapping == null ? new String[] {""} : mappingPaths(classMapping),
                mappingPaths(handlerMapping));
        assertThat(paths).contains(expectedPath);
    }

    private static String[] mappingPaths(RequestMapping mapping) {
        if (mapping.path().length > 0) {
            return mapping.path();
        }
        if (mapping.value().length > 0) {
            return mapping.value();
        }
        return new String[] {""};
    }

    private static Set<String> combine(String[] bases, String[] children) {
        Set<String> combined = new LinkedHashSet<>();
        for (String base : bases) {
            for (String child : children) {
                String normalizedBase = "/".equals(base) ? "" : base.replaceAll("/$", "");
                String normalizedChild = child.isBlank() || "/".equals(child)
                        ? ""
                        : child.startsWith("/") ? child : "/" + child;
                String fullPath = normalizedBase + normalizedChild;
                combined.add(fullPath.isEmpty() ? "/" : fullPath);
            }
        }
        return combined;
    }
}
