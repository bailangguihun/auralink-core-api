package com.auralink.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.auralink.api.v1.workflow.WorkflowDefinitionRequest;
import com.auralink.config.properties.WorkflowProperties;
import com.auralink.workflow.capability.WorkflowCapabilityRegistry;
import com.auralink.workflow.graph.WorkflowCanonicalizer;
import com.auralink.workflow.graph.WorkflowEdgeRequest;
import com.auralink.workflow.graph.WorkflowGraphCodec;
import com.auralink.workflow.graph.WorkflowNodeRequest;
import com.auralink.workflow.graph.WorkflowParameters;
import com.auralink.workflow.graph.WorkflowValidationResult;
import com.auralink.workflow.graph.WorkflowValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

class WorkflowValidatorTest {

    private WorkflowProperties properties;
    private ObjectMapper objectMapper;
    private WorkflowValidator validator;

    @BeforeEach
    void setUp() {
        properties = new WorkflowProperties();
        objectMapper = new ObjectMapper().findAndRegisterModules();
        WorkflowGraphCodec codec = new WorkflowGraphCodec(objectMapper);
        validator = new WorkflowValidator(
                properties,
                new WorkflowCapabilityRegistry(),
                codec,
                new WorkflowCanonicalizer(codec));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validDefinitions")
    void acceptsEveryFrozenWorkflowPath(
            String name,
            WorkflowDefinitionRequest request,
            WorkflowModality expectedTerminal,
            List<WorkflowOperation> expectedOperations) {
        WorkflowValidationResult result = validator.validate(request);

        assertThat(result.valid()).as(name).isTrue();
        assertThat(result.violations()).isEmpty();
        assertThat(result.normalizedName()).isEqualTo("Test workflow");
        assertThat(result.normalizedDescription()).isEqualTo("Test description");
        assertThat(result.canonicalization().sourceModality())
                .isEqualTo(WorkflowModality.valueOf(
                        request.getGraph().getNodes().get(0).getOutputModality()));
        assertThat(result.canonicalization().terminalModality()).isEqualTo(expectedTerminal);
        assertThat(result.canonicalization().operationSequence()).containsExactlyElementsOf(expectedOperations);
        assertThat(result.canonicalization().graph().nodes())
                .extracting(node -> node.id())
                .containsExactlyElementsOf(expectedNodeIds(expectedOperations.size()));
        assertThat(result.canonicalization().graph().edges())
                .extracting(edge -> edge.from() + "->" + edge.to())
                .containsExactlyElementsOf(expectedEdges(expectedOperations.size()));
        assertThat(result.canonicalization().graph().nodes().stream()
                .filter(node -> node.kind() == WorkflowNodeKind.TRANSFORM)
                .map(node -> node.providerCode()))
                .containsExactlyElementsOf(expectedOperations.stream()
                        .map(WorkflowTestDefinitions::provider)
                        .toList());
        assertThat(result.canonicalization().canonicalJson()).doesNotContain(" ", "\n", "\r");
        assertThat(validator.validate(request).canonicalization().canonicalJson())
                .isEqualTo(result.canonicalization().canonicalJson());
    }

    @Test
    void equivalentArrayOrdersProduceByteIdenticalCanonicalJson() throws Exception {
        WorkflowDefinitionRequest ordered = WorkflowTestDefinitions.definition(
                WorkflowModality.TEXT_DESCRIPTION,
                WorkflowOperation.TEXT_TO_PAINTING,
                WorkflowOperation.PAINTING_TO_MUSIC);
        WorkflowDefinitionRequest shuffled = objectMapper.readValue(
                objectMapper.writeValueAsBytes(ordered), WorkflowDefinitionRequest.class);
        Collections.reverse(shuffled.getGraph().getNodes());
        Collections.reverse(shuffled.getGraph().getEdges());

        WorkflowValidationResult first = validator.validate(ordered);
        WorkflowValidationResult second = validator.validate(shuffled);

        assertThat(first.valid()).isTrue();
        assertThat(second.valid()).as(second.violations().toString()).isTrue();
        assertThat(second.canonicalization().canonicalJson())
                .isEqualTo(first.canonicalization().canonicalJson());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidDefinitions")
    void rejectsEveryRequiredInvalidDefinition(
            String name,
            WorkflowDefinitionRequest request,
            String expectedViolation) {
        WorkflowValidationResult first = validator.validate(request);
        WorkflowValidationResult second = validator.validate(request);

        assertThat(first.valid()).as(name).isFalse();
        assertThat(first.canonicalization()).isNull();
        assertThat(first.violations()).extracting(violation -> violation.code())
                .contains(expectedViolation);
        assertThat(second.violations()).isEqualTo(first.violations());
    }

    @Test
    void returnsAllIndependentDeterministicViolations() {
        WorkflowDefinitionRequest request = WorkflowTestDefinitions.definition(
                WorkflowModality.TEXT_DESCRIPTION, WorkflowOperation.TEXT_TO_PAINTING);
        request.setName("\u0000");
        WorkflowNodeRequest transform = request.getGraph().getNodes().get(1);
        transform.setProviderCode("wrong-provider");
        transform.setOutputModality(WorkflowModality.AUDIO.name());
        transform.getParameters().put("unexpected", TextNode.valueOf("value"));

        WorkflowValidationResult result = validator.validate(request);

        assertThat(result.violations()).extracting(violation -> violation.code())
                .contains(
                        "CONTROL_CHARACTER_NOT_ALLOWED",
                        "PROVIDER_NOT_ALLOWED",
                        "OUTPUT_MODALITY_MISMATCH",
                        "PARAMETERS_NOT_ALLOWED");
        assertThat(result.violations()).isSortedAccordingTo(java.util.Comparator
                .comparing(com.auralink.api.v1.error.ApiViolationDetail::path)
                .thenComparing(com.auralink.api.v1.error.ApiViolationDetail::code));
    }

    private static Stream<Arguments> validDefinitions() {
        return Stream.of(
                valid("TEXT_DESCRIPTION -> PAINTING", WorkflowModality.TEXT_DESCRIPTION,
                        WorkflowModality.PAINTING, WorkflowOperation.TEXT_TO_PAINTING),
                valid("POEM -> PAINTING", WorkflowModality.POEM,
                        WorkflowModality.PAINTING, WorkflowOperation.POEM_TO_PAINTING),
                valid("IMAGE -> PAINTING", WorkflowModality.IMAGE,
                        WorkflowModality.PAINTING, WorkflowOperation.IMAGE_TO_PAINTING),
                valid("PAINTING -> AUDIO", WorkflowModality.PAINTING,
                        WorkflowModality.AUDIO, WorkflowOperation.PAINTING_TO_MUSIC),
                valid("PAINTING -> POEM", WorkflowModality.PAINTING,
                        WorkflowModality.POEM, WorkflowOperation.PAINTING_TO_POEM),
                valid("TEXT_DESCRIPTION -> PAINTING -> AUDIO", WorkflowModality.TEXT_DESCRIPTION,
                        WorkflowModality.AUDIO,
                        WorkflowOperation.TEXT_TO_PAINTING, WorkflowOperation.PAINTING_TO_MUSIC),
                valid("TEXT_DESCRIPTION -> PAINTING -> POEM", WorkflowModality.TEXT_DESCRIPTION,
                        WorkflowModality.POEM,
                        WorkflowOperation.TEXT_TO_PAINTING, WorkflowOperation.PAINTING_TO_POEM),
                valid("POEM -> PAINTING -> AUDIO", WorkflowModality.POEM,
                        WorkflowModality.AUDIO,
                        WorkflowOperation.POEM_TO_PAINTING, WorkflowOperation.PAINTING_TO_MUSIC),
                valid("POEM -> PAINTING -> POEM", WorkflowModality.POEM,
                        WorkflowModality.POEM,
                        WorkflowOperation.POEM_TO_PAINTING, WorkflowOperation.PAINTING_TO_POEM),
                valid("IMAGE -> PAINTING -> AUDIO", WorkflowModality.IMAGE,
                        WorkflowModality.AUDIO,
                        WorkflowOperation.IMAGE_TO_PAINTING, WorkflowOperation.PAINTING_TO_MUSIC),
                valid("IMAGE -> PAINTING -> POEM", WorkflowModality.IMAGE,
                        WorkflowModality.POEM,
                        WorkflowOperation.IMAGE_TO_PAINTING, WorkflowOperation.PAINTING_TO_POEM),
                valid("PAINTING -> POEM -> PAINTING", WorkflowModality.PAINTING,
                        WorkflowModality.PAINTING,
                        WorkflowOperation.PAINTING_TO_POEM, WorkflowOperation.POEM_TO_PAINTING));
    }

    private static Arguments valid(
            String name,
            WorkflowModality source,
            WorkflowModality terminal,
            WorkflowOperation... operations) {
        return Arguments.of(
                name,
                WorkflowTestDefinitions.definition(source, operations),
                terminal,
                List.of(operations));
    }

    private static Stream<Arguments> invalidDefinitions() {
        List<Arguments> cases = new ArrayList<>();
        cases.add(invalid("source-only graph",
                WorkflowTestDefinitions.definition(WorkflowModality.TEXT_DESCRIPTION),
                "SOURCE_ONLY_WORKFLOW"));
        cases.add(mutated("zero source", request -> {
            request.getGraph().getNodes().get(0).setKind(WorkflowNodeKind.TRANSFORM.name());
        }, "SOURCE_COUNT_INVALID"));
        cases.add(mutated("two sources", request -> {
            WorkflowNodeRequest second = request.getGraph().getNodes().get(1);
            second.setKind(WorkflowNodeKind.SOURCE.name());
            second.setOperation(null);
            second.setProviderCode(null);
            second.setInputModality(null);
            second.setParameters(null);
        }, "SOURCE_COUNT_INVALID"));
        cases.add(mutated("zero terminal", request -> {
            WorkflowEdgeRequest back = new WorkflowEdgeRequest();
            back.setFrom("step1");
            back.setTo("source");
            request.getGraph().getEdges().add(back);
        }, "TERMINAL_COUNT_INVALID"));
        cases.add(invalid("two terminals", branchGraph(), "TERMINAL_COUNT_INVALID"));
        cases.add(invalid("disconnected node", disconnectedGraph(), "DISCONNECTED_NODE"));
        cases.add(mutated("cycle", request -> {
            WorkflowEdgeRequest back = new WorkflowEdgeRequest();
            back.setFrom("step1");
            back.setTo("source");
            request.getGraph().getEdges().add(back);
        }, "CYCLE_DETECTED"));
        cases.add(mutated("self-edge", request -> request.getGraph().getEdges().get(0).setTo("source"),
                "SELF_EDGE"));
        cases.add(mutated("duplicate edge", request -> request.getGraph().getEdges().add(
                edge("source", "step1")), "DUPLICATE_EDGE"));
        cases.add(mutated("duplicate node id", request ->
                request.getGraph().getNodes().get(1).setId("source"), "DUPLICATE_NODE_ID"));
        cases.add(mutated("missing node reference", request ->
                request.getGraph().getEdges().get(0).setTo("missing"), "UNKNOWN_NODE_REFERENCE"));
        cases.add(invalid("branching", branchGraph(), "BRANCHING_NOT_ALLOWED"));
        cases.add(invalid("merging", mergingGraph(), "MERGING_NOT_ALLOWED"));
        cases.add(mutated("wrong edge modality", request ->
                request.getGraph().getNodes().get(0).setOutputModality(WorkflowModality.IMAGE.name()),
                "EDGE_MODALITY_MISMATCH"));
        cases.add(mutated("wrong operation input", request ->
                request.getGraph().getNodes().get(1).setInputModality(WorkflowModality.IMAGE.name()),
                "INPUT_MODALITY_MISMATCH"));
        cases.add(mutated("wrong operation output", request ->
                request.getGraph().getNodes().get(1).setOutputModality(WorkflowModality.AUDIO.name()),
                "OUTPUT_MODALITY_MISMATCH"));
        cases.add(mutated("missing providerCode", request ->
                request.getGraph().getNodes().get(1).setProviderCode(null), "PROVIDER_REQUIRED"));
        cases.add(mutated("wrong providerCode", request ->
                request.getGraph().getNodes().get(1).setProviderCode("qwen3-vl-plus"),
                "PROVIDER_NOT_ALLOWED"));
        cases.add(mutated("unknown operation", request ->
                request.getGraph().getNodes().get(1).setOperation("TEXT_TO_AUDIO"),
                "OPERATION_UNKNOWN"));
        cases.add(invalid("PAINTING_TO_VIDEO disabled",
                WorkflowTestDefinitions.definition(
                        WorkflowModality.PAINTING, WorkflowOperation.PAINTING_TO_VIDEO),
                "OPERATION_DISABLED"));
        cases.add(mutated("non-empty parameters", request ->
                request.getGraph().getNodes().get(1).getParameters()
                        .put("temperature", TextNode.valueOf("not-allowed")),
                "PARAMETERS_NOT_ALLOWED"));
        cases.add(mutated("SOURCE containing operation/provider", request -> {
            WorkflowNodeRequest source = request.getGraph().getNodes().get(0);
            source.setOperation(WorkflowOperation.TEXT_TO_PAINTING.name());
            source.setProviderCode("seedream-5");
        }, "SOURCE_FIELD_NOT_ALLOWED"));
        cases.add(mutated("SOURCE containing explicit null transform field", request ->
                request.getGraph().getNodes().get(0).setOperation(null),
                "SOURCE_FIELD_NOT_ALLOWED"));
        cases.add(mutated("TRANSFORM missing operation", request ->
                request.getGraph().getNodes().get(1).setOperation(null), "OPERATION_REQUIRED"));
        cases.add(invalid("TEXT_DESCRIPTION -> AUDIO",
                forbiddenDirect(WorkflowModality.TEXT_DESCRIPTION, WorkflowOperation.PAINTING_TO_MUSIC),
                "EDGE_MODALITY_MISMATCH"));
        cases.add(invalid("POEM -> AUDIO",
                forbiddenDirect(WorkflowModality.POEM, WorkflowOperation.PAINTING_TO_MUSIC),
                "EDGE_MODALITY_MISMATCH"));
        cases.add(invalid("IMAGE -> AUDIO",
                forbiddenDirect(WorkflowModality.IMAGE, WorkflowOperation.PAINTING_TO_MUSIC),
                "EDGE_MODALITY_MISMATCH"));
        cases.add(invalid("TEXT_DESCRIPTION -> POEM",
                forbiddenDirect(WorkflowModality.TEXT_DESCRIPTION, WorkflowOperation.PAINTING_TO_POEM),
                "EDGE_MODALITY_MISMATCH"));
        cases.add(invalid("IMAGE -> POEM",
                forbiddenDirect(WorkflowModality.IMAGE, WorkflowOperation.PAINTING_TO_POEM),
                "EDGE_MODALITY_MISMATCH"));
        cases.add(invalid("PAINTING -> AUDIO -> anything",
                terminalSuccessor(WorkflowOperation.PAINTING_TO_MUSIC),
                "TERMINAL_OUTPUT_HAS_SUCCESSOR"));
        cases.add(invalid("AUDIO -> anything",
                WorkflowTestDefinitions.definition(
                        WorkflowModality.AUDIO, WorkflowOperation.PAINTING_TO_MUSIC),
                "SOURCE_MODALITY_INVALID"));
        cases.add(mutated("unknown fields", request ->
                request.getGraph().getNodes().get(0)
                        .putUnknownField("paintingId", TextNode.valueOf("forbidden")),
                "UNKNOWN_FIELD"));
        cases.add(mutated("unsupported schema version", request ->
                request.getGraph().setSchemaVersion(2), "WORKFLOW_SCHEMA_UNSUPPORTED"));
        cases.add(mutated("invalid node id", request ->
                request.getGraph().getNodes().get(1).setId("1 invalid"), "NODE_ID_INVALID"));

        WorkflowDefinitionRequest oversized = WorkflowTestDefinitions.definition(
                WorkflowModality.TEXT_DESCRIPTION, WorkflowOperation.TEXT_TO_PAINTING);
        oversized.getGraph().getNodes().get(0).putUnknownField(
                "padding", TextNode.valueOf("x".repeat(70_000)));
        cases.add(invalid("oversized graph", oversized, "WORKFLOW_GRAPH_TOO_LARGE"));
        return cases.stream();
    }

    private static Arguments mutated(
            String name,
            Consumer<WorkflowDefinitionRequest> mutation,
            String expected) {
        WorkflowDefinitionRequest request = WorkflowTestDefinitions.definition(
                WorkflowModality.TEXT_DESCRIPTION, WorkflowOperation.TEXT_TO_PAINTING);
        mutation.accept(request);
        return invalid(name, request, expected);
    }

    private static Arguments invalid(
            String name,
            WorkflowDefinitionRequest request,
            String expected) {
        return Arguments.of(name, request, expected);
    }

    private static WorkflowDefinitionRequest branchGraph() {
        WorkflowDefinitionRequest request = WorkflowTestDefinitions.definition(
                WorkflowModality.TEXT_DESCRIPTION, WorkflowOperation.TEXT_TO_PAINTING);
        WorkflowNodeRequest other = WorkflowTestDefinitions.transform(
                "other", WorkflowOperation.TEXT_TO_PAINTING);
        request.getGraph().getNodes().add(other);
        request.getGraph().getEdges().add(edge("source", "other"));
        return request;
    }

    private static WorkflowDefinitionRequest disconnectedGraph() {
        WorkflowDefinitionRequest request = WorkflowTestDefinitions.definition(
                WorkflowModality.TEXT_DESCRIPTION, WorkflowOperation.TEXT_TO_PAINTING);
        request.getGraph().getNodes().add(WorkflowTestDefinitions.transform(
                "detached", WorkflowOperation.PAINTING_TO_MUSIC));
        return request;
    }

    private static WorkflowDefinitionRequest mergingGraph() {
        WorkflowDefinitionRequest request = WorkflowTestDefinitions.definition(
                WorkflowModality.TEXT_DESCRIPTION,
                WorkflowOperation.TEXT_TO_PAINTING,
                WorkflowOperation.PAINTING_TO_MUSIC);
        request.getGraph().getEdges().add(edge("source", "step2"));
        return request;
    }

    private static WorkflowDefinitionRequest forbiddenDirect(
            WorkflowModality source,
            WorkflowOperation operation) {
        return WorkflowTestDefinitions.definition(source, operation);
    }

    private static WorkflowDefinitionRequest terminalSuccessor(WorkflowOperation terminalOperation) {
        WorkflowDefinitionRequest request = WorkflowTestDefinitions.definition(
                WorkflowModality.PAINTING, terminalOperation);
        WorkflowNodeRequest successor = WorkflowTestDefinitions.transform(
                "step2", WorkflowOperation.TEXT_TO_PAINTING);
        successor.setInputModality(request.getGraph().getNodes().get(1).getOutputModality());
        request.getGraph().getNodes().add(successor);
        request.getGraph().getEdges().add(edge("step1", "step2"));
        return request;
    }

    private static WorkflowEdgeRequest edge(String from, String to) {
        WorkflowEdgeRequest edge = new WorkflowEdgeRequest();
        edge.setFrom(from);
        edge.setTo(to);
        return edge;
    }

    private static List<String> expectedNodeIds(int operations) {
        List<String> ids = new ArrayList<>();
        ids.add("source");
        for (int index = 1; index <= operations; index++) {
            ids.add("step" + index);
        }
        return ids;
    }

    private static List<String> expectedEdges(int operations) {
        List<String> edges = new ArrayList<>();
        String previous = "source";
        for (int index = 1; index <= operations; index++) {
            String next = "step" + index;
            edges.add(previous + "->" + next);
            previous = next;
        }
        return edges;
    }
}
