package com.auralink.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.auralink.workflow.capability.WorkflowCapabilityRegistry;
import com.auralink.workflow.capability.WorkflowOperationCapability;

class WorkflowCapabilityRegistryTest {

    @Test
    void exposesExactlyTheFrozenDefinitionCatalogWithoutExecutionAvailability() {
        WorkflowCapabilityRegistry registry = new WorkflowCapabilityRegistry();

        assertThat(registry.sourceModalities()).containsExactly(
                WorkflowModality.TEXT_DESCRIPTION,
                WorkflowModality.POEM,
                WorkflowModality.IMAGE,
                WorkflowModality.PAINTING);
        assertThat(registry.operations()).hasSize(6);
        assertThat(registry.operations()).filteredOn(WorkflowOperationCapability::definitionEnabled)
                .hasSize(5);
        assertThat(registry.operations()).noneMatch(WorkflowOperationCapability::executionAvailable);

        Map<WorkflowOperation, String> providers = registry.operations().stream()
                .collect(java.util.stream.Collectors.toMap(
                        WorkflowOperationCapability::operation,
                        capability -> capability.providers().get(0).code()));
        assertThat(providers).containsExactlyInAnyOrderEntriesOf(Map.of(
                WorkflowOperation.TEXT_TO_PAINTING, "seedream-5",
                WorkflowOperation.POEM_TO_PAINTING, "qwen3vl-seedream5",
                WorkflowOperation.IMAGE_TO_PAINTING, "seedream-5",
                WorkflowOperation.PAINTING_TO_MUSIC, "auralink-vmm",
                WorkflowOperation.PAINTING_TO_POEM, "qwen3-vl-plus",
                WorkflowOperation.PAINTING_TO_VIDEO, "reserved-video"));

        WorkflowOperationCapability video = registry.require(WorkflowOperation.PAINTING_TO_VIDEO);
        assertThat(video.definitionEnabled()).isFalse();
        assertThat(video.executionAvailable()).isFalse();
        assertThat(video.terminalOutput()).isTrue();
        assertThat(video.availabilityReason())
                .isEqualTo(WorkflowCapabilityRegistry.VIDEO_RESERVED_REASON);
        assertThat(video.providers().get(0).definitionEnabled()).isFalse();
        assertThat(registry.operations()).flatExtracting(WorkflowOperationCapability::providers)
                .allSatisfy(provider -> {
                    assertThat(provider.executionAvailable()).isFalse();
                    assertThat(provider.parameterSchema().type()).isEqualTo("object");
                    assertThat(provider.parameterSchema().properties()).isEmpty();
                    assertThat(provider.parameterSchema().additionalProperties()).isFalse();
                });
    }

    @Test
    void registryUsesExactCaseSensitiveOperationAndProviderCodes() {
        WorkflowCapabilityRegistry registry = new WorkflowCapabilityRegistry();

        assertThat(registry.find("TEXT_TO_PAINTING")).isPresent();
        assertThat(registry.find("text_to_painting")).isEmpty();
        assertThat(registry.find(null)).isEmpty();
        assertThat(registry.require(WorkflowOperation.TEXT_TO_PAINTING)
                .allowsProvider("seedream-5")).isTrue();
        assertThat(registry.require(WorkflowOperation.TEXT_TO_PAINTING)
                .allowsProvider("Seedream-5")).isFalse();
    }
}
