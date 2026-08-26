package com.auralink.provider;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.config.properties.ProviderProperties;
import com.auralink.creation.provider.CreationProviderAdapter;
import com.auralink.creation.provider.ProviderAdapterRegistry;
import com.auralink.creation.provider.ProviderBinaryOutput;
import com.auralink.creation.provider.ProviderExecutionRequest;
import com.auralink.creation.provider.ProviderExecutionResult;
import com.auralink.creation.provider.ProviderImageInput;
import com.auralink.creation.provider.ProviderTextInput;
import com.auralink.creation.provider.ProviderTextOutput;
import com.auralink.provider.artifact.ProviderArtifact;
import com.auralink.provider.artifact.ProviderArtifactStagingService;
import com.auralink.provider.composite.QwenSeedreamCompositeProviderAdapter;
import com.auralink.provider.http.ProviderHttpExecutor;
import com.auralink.provider.qwen.PaintingPoemResultValidator;
import com.auralink.provider.qwen.PaintingPromptPlanValidator;
import com.auralink.provider.qwen.PaintingToPoemPromptBuilder;
import com.auralink.provider.qwen.QwenCreationHttpClient;
import com.auralink.provider.qwen.QwenEndpointPolicy;
import com.auralink.provider.qwen.QwenPaintingPromptPlanner;
import com.auralink.provider.qwen.QwenPaintingToPoemProviderAdapter;
import com.auralink.provider.seedream.ImageToPaintingPromptBuilder;
import com.auralink.provider.seedream.PoemPlanSeedreamPromptBuilder;
import com.auralink.provider.seedream.SeedreamEndpointPolicy;
import com.auralink.provider.seedream.SeedreamHttpClient;
import com.auralink.provider.seedream.SeedreamImageGenerator;
import com.auralink.provider.seedream.SeedreamProviderAdapter;
import com.auralink.provider.seedream.TextToPaintingPromptBuilder;
import com.auralink.provider.validation.ProviderDataUrlEncoder;
import com.auralink.provider.validation.ProviderInputValidator;
import com.auralink.provider.vmm.AuralinkVmmProviderAdapter;
import com.auralink.provider.vmm.VmmEndpointPolicy;
import com.auralink.provider.vmm.VmmHttpClient;
import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowOperation;
import com.auralink.workflow.capability.WorkflowCapabilityRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Test-source executable loaded beside the actual packaged Boot JAR. */
public final class PackagedProviderContractHarness {

    private PackagedProviderContractHarness() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("auralink-packaged-provider-contract-");
        try {
            ContractCounts counts = run(root);
            System.out.println("PACKAGED_PROVIDER_CONTRACT_CHAIN_OK "
                    + "textSeedream=" + counts.textSeedream()
                    + " imageSeedream=" + counts.imageSeedream()
                    + " compositeQwen=" + counts.compositeQwen()
                    + " compositeSeedream=" + counts.compositeSeedream()
                    + " poemQwen=" + counts.poemQwen()
                    + " vmm=" + counts.vmm());
        } finally {
            deleteOwnedTree(root);
        }
    }

    static ContractCounts run(Path root) throws Exception {
        Files.createDirectories(root);
        CreationProviderProperties creation = ProviderTestFixtures.properties(root.resolve("staging"));
        ProviderProperties providers = configuredProviders(root);
        ProviderArtifactStagingService staging = ProviderTestFixtures.staging(creation);
        ProviderInputValidator inputValidator = new ProviderInputValidator(creation);
        ProviderDataUrlEncoder dataUrlEncoder = new ProviderDataUrlEncoder();
        ProviderBulkheads bulkheads = new ProviderBulkheads(creation);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ProviderHttpExecutor http = new ProviderHttpExecutor(mapper);
        SeedreamEndpointPolicy seedreamPolicy = new SeedreamEndpointPolicy(creation, providers);
        QwenEndpointPolicy qwenPolicy = new QwenEndpointPolicy(creation, providers);

        try (LocalProviderHttpFixture textArk = arkFixture();
                LocalProviderHttpFixture imageArk = arkFixture();
                LocalProviderHttpFixture compositeQwen = qwenFixture(planJson());
                LocalProviderHttpFixture compositeArk = arkFixture();
                LocalProviderHttpFixture poemQwen = qwenFixture(poemJson());
                LocalProviderHttpFixture vmm = new LocalProviderHttpFixture("/api/generate_with_image")) {
            SeedreamProviderAdapter textAdapter = seedreamAdapter(
                    textArk, mapper, http, creation, providers, staging,
                    inputValidator, dataUrlEncoder, bulkheads, seedreamPolicy);
            SeedreamProviderAdapter imageAdapter = seedreamAdapter(
                    imageArk, mapper, http, creation, providers, staging,
                    inputValidator, dataUrlEncoder, bulkheads, seedreamPolicy);

            QwenCreationHttpClient planClient = qwenClient(
                    compositeQwen, mapper, http, creation, providers, bulkheads);
            QwenPaintingPromptPlanner planner = new QwenPaintingPromptPlanner(
                    planClient, new PaintingPromptPlanValidator(mapper, creation));
            SeedreamImageGenerator compositeGenerator = seedreamGenerator(
                    compositeArk, mapper, http, creation, providers, staging, bulkheads);
            QwenSeedreamCompositeProviderAdapter compositeAdapter =
                    new QwenSeedreamCompositeProviderAdapter(
                            inputValidator,
                            planner,
                            new PoemPlanSeedreamPromptBuilder(),
                            compositeGenerator,
                            qwenPolicy,
                            seedreamPolicy);

            QwenPaintingToPoemProviderAdapter poemAdapter =
                    new QwenPaintingToPoemProviderAdapter(
                            inputValidator,
                            dataUrlEncoder,
                            creation,
                            qwenClient(poemQwen, mapper, http, creation, providers, bulkheads),
                            qwenPolicy,
                            new PaintingToPoemPromptBuilder(),
                            new PaintingPoemResultValidator(mapper, creation));

            Path vmmRoot = root.resolve("vmm-output");
            Files.createDirectory(vmmRoot);
            Files.write(vmmRoot.resolve("contract.wav"), ProviderTestFixtures.wave());
            providers.getPaintingMusic().setBaseUrl(withoutTrailingSlash(vmm.uri("/")));
            providers.getPaintingMusic().setOutputRoot(vmmRoot.toString());
            vmm.respondJson(200, "{\"success\":true,\"fileName\":\"contract.wav\","
                    + "\"full_path\":\"/must/be/ignored.wav\"}");
            VmmEndpointPolicy vmmPolicy = new VmmEndpointPolicy(creation, providers);
            AuralinkVmmProviderAdapter vmmAdapter = new AuralinkVmmProviderAdapter(
                    inputValidator,
                    dataUrlEncoder,
                    staging,
                    creation,
                    new VmmHttpClient(restClient(), http, mapper, creation, vmmPolicy),
                    vmmPolicy,
                    bulkheads);

            ProviderAdapterRegistry registry = new ProviderAdapterRegistry(
                    List.of(textAdapter, compositeAdapter, poemAdapter, vmmAdapter),
                    new WorkflowCapabilityRegistry());
            require(registry.bindings().size() == 5, "provider registry binding count");
            require(registry.find(WorkflowOperation.PAINTING_TO_VIDEO, "reserved-video").isEmpty(),
                    "reserved video adapter absence");

            ProviderExecutionResult textResult = textAdapter.execute(new ProviderExecutionRequest(
                    "packaged-text",
                    WorkflowOperation.TEXT_TO_PAINTING,
                    "seedream-5",
                    new ProviderTextInput("松风穿谷，远山含烟", WorkflowModality.TEXT_DESCRIPTION)));
            closePaintingResult(textResult);

            try (ProviderArtifact imageSource = input(staging)) {
                ProviderExecutionResult imageResult = imageAdapter.execute(new ProviderExecutionRequest(
                        "packaged-image",
                        WorkflowOperation.IMAGE_TO_PAINTING,
                        "seedream-5",
                        new ProviderImageInput(imageSource, WorkflowModality.IMAGE, null)));
                closePaintingResult(imageResult);
            }

            ProviderExecutionResult compositeResult = compositeAdapter.execute(new ProviderExecutionRequest(
                    "packaged-composite",
                    WorkflowOperation.POEM_TO_PAINTING,
                    "qwen3vl-seedream5",
                    new ProviderTextInput("孤帆远影碧空尽", WorkflowModality.POEM)));
            closePaintingResult(compositeResult);

            try (ProviderArtifact paintingSource = input(staging)) {
                ProviderExecutionResult poemResult = poemAdapter.execute(new ProviderExecutionRequest(
                        "packaged-poem",
                        WorkflowOperation.PAINTING_TO_POEM,
                        "qwen3-vl-plus",
                        new ProviderImageInput(paintingSource, WorkflowModality.PAINTING, null)));
                ProviderTextOutput poem = (ProviderTextOutput) poemResult.output();
                require(poem.lines().size() == 4, "four-line poem result");
                require(poem.text().equals(String.join("\n", poem.lines())), "poem text consistency");
            }

            try (ProviderArtifact paintingSource = input(staging)) {
                ProviderExecutionResult musicResult = vmmAdapter.execute(new ProviderExecutionRequest(
                        "packaged-vmm",
                        WorkflowOperation.PAINTING_TO_MUSIC,
                        "auralink-vmm",
                        new ProviderImageInput(paintingSource, WorkflowModality.PAINTING, null)));
                ProviderBinaryOutput audio = (ProviderBinaryOutput) musicResult.output();
                require("audio/wav".equals(audio.mimeType()), "VMM WAV output");
                audio.artifact().close();
            }

            require(stagingEmpty(creation.getStagingDir()), "transient artifact cleanup");
            require(!Files.exists(vmmRoot.resolve("contract.wav")), "VMM source output cleanup");
            require(textArk.requestCount() == 1, "TEXT_TO_PAINTING call count");
            require(imageArk.requestCount() == 1, "IMAGE_TO_PAINTING call count");
            require(compositeQwen.requestCount() == 1, "POEM_TO_PAINTING Qwen call count");
            require(compositeArk.requestCount() == 1, "POEM_TO_PAINTING Seedream call count");
            require(poemQwen.requestCount() == 1, "PAINTING_TO_POEM call count");
            require(vmm.requestCount() == 1, "PAINTING_TO_MUSIC call count");
            require(!imageArk.lastRequest().bodyText().contains("http://"), "internal image transport");
            require(poemQwen.lastRequest().bodyText().contains("data:image/png;base64,"),
                    "Qwen image Data URL");
            require(!vmm.lastRequest().bodyText().contains(root.toString()), "VMM path isolation");

            return new ContractCounts(1, 1, 1, 1, 1, 1);
        }
    }

    private static SeedreamProviderAdapter seedreamAdapter(
            LocalProviderHttpFixture fixture,
            ObjectMapper mapper,
            ProviderHttpExecutor http,
            CreationProviderProperties creation,
            ProviderProperties providers,
            ProviderArtifactStagingService staging,
            ProviderInputValidator inputValidator,
            ProviderDataUrlEncoder dataUrlEncoder,
            ProviderBulkheads bulkheads,
            SeedreamEndpointPolicy policy) {
        return new SeedreamProviderAdapter(
                inputValidator,
                dataUrlEncoder,
                new TextToPaintingPromptBuilder(),
                new ImageToPaintingPromptBuilder(),
                seedreamGenerator(fixture, mapper, http, creation, providers, staging, bulkheads),
                policy,
                creation);
    }

    private static SeedreamImageGenerator seedreamGenerator(
            LocalProviderHttpFixture fixture,
            ObjectMapper mapper,
            ProviderHttpExecutor http,
            CreationProviderProperties creation,
            ProviderProperties providers,
            ProviderArtifactStagingService staging,
            ProviderBulkheads bulkheads) {
        SeedreamHttpClient client = new SeedreamHttpClient(
                restClient(), http, mapper, creation, providers,
                () -> fixture.uri("/images/generations"));
        return new SeedreamImageGenerator(
                client,
                ignoredUrl -> staging.stageOutputImage(
                        new ByteArrayInputStream(ProviderTestFixtures.png()), "image/png"),
                bulkheads);
    }

    private static QwenCreationHttpClient qwenClient(
            LocalProviderHttpFixture fixture,
            ObjectMapper mapper,
            ProviderHttpExecutor http,
            CreationProviderProperties creation,
            ProviderProperties providers,
            ProviderBulkheads bulkheads) {
        return new QwenCreationHttpClient(
                restClient(), http, mapper, creation, providers,
                () -> fixture.uri("/chat/completions"), bulkheads);
    }

    private static RestClient restClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(2_000);
        return RestClient.builder().requestFactory(factory).build();
    }

    private static LocalProviderHttpFixture arkFixture() throws Exception {
        LocalProviderHttpFixture fixture = new LocalProviderHttpFixture("/images/generations");
        fixture.respondJson(200, "{\"data\":[{\"url\":\"https://signed.example/image.png\"}]}");
        return fixture;
    }

    private static LocalProviderHttpFixture qwenFixture(String content) throws Exception {
        LocalProviderHttpFixture fixture = new LocalProviderHttpFixture("/chat/completions");
        ObjectMapper mapper = new ObjectMapper();
        var envelope = mapper.createObjectNode();
        envelope.putArray("choices").addObject().putObject("message").put("content", content);
        fixture.respondJson(200, mapper.writeValueAsString(envelope));
        return fixture;
    }

    private static ProviderProperties configuredProviders(Path root) {
        ProviderProperties providers = new ProviderProperties();
        providers.getSeedream().setApiKey("packaged-synthetic-seedream-key");
        providers.getSeedream().setBaseUrl("https://ark.cn-beijing.volces.com/api/v3");
        providers.getSeedream().setModel("seedream-contract-model");
        providers.getQwen().setApiKey("packaged-synthetic-qwen-key");
        providers.getQwen().setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        providers.getQwen().setModel("qwen3-vl-plus");
        providers.getPaintingMusic().setOutputRoot(root.resolve("vmm-output").toString());
        return providers;
    }

    private static ProviderArtifact input(ProviderArtifactStagingService staging) {
        return staging.stageInputImage(
                new ByteArrayInputStream(ProviderTestFixtures.png()), "image/png");
    }

    private static void closePaintingResult(ProviderExecutionResult result) {
        ProviderBinaryOutput output = (ProviderBinaryOutput) result.output();
        require(result.outputModality() == WorkflowModality.PAINTING, "Painting output modality");
        require(output.width() != null && output.width() > 0, "Painting image dimensions");
        output.artifact().close();
    }

    private static String withoutTrailingSlash(java.net.URI uri) {
        String value = uri.toString();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static boolean stagingEmpty(Path root) throws Exception {
        if (!Files.exists(root)) {
            return true;
        }
        try (var files = Files.list(root)) {
            return files.findAny().isEmpty();
        }
    }

    private static void require(boolean condition, String check) {
        if (!condition) {
            throw new IllegalStateException("Packaged provider contract failed: " + check);
        }
    }

    private static String planJson() {
        return "{\"schemaVersion\":\"1\",\"subject\":\"孤舟\",\"scene\":\"暮江\","
                + "\"composition\":\"远山近舟\",\"colorPalette\":\"淡墨赭石\","
                + "\"brushwork\":\"水墨皴染\",\"artisticConception\":\"清寂悠远\","
                + "\"finalPrompt\":\"暮江孤舟与远山的淡墨国画\"}";
    }

    private static String poemJson() {
        return "{\"schemaVersion\":\"1\",\"title\":null,"
                + "\"lines\":[\"远岫含烟入暮云\",\"孤舟一叶过江津\",\"疏林淡墨留清韵\",\"月照寒波不染尘\"],"
                + "\"text\":\"远岫含烟入暮云\\n孤舟一叶过江津\\n疏林淡墨留清韵\\n月照寒波不染尘\"}";
    }

    private static void deleteOwnedTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    record ContractCounts(
            int textSeedream,
            int imageSeedream,
            int compositeQwen,
            int compositeSeedream,
            int poemQwen,
            int vmm) {
    }
}
