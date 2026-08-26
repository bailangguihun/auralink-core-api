package com.auralink.provider.composite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.net.URI;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.creation.provider.ProviderBinaryOutput;
import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;
import com.auralink.creation.provider.ProviderExecutionRequest;
import com.auralink.creation.provider.ProviderTextInput;
import com.auralink.provider.ProviderTestFixtures;
import com.auralink.provider.artifact.ProviderArtifact;
import com.auralink.provider.qwen.PaintingPromptPlan;
import com.auralink.provider.qwen.QwenEndpointPolicy;
import com.auralink.provider.qwen.QwenPaintingPromptPlanner;
import com.auralink.provider.seedream.PoemPlanSeedreamPromptBuilder;
import com.auralink.provider.seedream.SeedreamEndpointPolicy;
import com.auralink.provider.seedream.SeedreamImageGenerator;
import com.auralink.provider.validation.ProviderInputValidator;
import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowOperation;

class QwenSeedreamCompositeProviderAdapterTest {

    @TempDir
    java.nio.file.Path temporaryDirectory;

    private QwenPaintingPromptPlanner planner;
    private SeedreamImageGenerator seedream;
    private QwenEndpointPolicy qwenPolicy;
    private SeedreamEndpointPolicy seedreamPolicy;
    private QwenSeedreamCompositeProviderAdapter adapter;

    @BeforeEach
    void setUp() {
        CreationProviderProperties properties =
                ProviderTestFixtures.properties(temporaryDirectory.resolve("staging"));
        planner = mock(QwenPaintingPromptPlanner.class);
        seedream = mock(SeedreamImageGenerator.class);
        qwenPolicy = mock(QwenEndpointPolicy.class);
        seedreamPolicy = mock(SeedreamEndpointPolicy.class);
        when(qwenPolicy.resolveChatCompletionsEndpoint())
                .thenReturn(URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"));
        when(seedreamPolicy.resolveGenerationEndpoint())
                .thenReturn(URI.create("https://ark.cn-beijing.volces.com/api/v3/images/generations"));
        adapter = new QwenSeedreamCompositeProviderAdapter(
                new ProviderInputValidator(properties),
                planner,
                new PoemPlanSeedreamPromptBuilder(),
                seedream,
                qwenPolicy,
                seedreamPolicy);
    }

    @Test
    void validPoemCallsQwenOnceThenSeedreamOnceWithSameCorrelationId() {
        PaintingPromptPlan plan = validPlan();
        when(planner.create("composite-1", "孤帆远影碧空尽")).thenReturn(plan);
        ProviderArtifact artifact = ProviderTestFixtures.staging(
                ProviderTestFixtures.properties(temporaryDirectory.resolve("output")))
                .stageOutputImage(new ByteArrayInputStream(ProviderTestFixtures.png()), "image/png");
        when(seedream.generate(eq("composite-1"), any(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(artifact);

        var result = adapter.execute(new ProviderExecutionRequest(
                "composite-1",
                WorkflowOperation.POEM_TO_PAINTING,
                "qwen3vl-seedream5",
                new ProviderTextInput("孤帆远影碧空尽", WorkflowModality.POEM)));

        verify(planner).create("composite-1", "孤帆远影碧空尽");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(seedream).generate(eq("composite-1"), prompt.capture(), org.mockito.ArgumentMatchers.isNull());
        assertThat(prompt.getValue()).contains("已验证", plan.finalPrompt(), "不添加无依据的新主体");
        assertThat(result.requestId()).isEqualTo("composite-1");
        assertThat(result.outputModality()).isEqualTo(WorkflowModality.PAINTING);
        assertThat(((ProviderBinaryOutput) result.output()).artifact()).isSameAs(artifact);
        artifact.close();
    }

    @Test
    void invalidQwenPlanStopsBeforeSeedream() {
        when(planner.create(any(), any())).thenThrow(new ProviderExecutionException(
                ProviderErrorCategory.PROVIDER_INVALID_RESPONSE,
                "Qwen plan failed validation"));

        assertThatThrownBy(() -> adapter.execute(request()))
                .isInstanceOf(ProviderExecutionException.class)
                .extracting(exception -> ((ProviderExecutionException) exception).category())
                .isEqualTo(ProviderErrorCategory.PROVIDER_INVALID_RESPONSE);
        verify(seedream, never()).generate(any(), any(), any());
    }

    @Test
    void qwenTimeoutStopsBeforeSeedream() {
        when(planner.create(any(), any())).thenThrow(new ProviderExecutionException(
                ProviderErrorCategory.PROVIDER_TIMEOUT,
                "Provider request timed out"));

        assertThatThrownBy(() -> adapter.execute(request()))
                .isInstanceOf(ProviderExecutionException.class)
                .extracting(exception -> ((ProviderExecutionException) exception).category())
                .isEqualTo(ProviderErrorCategory.PROVIDER_TIMEOUT);
        verify(seedream, never()).generate(any(), any(), any());
    }

    @Test
    void seedreamFailureIsReturnedWithoutWholeCompositeRetry() {
        when(planner.create(any(), any())).thenReturn(validPlan());
        when(seedream.generate(any(), any(), any())).thenThrow(new ProviderExecutionException(
                ProviderErrorCategory.PROVIDER_UNAVAILABLE,
                "Provider service is unavailable"));

        assertThatThrownBy(() -> adapter.execute(request()))
                .isInstanceOf(ProviderExecutionException.class)
                .extracting(exception -> ((ProviderExecutionException) exception).category())
                .isEqualTo(ProviderErrorCategory.PROVIDER_UNAVAILABLE);
        verify(planner).create(any(), any());
        verify(seedream).generate(any(), any(), any());
    }

    @Test
    void invalidOutputStagingStopsBeforeQwenAndSeedreamCalls() {
        org.mockito.Mockito.doThrow(new ProviderExecutionException(
                ProviderErrorCategory.PROVIDER_CONFIGURATION_INVALID,
                "Provider staging root is unavailable or unsafe"))
                .when(seedream).prepare();

        assertThatThrownBy(() -> adapter.execute(request()))
                .isInstanceOf(ProviderExecutionException.class)
                .extracting(exception -> ((ProviderExecutionException) exception).category())
                .isEqualTo(ProviderErrorCategory.PROVIDER_CONFIGURATION_INVALID);
        verify(planner, never()).create(any(), any());
        verify(seedream, never()).generate(any(), any(), any());
    }

    @Test
    void invalidSeedreamConfigurationStopsBeforePaidQwenCall() {
        when(seedreamPolicy.resolveGenerationEndpoint()).thenThrow(new ProviderExecutionException(
                ProviderErrorCategory.PROVIDER_CONFIGURATION_MISSING,
                "Seedream configuration is incomplete"));

        assertThatThrownBy(() -> adapter.execute(request()))
                .isInstanceOf(ProviderExecutionException.class)
                .extracting(exception -> ((ProviderExecutionException) exception).category())
                .isEqualTo(ProviderErrorCategory.PROVIDER_CONFIGURATION_MISSING);
        verify(planner, never()).create(any(), any());
        verify(seedream, never()).generate(any(), any(), any());
    }

    private ProviderExecutionRequest request() {
        return new ProviderExecutionRequest(
                "composite-failure",
                WorkflowOperation.POEM_TO_PAINTING,
                "qwen3vl-seedream5",
                new ProviderTextInput("江流天地外", WorkflowModality.POEM));
    }

    private PaintingPromptPlan validPlan() {
        return new PaintingPromptPlan(
                "1", "孤舟", "江面", "纵深构图", "淡墨", "水墨皴染", "清远", "江面孤舟淡墨国画");
    }
}
