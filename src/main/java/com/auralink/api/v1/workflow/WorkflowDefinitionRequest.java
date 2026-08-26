package com.auralink.api.v1.workflow;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import com.auralink.workflow.graph.WorkflowGraphRequest;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import lombok.Getter;
import lombok.Setter;

/** Create, replacement-update, and validation request for a private workflow. */
@Getter
@Setter
@JsonPropertyOrder({"name", "description", "graph"})
public final class WorkflowDefinitionRequest {

    private String name;
    private String description;
    private WorkflowGraphRequest graph;
    @JsonIgnore
    private final Map<String, JsonNode> unknownFields = new TreeMap<>();

    @JsonAnySetter
    public void putUnknownField(String name, JsonNode value) {
        unknownFields.put(name, value);
    }

    @JsonAnyGetter
    public Map<String, JsonNode> unknownFields() {
        return Collections.unmodifiableMap(unknownFields);
    }
}
