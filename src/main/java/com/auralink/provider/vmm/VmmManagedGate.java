package com.auralink.provider.vmm;

import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.config.properties.VmmManagedProperties;
import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;
import com.auralink.creation.provider.ProviderReadiness;
import com.auralink.creation.provider.ProviderReadinessState;
import com.auralink.provider.validation.StrictProviderJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Non-generating, fail-closed managed-role check. The VMM gate remains the atomic admission authority. */
@Component
public class VmmManagedGate {
   public static final String TOKEN_HEADER = "X-ArtLIVE-VMM-Token";
   private final VmmManagedProperties properties;
   private final ObjectMapper mapper;

   public VmmManagedGate(VmmManagedProperties properties, ObjectMapper mapper) {
      this.properties = properties;
      this.mapper = mapper;
   }

   public boolean enabled() { return properties.isEnabled(); }

   public ProviderReadiness readiness(URI generationEndpoint) {
      try {
         Status status = status(generationEndpoint);
         return status.permitted()
            ? new ProviderReadiness(ProviderReadinessState.READY_FOR_CONTROLLED_EXECUTION, "VMM_MANAGED_READY")
            : new ProviderReadiness(ProviderReadinessState.INTERNAL_SERVICE_NOT_VALIDATED, "VMM_MANAGED_DISPATCH_CLOSED");
      } catch (ProviderExecutionException exception) {
         return new ProviderReadiness(
            exception.category() == ProviderErrorCategory.PROVIDER_CONFIGURATION_INVALID
               ? ProviderReadinessState.CONFIGURATION_INVALID : ProviderReadinessState.INTERNAL_SERVICE_NOT_VALIDATED,
            "VMM_MANAGED_NOT_READY");
      }
   }

   public void requireSubmission(URI endpoint, long ownerId, Long paintingId, int duration) {
      if (!enabled()) return;
      try {
         requireAllowed(status(endpoint), ownerId, paintingId, duration);
      } catch (ProviderExecutionException exception) {
         throw new ApiV1Exception(HttpStatus.CONFLICT, ApiErrorCode.CREATION_OPERATION_UNAVAILABLE,
            "音乐服务未开放派发，或本次请求不属于受控验收范围");
      }
   }

   public void requireDispatch(URI endpoint, VmmExecutionContext context, int duration) {
      if (!enabled()) return;
      if (context == null) throw invalid("Managed VMM execution identity is required");
      requireAllowed(status(endpoint), context.ownerId(), context.sourcePaintingId(), duration);
   }

   private void requireAllowed(Status status, long ownerId, Long paintingId, int duration) {
      if (!status.permitted()) throw unavailable();
      if ("ACCEPTANCE".equals(status.mode()) &&
         (!Long.valueOf(ownerId).equals(properties.getAcceptanceOwnerId()) || paintingId == null ||
          !paintingId.equals(properties.getAcceptanceSourcePaintingId()) ||
          !Integer.valueOf(duration).equals(properties.getAcceptanceDurationSeconds()))) {
         throw new ProviderExecutionException(ProviderErrorCategory.PROVIDER_REJECTED, "Managed VMM acceptance scope does not match");
      }
   }

   public String token() {
      try {
         Path configured = Path.of(properties.getTokenFile());
         if (!configured.isAbsolute() || Files.isSymbolicLink(configured) ||
             !Files.isRegularFile(configured, LinkOption.NOFOLLOW_LINKS) || !configured.toRealPath().equals(configured.normalize()) ||
             Files.size(configured) > 4096) throw invalid("Managed VMM private token file is unavailable");
         Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(configured, LinkOption.NOFOLLOW_LINKS);
         if (permissions.contains(PosixFilePermission.OTHERS_READ) || permissions.contains(PosixFilePermission.OTHERS_WRITE) ||
             permissions.contains(PosixFilePermission.GROUP_WRITE) || permissions.contains(PosixFilePermission.OTHERS_EXECUTE) ||
             permissions.contains(PosixFilePermission.GROUP_EXECUTE)) throw invalid("Managed VMM private token file permissions are unsafe");
         String value = Files.readString(configured).strip();
         if (!value.matches("[A-Za-z0-9_-]{32,256}")) throw invalid("Managed VMM private token is invalid");
         return value;
      } catch (ProviderExecutionException exception) {
         throw exception;
      } catch (Exception exception) {
         throw invalid("Managed VMM private token file is unavailable");
      }
   }

   private Status status(URI endpoint) {
      String secret = token();
      HttpURLConnection connection = null;
      try {
         URI statusEndpoint = new URI(endpoint.getScheme(), null, endpoint.getHost(), endpoint.getPort(), "/internal/vmm/status", null, null);
         connection = (HttpURLConnection) statusEndpoint.toURL().openConnection(java.net.Proxy.NO_PROXY);
         connection.setInstanceFollowRedirects(false);
         connection.setConnectTimeout(1000);
         connection.setReadTimeout(1000);
         connection.setRequestMethod("GET");
         connection.setRequestProperty(TOKEN_HEADER, secret);
         connection.setRequestProperty("Accept", "application/json");
         connection.setRequestProperty("Connection", "close");
         long deadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
         if (connection.getResponseCode() != 200 || connection.getContentLengthLong() > 4096) throw unavailable();
         byte[] body;
         try (InputStream input = connection.getInputStream(); java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[512];
            int count;
            while ((count = input.read(buffer)) >= 0) {
               if (System.nanoTime() > deadline || bytes.size() + count > 4096) throw unavailable();
               bytes.write(buffer, 0, count);
            }
            body = bytes.toByteArray();
         }
         JsonNode root = StrictProviderJson.parse(mapper, body);
         if (root == null || !root.isObject() || !"artlive-vmm-managed-v1".equals(root.path("contract").asText()) ||
             !root.path("model_ready").isBoolean() || !root.path("dispatch_permitted").isBoolean() ||
             !root.path("in_flight").isBoolean() || !root.path("uncertain").isBoolean() ||
             !Set.of("CLOSED", "ACCEPTANCE", "OPEN").contains(root.path("mode").asText())) throw unavailable();
         return new Status(root.path("mode").asText(), root.path("model_ready").booleanValue() &&
            root.path("dispatch_permitted").booleanValue() && !root.path("in_flight").booleanValue() &&
            !root.path("uncertain").booleanValue() && !"CLOSED".equals(root.path("mode").asText()));
      } catch (ProviderExecutionException exception) {
         throw exception;
      } catch (Exception exception) {
         throw unavailable();
      } finally {
         if (connection != null) connection.disconnect();
      }
   }

   private ProviderExecutionException invalid(String message) {
      return new ProviderExecutionException(ProviderErrorCategory.PROVIDER_CONFIGURATION_INVALID, message);
   }
   private ProviderExecutionException unavailable() {
      return new ProviderExecutionException(ProviderErrorCategory.PROVIDER_UNAVAILABLE, "Managed VMM is not ready for dispatch");
   }
   private record Status(String mode, boolean permitted) { }
}
