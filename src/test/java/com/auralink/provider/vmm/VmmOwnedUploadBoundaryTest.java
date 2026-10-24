package com.auralink.provider.vmm;

import static org.assertj.core.api.Assertions.*;
import com.auralink.config.CreationProviderClientConfiguration;
import com.auralink.config.properties.*;
import com.auralink.creation.provider.*;
import com.auralink.provider.ProviderBulkheads;
import com.auralink.provider.artifact.*;
import com.auralink.provider.http.ProviderHttpExecutor;
import com.auralink.provider.validation.*;
import com.auralink.service.media.ImageContentValidator;
import com.auralink.workflow.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer;

/** Both real managed implementations over HTTP; Python's model backend alone is synthetic. */
@EnabledIfSystemProperty(named="vmm.fixture.source", matches="/.+")
class VmmOwnedUploadBoundaryTest {
   @TempDir Path temp;
   @Test void actualCoreAdapterToManagedServiceConsumesUploadOnceAndRetainsGallery() throws Exception {
      Path fixture=Path.of(System.getProperty("vmm.fixture.source")).toRealPath();
      Path root=temp.resolve("cpu-wrapper");
      Process wrapper=new ProcessBuilder("python3", "-B", fixture.toString(), "--isolated-cpu-root", root.toString())
         .redirectError(temp.resolve("wrapper.stderr").toFile()).start();
      var mapper=new ObjectMapper();
      try {
         var line=java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return new BufferedReader(new InputStreamReader(wrapper.getInputStream())).readLine(); }
            catch(IOException e){throw new java.io.UncheckedIOException(e);}
         }).get(10,TimeUnit.SECONDS);
         assertThat(line).as(Files.readString(temp.resolve("wrapper.stderr"))).isNotNull();
         int port=mapper.readTree(line).path("port").asInt();assertThat(port).isPositive();
         var creation=new CreationProviderProperties();creation.setEnabled(true);creation.setStagingDir(temp.resolve("staging"));creation.setVmmReadTimeout(Duration.ofSeconds(3));
         var provider=new ProviderProperties();provider.getPaintingMusic().setBaseUrl("http://127.0.0.1:"+port);provider.getPaintingMusic().setOutputRoot(root.resolve("output").toString());
         var managed=new VmmManagedProperties();managed.setEnabled(true);
         Path token=Files.writeString(temp.resolve("token"),"test_only_012345678901234567890123456789");Files.setPosixFilePermissions(token,PosixFilePermissions.fromString("rw-------"));managed.setTokenFile(token.toString());
         var gate=new VmmManagedGate(managed,mapper);var endpoint=new VmmEndpointPolicy(creation,provider,gate);
         var clients=new CreationProviderClientConfiguration(creation,new RestClientBuilderConfigurer());
         try(var transport=clients.vmmProviderHttpClient()) {
            var client=new VmmHttpClient(clients.vmmProviderRestClient(transport),new ProviderHttpExecutor(mapper),mapper,creation,endpoint,gate);
            var staging=new ProviderArtifactStagingService(creation,new ImageContentValidator(new MediaAssetProperties()),new AudioOutputValidator());
            var adapter=new AuralinkVmmProviderAdapter(new ProviderInputValidator(creation),new ProviderDataUrlEncoder(),staging,creation,client,endpoint,new ProviderBulkheads(creation));
            var png=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",png);
            try(var image=staging.stageInputImage(new ByteArrayInputStream(png.toByteArray()),"image/png")) {
               var request=new ProviderExecutionRequest("upload-once",WorkflowOperation.PAINTING_TO_MUSIC,"auralink-vmm",new ProviderImageInput(image,WorkflowModality.PAINTING,null),Map.of("durationSeconds",mapper.valueToTree(3)),new VmmExecutionContext(7,11,22,0,null,44L));
               var result=adapter.execute(request);
               try(var wav=((ProviderBinaryOutput)result.output()).artifact();var bytes=wav.openStream()) {
                  assertThat(new AudioOutputValidator().measureWave(bytes).durationSeconds()).isEqualTo(3);
               }
               assertThatThrownBy(() -> adapter.execute(request)).isInstanceOfSatisfying(ProviderExecutionException.class,e -> assertThat(e.category()).isEqualTo(ProviderErrorCategory.PROVIDER_REJECTED));
               assertThat(Files.readString(root.resolve("backend-count"))).isEqualTo("1");
               var state=mapper.readTree(Files.readAllBytes(root.resolve("state/gate.json"))).path("requests").path("upload-once");
               assertThat(state.path("source_kind").asText()).isEqualTo("OWNED_UPLOAD");assertThat(state.path("source_asset_id").asText()).isEqualTo("44");
               assertThat(state.has("source_painting_id")).isFalse();assertThat(state.path("state").asText()).isEqualTo("SUCCEEDED");assertThat(state.path("image_sha256").asText()).isEqualTo(image.sha256());
               var gallery=adapter.execute(new ProviderExecutionRequest("gallery-once",WorkflowOperation.PAINTING_TO_MUSIC,"auralink-vmm",request.input(),request.parameters(),new VmmExecutionContext(7,12,23,0,33L)));
               ((ProviderBinaryOutput)gallery.output()).artifact().close();
               assertThat(Files.readString(root.resolve("backend-count"))).isEqualTo("2");
            }
         }
      } finally {
         wrapper.getOutputStream().close();
         if(!wrapper.waitFor(5,TimeUnit.SECONDS)){wrapper.destroyForcibly();wrapper.waitFor(5,TimeUnit.SECONDS);}
      }
      assertThat(wrapper.exitValue()).as(Files.readString(temp.resolve("wrapper.stderr"))).isZero();
   }
}
