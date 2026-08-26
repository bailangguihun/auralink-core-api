package com.auralink.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.auralink.creation.provider.CreationProviderAdapter;
import com.auralink.creation.provider.ProviderAdapterBinding;
import com.auralink.creation.provider.ProviderAdapterRegistry;
import com.auralink.creation.provider.ProviderReadinessState;
import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowOperation;
import com.auralink.workflow.capability.WorkflowCapabilityRegistry;

class ProviderAdapterRegistryTest {

    @Test
    void exactlyMatchesFiveEnabledWorkflowProviderBindings() {
        CreationProviderAdapter seedream = adapter(
                binding(WorkflowOperation.TEXT_TO_PAINTING, "seedream-5",
                        WorkflowModality.TEXT_DESCRIPTION, WorkflowModality.PAINTING),
                binding(WorkflowOperation.IMAGE_TO_PAINTING, "seedream-5",
                        WorkflowModality.IMAGE, WorkflowModality.PAINTING));
        CreationProviderAdapter composite = adapter(binding(
                WorkflowOperation.POEM_TO_PAINTING, "qwen3vl-seedream5",
                WorkflowModality.POEM, WorkflowModality.PAINTING));
        CreationProviderAdapter qwen = adapter(binding(
                WorkflowOperation.PAINTING_TO_POEM, "qwen3-vl-plus",
                WorkflowModality.PAINTING, WorkflowModality.POEM));
        CreationProviderAdapter vmm = adapter(binding(
                WorkflowOperation.PAINTING_TO_MUSIC, "auralink-vmm",
                WorkflowModality.PAINTING, WorkflowModality.AUDIO));

        ProviderAdapterRegistry registry = new ProviderAdapterRegistry(
                List.of(seedream, composite, qwen, vmm), new WorkflowCapabilityRegistry());

        assertThat(registry.bindings()).containsExactly(
                binding(WorkflowOperation.TEXT_TO_PAINTING, "seedream-5",
                        WorkflowModality.TEXT_DESCRIPTION, WorkflowModality.PAINTING),
                binding(WorkflowOperation.POEM_TO_PAINTING, "qwen3vl-seedream5",
                        WorkflowModality.POEM, WorkflowModality.PAINTING),
                binding(WorkflowOperation.IMAGE_TO_PAINTING, "seedream-5",
                        WorkflowModality.IMAGE, WorkflowModality.PAINTING),
                binding(WorkflowOperation.PAINTING_TO_MUSIC, "auralink-vmm",
                        WorkflowModality.PAINTING, WorkflowModality.AUDIO),
                binding(WorkflowOperation.PAINTING_TO_POEM, "qwen3-vl-plus",
                        WorkflowModality.PAINTING, WorkflowModality.POEM));
        assertThat(registry.find(WorkflowOperation.TEXT_TO_PAINTING, "seedream-5"))
                .contains(seedream);
        assertThat(registry.find(WorkflowOperation.TEXT_TO_PAINTING, "Seedream-5")).isEmpty();
        assertThat(registry.find(WorkflowOperation.TEXT_TO_PAINTING, null)).isEmpty();
        assertThat(registry.find(WorkflowOperation.PAINTING_TO_VIDEO, "reserved-video")).isEmpty();
        assertThat(registry.implementationReadiness(
                WorkflowOperation.PAINTING_TO_VIDEO, "reserved-video").state())
                .isEqualTo(ProviderReadinessState.RESERVED_DISABLED);
    }

    @Test
    void rejectsMissingEnabledWorkflowAdapter() {
        CreationProviderAdapter onlySeedream = adapter(
                binding(WorkflowOperation.TEXT_TO_PAINTING, "seedream-5",
                        WorkflowModality.TEXT_DESCRIPTION, WorkflowModality.PAINTING),
                binding(WorkflowOperation.IMAGE_TO_PAINTING, "seedream-5",
                        WorkflowModality.IMAGE, WorkflowModality.PAINTING));

        assertThatThrownBy(() -> new ProviderAdapterRegistry(
                List.of(onlySeedream), new WorkflowCapabilityRegistry()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("seedream-5");
    }

    @Test
    void rejectsReservedVideoOrUnknownExtraAdapter() {
        CreationProviderAdapter video = adapter(binding(
                WorkflowOperation.PAINTING_TO_VIDEO, "reserved-video",
                WorkflowModality.PAINTING, WorkflowModality.VIDEO));

        assertThatThrownBy(() -> new ProviderAdapterRegistry(
                List.of(video), new WorkflowCapabilityRegistry()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsDuplicateExactBinding() {
        ProviderAdapterBinding binding = binding(
                WorkflowOperation.TEXT_TO_PAINTING, "seedream-5",
                WorkflowModality.TEXT_DESCRIPTION, WorkflowModality.PAINTING);

        assertThatThrownBy(() -> new ProviderAdapterRegistry(
                List.of(adapter(binding), adapter(binding)), new WorkflowCapabilityRegistry()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Duplicate creation provider adapter binding");
    }

    private CreationProviderAdapter adapter(ProviderAdapterBinding... bindings) {
        CreationProviderAdapter adapter = mock(CreationProviderAdapter.class);
        when(adapter.bindings()).thenReturn(List.of(bindings));
        return adapter;
    }

    private ProviderAdapterBinding binding(
            WorkflowOperation operation,
            String provider,
            WorkflowModality input,
            WorkflowModality output) {
        return new ProviderAdapterBinding(operation, provider, input, output);
    }
}
