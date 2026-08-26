package com.auralink.api.v1.creation;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;

import lombok.Getter;
import lombok.Setter;

/** Strict optimistic-concurrency input for an owner-authorized Creation retry. */
@Getter
@Setter
public final class CreationRetryRequest {

    private Integer expectedRetryVersion;

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
