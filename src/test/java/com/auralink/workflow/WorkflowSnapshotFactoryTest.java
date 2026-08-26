package com.auralink.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.auralink.api.v1.workflow.WorkflowDefinitionRequest;
import com.auralink.config.properties.WorkflowProperties;
import com.auralink.workflow.capability.WorkflowCapabilityRegistry;
import com.auralink.workflow.graph.WorkflowCanonicalizer;
import com.auralink.workflow.graph.WorkflowGraphCodec;
import com.auralink.workflow.graph.WorkflowValidationResult;
import com.auralink.workflow.graph.WorkflowValidator;
import com.auralink.workflow.snapshot.WorkflowSnapshotFactory;
import com.auralink.workflow.snapshot.WorkflowSnapshotResult;
import com.fasterxml.jackson.databind.ObjectMapper;

class WorkflowSnapshotFactoryTest {

    @Test
    void snapshotIsCanonicalDetachedAndIndependentOfLaterDefinitionChanges() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        WorkflowGraphCodec codec = new WorkflowGraphCodec(mapper);
        WorkflowValidator validator = new WorkflowValidator(
                new WorkflowProperties(),
                new WorkflowCapabilityRegistry(),
                codec,
                new WorkflowCanonicalizer(codec));
        WorkflowDefinitionRequest original = WorkflowTestDefinitions.definition(
                WorkflowModality.TEXT_DESCRIPTION,
                WorkflowOperation.TEXT_TO_PAINTING,
                WorkflowOperation.PAINTING_TO_MUSIC);
        WorkflowValidationResult validated = validator.validate(original);
        WorkflowSnapshotFactory factory = new WorkflowSnapshotFactory(codec);

        WorkflowSnapshotResult snapshot = factory.create(
                "123e4567-e89b-12d3-a456-426614174000",
                "Original name",
                1,
                validated.canonicalization().graph());
        String before = snapshot.canonicalJson();

        original.setName("Updated name");
        original.getGraph().getNodes().clear();
        original.getGraph().getEdges().clear();

        assertThat(snapshot.canonicalJson()).isEqualTo(before);
        assertThat(snapshot.snapshot().snapshotVersion()).isEqualTo(1);
        assertThat(snapshot.snapshot().workflowId())
                .isEqualTo("123e4567-e89b-12d3-a456-426614174000");
        assertThat(snapshot.snapshot().workflowName()).isEqualTo("Original name");
        assertThat(snapshot.snapshot().workflowSchemaVersion()).isEqualTo(1);
        assertThat(snapshot.snapshot().graph().nodes()).hasSize(3);
        assertThat(before).doesNotContain(
                "userId", "owner", "createdAt", "updatedAt", "apiKey", "secret", "internalId");
        assertThat(before).doesNotContain("\n", "\r", "  ");
    }
}
