package com.auralink.workflow.graph;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/** Narrow deterministic JSON codec; the application-wide mapper is untouched. */
@Component
public class WorkflowGraphCodec {

    private final ObjectMapper canonicalMapper;

    public WorkflowGraphCodec(ObjectMapper applicationMapper) {
        canonicalMapper = applicationMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, false)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public String encode(CanonicalWorkflowGraph graph) {
        try {
            return canonicalMapper.writeValueAsString(graph);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize canonical workflow graph", exception);
        }
    }

    public CanonicalWorkflowGraph decode(String canonicalJson) {
        try {
            return canonicalMapper.readValue(canonicalJson, CanonicalWorkflowGraph.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Persisted workflow graph is invalid", exception);
        }
    }

    public int requestSizeBytes(WorkflowGraphRequest graph) {
        try {
            return canonicalMapper.writeValueAsString(graph).getBytes(StandardCharsets.UTF_8).length;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Workflow graph cannot be serialized", exception);
        }
    }

    public ObjectMapper canonicalMapperCopy() {
        return canonicalMapper.copy();
    }
}
