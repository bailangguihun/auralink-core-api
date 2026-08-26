package com.auralink.workflow.graph;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Bounded parameter object for a transform node.
 *
 * <p>Schema version 1 defines no keys, so the validator accepts only an empty
 * instance. Keeping a dedicated type prevents the whole graph from becoming an
 * untyped map.</p>
 */
public final class WorkflowParameters {

    private final Map<String, JsonNode> values = new TreeMap<>();

    @JsonAnySetter
    public void put(String name, JsonNode value) {
        values.put(name, value);
    }

    @JsonAnyGetter
    public Map<String, JsonNode> values() {
        return Collections.unmodifiableMap(values);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return values.isEmpty();
    }
}
