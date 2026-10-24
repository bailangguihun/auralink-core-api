package com.auralink.provider.vmm;

import static org.assertj.core.api.Assertions.*;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.config.CreationProviderClientConfiguration;
import com.auralink.config.properties.*;
import com.auralink.creation.provider.*;
import com.auralink.provider.ProviderBulkheads;
import com.auralink.provider.artifact.*;
import com.auralink.provider.http.ProviderHttpExecutor;
import com.auralink.provider.validation.*;
import com.auralink.service.media.ImageContentValidator;
import com.auralink.workflow.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer;
import org.springframework.web.client.RestClient;

class VmmManagedContractTest {
   @TempDir Path temp;
   final ObjectMapper mapper = new ObjectMapper();
   final AtomicInteger posts = new AtomicInteger();
   final AtomicInteger gets = new AtomicInteger();
   final AtomicReference<String> mode = new AtomicReference<>("OPEN");
   final AtomicBoolean uploadSupport = new AtomicBoolean(true);
   final AtomicBoolean ready = new AtomicBoolean(true), permitted = new AtomicBoolean(true), inFlight = new AtomicBoolean(), uncertain = new AtomicBoolean();
   final AtomicReference<String> response = new AtomicReference<>("OK");
   final AtomicReference<JsonNode> received = new AtomicReference<>();
   final AtomicReference<Headers> headers = new AtomicReference<>();
   HttpServer server;
   ExecutorService workers;
   CloseableHttpClient transport;
   CreationProviderProperties creation;
   ProviderProperties provider;
   VmmManagedProperties managed;
   VmmManagedGate gate;
   VmmEndpointPolicy endpoint;
   VmmHttpClient client;
   Path outputs;
   final VmmExecutionContext context = new VmmExecutionContext(7, 11, 22, 0, 33L);
   final String requestId = "9ac88e3b-2dd6-4fbd-a5a5-12c5dc2e4823";

   @BeforeEach void setup() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      workers = Executors.newCachedThreadPool(); server.setExecutor(workers);
      outputs = Files.createDirectory(temp.resolve("output"));
      creation = new CreationProviderProperties(); creation.setEnabled(true); creation.setStagingDir(temp.resolve("staging"));
      creation.setVmmReadTimeout(Duration.ofMillis(250));
      provider = new ProviderProperties(); provider.getPaintingMusic().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
      provider.getPaintingMusic().setOutputRoot(outputs.toString());
      managed = new VmmManagedProperties(); managed.setEnabled(true);
      Path token = Files.writeString(temp.resolve("token"), "test_only_012345678901234567890123456789");
      Files.setPosixFilePermissions(token, PosixFilePermissions.fromString("rw-------")); managed.setTokenFile(token.toString());
      managed.setAcceptanceOwnerId(7L); managed.setAcceptanceSourcePaintingId(33L); managed.setAcceptanceDurationSeconds(10);
      gate = new VmmManagedGate(managed, mapper); endpoint = new VmmEndpointPolicy(creation, provider, gate);
      var config = new CreationProviderClientConfiguration(creation, new RestClientBuilderConfigurer());
      transport = config.vmmProviderHttpClient(); RestClient rest = config.vmmProviderRestClient(transport);
      client = new VmmHttpClient(rest, new ProviderHttpExecutor(mapper), mapper, creation, endpoint, gate);
      server.createContext("/internal/vmm/status", exchange -> {
         gets.incrementAndGet();
         if (!"test_only_012345678901234567890123456789".equals(exchange.getRequestHeaders().getFirst(VmmManagedGate.TOKEN_HEADER))) { send(exchange, 403, "{}"); return; }
         if ("BAD_STATUS".equals(response.get())) { send(exchange, 200, "{\"contract\":\"old-vmm\"}"); return; }
         send(exchange, 200, mapper.writeValueAsString(Map.of("contract", "artlive-vmm-managed-v1", "mode", mode.get(),
            "model_ready", ready.get(), "dispatch_permitted", permitted.get(), "in_flight", inFlight.get(), "uncertain", uncertain.get(), "source_kinds", uploadSupport.get() ? List.of("CATALOG_PAINTING", "OWNED_UPLOAD") : List.of("CATALOG_PAINTING"))));
      });
      server.createContext("/api/generate_with_image", exchange -> {
         posts.incrementAndGet(); headers.set(exchange.getRequestHeaders()); received.set(mapper.readTree(exchange.getRequestBody()));
         if ("DELAY".equals(response.get())) { try { Thread.sleep(650); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
         if ("NULL".equals(response.get())) { send(exchange, 200, "null"); return; }
         if ("MALFORMED".equals(response.get())) { send(exchange, 200, "{"); return; }
         if ("REFUSE".equals(response.get())) { send(exchange, 409, "{\"code\":\"REQUEST_ALREADY_DISPATCHED\"}"); return; }
         int duration = received.get().get("duration").intValue();
         Files.write(outputs.resolve("synthetic.wav"), wave(duration));
         String identity = "MISMATCH".equals(response.get()) ? "another-request" : exchange.getRequestHeaders().getFirst("X-ArtLIVE-VMM-Request-ID");
         send(exchange, 200, mapper.writeValueAsString(Map.of("success", true, "fileName", "UNSAFE".equals(response.get()) ? "../synthetic.wav" : "synthetic.wav", "request_id", identity == null ? "legacy" : identity)));
      }); server.start();
   }
   @AfterEach void cleanup() throws Exception { if (server != null) server.stop(0); if (workers != null) workers.shutdownNow(); if (transport != null) transport.close(); }
   static void send(HttpExchange exchange, int code, String value) throws IOException {
      byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(code, bytes.length); try (var out = exchange.getResponseBody()) { out.write(bytes); }
   }
   @Test void uploadUsesOnlyAssetNamespaceAndOldWrapperRefusesBeforePost() {
      var upload = new VmmExecutionContext(7, 11, 22, 0, null, 44L);
      uploadSupport.set(false);
      assertThatThrownBy(() -> gate.requireSubmission(endpoint.resolveGenerationEndpoint(), 7, null, 44L, 3)).isInstanceOf(ApiV1Exception.class);
      assertThatThrownBy(() -> client.generate(requestId, "data:image/png;base64,AQ==", 3, upload)).isInstanceOf(ProviderExecutionException.class);
      assertThat(posts).hasValue(0);
      uploadSupport.set(true);
      assertThat(client.generate(requestId, "data:image/png;base64,AQ==", 3, upload)).isEqualTo("synthetic.wav");
      assertThat(headers.get().getFirst("X-ArtLIVE-VMM-Source-Kind")).isEqualTo("OWNED_UPLOAD");
      assertThat(headers.get().getFirst("X-ArtLIVE-VMM-Source-Asset-Id")).isEqualTo("44");
      assertThat(headers.get().getFirst("X-ArtLIVE-VMM-Source-Painting-Id")).isNull();
      mode.set("ACCEPTANCE");
      assertThatThrownBy(() -> client.generate(requestId, "data:image/png;base64,AQ==", 10, upload)).isInstanceOf(ProviderExecutionException.class);
      assertThat(posts).hasValue(1);
      assertThatThrownBy(() -> new VmmExecutionContext(7,11,22,0,33L,44L)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new VmmExecutionContext(7,11,22,0,null,null)).isInstanceOf(IllegalArgumentException.class);
   }

   @Test void disabledRetainsLegacyWithoutTokenOrStatusRequests() {
      managed.setEnabled(false); managed.setTokenFile("missing");
      assertThat(endpoint.readiness().state()).isEqualTo(ProviderReadinessState.READY_FOR_CONTROLLED_EXECUTION);
      assertThat(client.generate(requestId, "data:image/png;base64,AQ==", 10)).isEqualTo("synthetic.wav");
      assertThat(gets).hasValue(0); assertThat(posts).hasValue(1); assertThat(headers.get().getFirst(VmmManagedGate.TOKEN_HEADER)).isNull();
   }
   @Test void closedBlocksCapabilityAdmissionAndDispatchWithoutPost() {
      mode.set("CLOSED");
      assertThat(endpoint.readiness().state()).isEqualTo(ProviderReadinessState.INTERNAL_SERVICE_NOT_VALIDATED);
      assertThatThrownBy(() -> gate.requireSubmission(endpoint.resolveGenerationEndpoint(), 7, 33L, 10)).isInstanceOf(ApiV1Exception.class);
      assertThatThrownBy(() -> client.generate(requestId, "data:image/png;base64,AQ==", 10, context)).isInstanceOf(ProviderExecutionException.class);
      assertThat(posts).hasValue(0);
   }
   @Test void readinessRequiresLiveMatchingContractAndNonBusyService() {
      assertThat(endpoint.readiness().state()).isEqualTo(ProviderReadinessState.READY_FOR_CONTROLLED_EXECUTION);
      ready.set(false); assertNotReady(); ready.set(true); inFlight.set(true); assertNotReady(); inFlight.set(false);
      uncertain.set(true); assertNotReady(); uncertain.set(false); permitted.set(false); assertNotReady(); permitted.set(true);
      response.set("BAD_STATUS"); assertNotReady(); server.stop(0); assertNotReady(); assertThat(posts).hasValue(0);
   }
   void assertNotReady() { assertThat(endpoint.readiness().state()).isEqualTo(ProviderReadinessState.INTERNAL_SERVICE_NOT_VALIDATED); }
   @Test void acceptanceChecksTrustedOwnerSourceAndDurationBeforeAnyPost() {
      mode.set("ACCEPTANCE");
      gate.requireSubmission(endpoint.resolveGenerationEndpoint(), 7, 33L, 10);
      assertThatThrownBy(() -> gate.requireSubmission(endpoint.resolveGenerationEndpoint(), 8, 33L, 10)).isInstanceOf(ApiV1Exception.class);
      assertThatThrownBy(() -> gate.requireSubmission(endpoint.resolveGenerationEndpoint(), 7, 34L, 10)).isInstanceOf(ApiV1Exception.class);
      assertThatThrownBy(() -> gate.requireSubmission(endpoint.resolveGenerationEndpoint(), 7, 33L, 3)).isInstanceOf(ApiV1Exception.class);
      assertThatThrownBy(() -> client.generate(requestId, "data:image/png;base64,AQ==", 10, new VmmExecutionContext(8,11,22,0,33L))).isInstanceOf(ProviderExecutionException.class);
      assertThat(posts).hasValue(0);
   }
   @Test void realSubmissionUsesAuthenticatedOwnerAndCannotPersistUnauthorizedAcceptance() {
      mode.set("ACCEPTANCE");
      var currentUser = org.mockito.Mockito.mock(com.auralink.service.CurrentUserService.class);
      var owner = com.auralink.entity.User.builder().id(8L).build();
      org.mockito.Mockito.when(currentUser.requireCurrentUser()).thenReturn(owner);
      var preparer = org.mockito.Mockito.mock(com.auralink.workflow.service.WorkflowExecutionPreparer.class);
      var transform = new com.auralink.workflow.service.WorkflowExecutionPreparer.PreparedTransform("step1", WorkflowOperation.PAINTING_TO_MUSIC,
         "auralink-vmm", WorkflowModality.PAINTING, WorkflowModality.AUDIO, "{\"durationSeconds\":10}");
      var workflow = new com.auralink.workflow.service.WorkflowExecutionPreparer.PreparedWorkflow(null, null, "{}", WorkflowModality.PAINTING, WorkflowModality.AUDIO, List.of(transform));
      org.mockito.Mockito.when(preparer.prepare(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(owner))).thenReturn(workflow);
      var resolver = org.mockito.Mockito.mock(com.auralink.creation.CreationSourceResolver.class);
      var painting = com.auralink.entity.Painting.builder().id(33L).build();
      org.mockito.Mockito.when(resolver.resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(owner)))
         .thenReturn(new com.auralink.creation.CreationSourceResolver.ResolvedSource(WorkflowModality.PAINTING, null, painting, null));
      var records = org.mockito.Mockito.mock(com.auralink.repository.CreationRepository.class);
      var service = new com.auralink.creation.CreationSubmissionService(
         org.mockito.Mockito.mock(com.auralink.creation.CreationFeatureGuard.class), new CreationExecutionProperties(), currentUser, preparer,
         org.mockito.Mockito.mock(com.auralink.creation.CreationExecutionCapabilityService.class), resolver,
         org.mockito.Mockito.mock(com.auralink.creation.CreationStateMachine.class), null, records,
         org.mockito.Mockito.mock(com.auralink.repository.CreationExecutionAttemptRepository.class),
         org.mockito.Mockito.mock(com.auralink.repository.CreationStepRepository.class),
         org.mockito.Mockito.mock(com.auralink.creation.CreationResponseMapper.class), java.time.Clock.systemUTC(),
         new VmmManagedAdmission(gate, endpoint, mapper));
      assertThatThrownBy(() -> service.submit(new com.auralink.api.v1.creation.CreationSubmissionRequest())).isInstanceOf(ApiV1Exception.class);
      org.mockito.Mockito.verifyNoInteractions(records);
      assertThat(posts).hasValue(0);
   }

   @Test void managedRequiresContextAndPrivateTokenPermissions() throws Exception {
      assertThatThrownBy(() -> client.generate(requestId, "data:image/png;base64,AQ==", 10)).isInstanceOf(ProviderExecutionException.class);
      Files.setPosixFilePermissions(Path.of(managed.getTokenFile()), PosixFilePermissions.fromString("rw-r--r--"));
      assertThat(endpoint.readiness().state()).isEqualTo(ProviderReadinessState.CONFIGURATION_INVALID); assertThat(posts).hasValue(0);
   }
   @ParameterizedTest @ValueSource(ints={3,10,30}) void realLoopbackAdapterPreservesImageDurationIdentityAndSafeWave(int duration) throws Exception {
      var staging = new ProviderArtifactStagingService(creation, new ImageContentValidator(new MediaAssetProperties()), new AudioOutputValidator());
      var adapter = new AuralinkVmmProviderAdapter(new ProviderInputValidator(creation), new ProviderDataUrlEncoder(), staging, creation, client, endpoint, new ProviderBulkheads(creation));
      ByteArrayOutputStream png = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB), "png", png);
      try (var source = staging.stageInputImage(new ByteArrayInputStream(png.toByteArray()), "image/png")) {
         var result = adapter.execute(new ProviderExecutionRequest(requestId, WorkflowOperation.PAINTING_TO_MUSIC, "auralink-vmm",
            new ProviderImageInput(source, WorkflowModality.PAINTING, null), Map.of("durationSeconds", mapper.valueToTree(duration)), context));
         try (var artifact = ((ProviderBinaryOutput)result.output()).artifact(); var stream = artifact.openStream()) {
            assertThat(artifact.mimeType()).isEqualTo("audio/wav"); assertThat(stream.readAllBytes()).isEqualTo(wave(duration));
            assertThat(new AudioOutputValidator().measureWave(new ByteArrayInputStream(wave(duration))).durationSeconds()).isEqualTo(duration);
         }
         assertThat(received.get().get("duration").intValue()).isEqualTo(duration);
         assertThat(received.get().get("image").asText()).isEqualTo("data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray()));
         assertThat(headers.get().getFirst("X-ArtLIVE-VMM-Owner-Id")).isEqualTo("7");
         assertThat(headers.get().getFirst("X-ArtLIVE-VMM-Creation-Id")).isEqualTo("11");
         assertThat(headers.get().getFirst("X-ArtLIVE-VMM-Step-Id")).isEqualTo("22");
         assertThat(headers.get().getFirst("X-ArtLIVE-VMM-Retry-Version")).isEqualTo("0");
         assertThat(headers.get().getFirst("X-ArtLIVE-VMM-Source-Painting-Id")).isEqualTo("33");
         assertThat(headers.get().getFirst("X-ArtLIVE-VMM-Request-ID")).isEqualTo(requestId);
         assertThat(posts).hasValue(1); assertThat(Files.exists(outputs.resolve("synthetic.wav"))).isFalse();
      }
   }
   @ParameterizedTest @ValueSource(strings={"DELAY","MALFORMED","MISMATCH","NULL"}) void ambiguousResponsesNeverRetry(String kind) {
      response.set(kind);
      assertThatThrownBy(() -> client.generate(requestId, "data:image/png;base64,AQ==", 10, context))
         .isInstanceOfSatisfying(ProviderExecutionException.class, ex -> assertThat(ex.category()).isEqualTo(ProviderErrorCategory.PROVIDER_OUTCOME_UNCERTAIN));
      assertThat(posts).hasValue(1);
   }
   @Test void rejectionIsNotRetried() {
      response.set("REFUSE"); assertThatThrownBy(() -> client.generate(requestId, "data:image/png;base64,AQ==", 10, context))
         .isInstanceOfSatisfying(ProviderExecutionException.class, ex -> assertThat(ex.category()).isEqualTo(ProviderErrorCategory.PROVIDER_REJECTED));
      assertThat(posts).hasValue(1);
   }
   @Test void interruptedSendStartedRemainsAmbiguousAndIsNeverRequeued() {
      var step = com.auralink.entity.CreationStep.builder().id(22L).stepIndex(0).status("RUNNING")
         .providerDispatchState("SEND_STARTED").providerRequestKey(requestId).build();
      var dispatch = com.auralink.entity.CreationStepDispatchAttempt.builder().dispatchState("SEND_STARTED").providerRequestKey(requestId).build();
      var inspector = new com.auralink.creation.CreationRecoveryStateInspector(null, null);
      var result = inspector.inspect(List.of(step), List.of(com.auralink.entity.CreationExecutionAttempt.builder().id(1L).build()), Map.of(22L, dispatch));
      assertThat(result.kind()).isEqualTo(com.auralink.creation.CreationRecoveryStateInspector.Kind.AMBIGUOUS);
      assertThat(result.errorCode()).isEqualTo("PROVIDER_DISPATCH_AMBIGUOUS");
      assertThat(posts).hasValue(0);
   }
   @Test void unsafeOutputPathRemainsRejectedByExistingAdapter() throws Exception {
      response.set("UNSAFE");
      var staging = new ProviderArtifactStagingService(creation, new ImageContentValidator(new MediaAssetProperties()), new AudioOutputValidator());
      var adapter = new AuralinkVmmProviderAdapter(new ProviderInputValidator(creation), new ProviderDataUrlEncoder(), staging, creation, client, endpoint, new ProviderBulkheads(creation));
      ByteArrayOutputStream png = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB), "png", png);
      try (var source = staging.stageInputImage(new ByteArrayInputStream(png.toByteArray()), "image/png")) {
         assertThatThrownBy(() -> adapter.execute(new ProviderExecutionRequest(requestId, WorkflowOperation.PAINTING_TO_MUSIC, "auralink-vmm",
            new ProviderImageInput(source, WorkflowModality.PAINTING, null), Map.of(), context)))
            .isInstanceOfSatisfying(ProviderExecutionException.class, ex -> assertThat(ex.category()).isEqualTo(ProviderErrorCategory.PROVIDER_OUTPUT_INVALID));
      }
      assertThat(posts).hasValue(1);
   }

   @ParameterizedTest @ValueSource(ints={2,31}) void durationOutsideContractNeverDispatches(int duration) {
      assertThatThrownBy(() -> client.generate(requestId, "data:image/png;base64,AQ==", duration, context)).isInstanceOf(ProviderExecutionException.class);
      assertThat(posts).hasValue(0);
   }
   static byte[] wave(int seconds) {
      int bytes = 8000 * seconds * 2; ByteBuffer b = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN);
      b.put("RIFF".getBytes()).putInt(36+bytes).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1);
      b.putInt(8000).putInt(16000).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(bytes);
      return b.array();
   }
}
