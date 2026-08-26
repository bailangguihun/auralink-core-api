package com.auralink.workflow.graph;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import lombok.Getter;
import lombok.Setter;

/** Typed directed edge request for the single-chain graph. */
@Getter
@Setter
@JsonPropertyOrder({"from", "to"})
public final class WorkflowEdgeRequest {

    private String from;
    private String to;
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
