package com.auralink.provider.vmm;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;
import com.auralink.provider.http.ProviderHttpExecutor;
import com.auralink.provider.http.ProviderHttpResponse;
import com.auralink.provider.validation.StrictProviderJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class VmmHttpClient {
   private static final long MAX_RESPONSE_BYTES = 65536L;
   public static final int MIN_DURATION_SECONDS = 3;
   public static final int MAX_DURATION_SECONDS = 30;
   private final RestClient restClient;
   private final ProviderHttpExecutor httpExecutor;
   private final ObjectMapper objectMapper;
   private final CreationProviderProperties properties;
   private final VmmEndpointResolver endpointResolver;
   private final VmmManagedGate managedGate;

   public VmmHttpClient(
      @Qualifier("vmmProviderRestClient") RestClient restClient,
      ProviderHttpExecutor httpExecutor,
      ObjectMapper objectMapper,
      CreationProviderProperties properties,
      VmmEndpointResolver endpointResolver
   ) {
      this(restClient, httpExecutor, objectMapper, properties, endpointResolver, null);
   }

   @Autowired
   public VmmHttpClient(@Qualifier("vmmProviderRestClient") RestClient restClient, ProviderHttpExecutor httpExecutor,
                        ObjectMapper objectMapper, CreationProviderProperties properties, VmmEndpointResolver endpointResolver, VmmManagedGate managedGate) {
      this.managedGate = managedGate;
      this.restClient = restClient;
      this.httpExecutor = httpExecutor;
      this.objectMapper = objectMapper;
      this.properties = properties;
      this.endpointResolver = endpointResolver;
   }

   public String generate(String requestId, String imageDataUrl, int durationSeconds) {
      return generate(requestId, imageDataUrl, durationSeconds, null);
   }

   public String generate(String requestId, String imageDataUrl, int durationSeconds, VmmExecutionContext context) {
      if (durationSeconds >= 3 && durationSeconds <= 30) {
         URI endpoint = this.endpointResolver.resolveGenerationEndpoint();
         long maxRequestBytes = Math.multiplyExact(this.properties.getMaxImageInputBytes(), 2L);
         boolean managed = managedGate != null && managedGate.enabled();
         RestClient client = restClient;
         if (managed) {
            managedGate.requireDispatch(endpoint, context, durationSeconds);
            client = restClient.mutate().defaultHeaders(headers -> {
               headers.set(VmmManagedGate.TOKEN_HEADER, managedGate.token());
               headers.set("X-ArtLIVE-VMM-Request-ID", requestId);
               headers.set("X-ArtLIVE-VMM-Owner-Id", Long.toString(context.ownerId()));
               headers.set("X-ArtLIVE-VMM-Creation-Id", Long.toString(context.creationId()));
               headers.set("X-ArtLIVE-VMM-Step-Id", Long.toString(context.stepId()));
               headers.set("X-ArtLIVE-VMM-Retry-Version", Integer.toString(context.retryVersion()));
               headers.set("X-ArtLIVE-VMM-Source-Painting-Id", context.sourcePaintingId().toString());
            }).build();
         }
         try {
            ProviderHttpResponse response = this.httpExecutor.postJson(client, endpoint, null, requestId,
               new VmmGenerationRequest(imageDataUrl, durationSeconds), maxRequestBytes, 65536L);
            if (managed) {
               JsonNode body = StrictProviderJson.parse(objectMapper, response.body());
               if (body == null || !body.isObject() || !requestId.equals(body.path("request_id").asText())) throw invalid("Managed VMM response identity does not match", null);
            }
            return this.parseFileName(response.body());
         } catch (ProviderExecutionException exception) {
            if (managed && (exception.category() == ProviderErrorCategory.PROVIDER_TIMEOUT ||
                exception.category() == ProviderErrorCategory.PROVIDER_UNAVAILABLE ||
                exception.category() == ProviderErrorCategory.PROVIDER_INVALID_RESPONSE)) {
               throw new ProviderExecutionException(ProviderErrorCategory.PROVIDER_OUTCOME_UNCERTAIN,
                  "Managed VMM request outcome is uncertain; it must not be automatically replayed", exception);
            }
            throw exception;
         } catch (java.io.IOException exception) {
            throw new ProviderExecutionException(ProviderErrorCategory.PROVIDER_OUTCOME_UNCERTAIN,
               "Managed VMM response is incomplete; it must not be automatically replayed", exception);
         }
      } else {
         throw new ProviderExecutionException(ProviderErrorCategory.PROVIDER_REJECTED, "VMM duration is outside the supported range");
      }
   }

   private String parseFileName(byte[] responseBody) {
      JsonNode root;
      try {
         root = StrictProviderJson.parse(this.objectMapper, responseBody);
      } catch (Exception exception) {
         throw this.invalid("VMM returned malformed JSON", exception);
      }

      if (root != null && root.isObject()) {
         JsonNode success = root.get("success");
         if (success != null && success.isBoolean() && !success.booleanValue()) {
            throw new ProviderExecutionException(ProviderErrorCategory.PROVIDER_REJECTED, "VMM rejected the generation request");
         }

         if (success != null && success.isBoolean() && success.booleanValue()) {
            JsonNode fileName = root.get("fileName");
            if (fileName != null && fileName.isTextual() && !fileName.textValue().isBlank()) {
               return fileName.textValue().trim();
            } else {
               throw this.invalid("VMM response file name is missing", null);
            }
         } else {
            throw this.invalid("VMM response success marker is missing", null);
         }
      } else {
         throw this.invalid("VMM response is not an object", null);
      }
   }

   private ProviderExecutionException invalid(String message, Throwable cause) {
      return cause == null
         ? new ProviderExecutionException(ProviderErrorCategory.PROVIDER_INVALID_RESPONSE, message)
         : new ProviderExecutionException(ProviderErrorCategory.PROVIDER_INVALID_RESPONSE, message, cause);
   }
}
