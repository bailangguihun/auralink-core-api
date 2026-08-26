package com.auralink.workflow.graph;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/** Typed request node; kind-specific field legality is enforced centrally. */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "id", "kind", "operation", "providerCode",
        "inputModality", "outputModality", "parameters"
})
public final class WorkflowNodeRequest {

    private String id;
    private String kind;
    private String operation;
    private String providerCode;
    private String inputModality;
    private String outputModality;
    private WorkflowParameters parameters;
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @JsonIgnore
    private boolean operationPresent;
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @JsonIgnore
    private boolean providerCodePresent;
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @JsonIgnore
    private boolean inputModalityPresent;
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @JsonIgnore
    private boolean parametersPresent;
    @JsonIgnore
    private final Map<String, JsonNode> unknownFields = new TreeMap<>();

    public void setOperation(String operation) {
        this.operation = operation;
        operationPresent = true;
    }

    public void setProviderCode(String providerCode) {
        this.providerCode = providerCode;
        providerCodePresent = true;
    }

    public void setInputModality(String inputModality) {
        this.inputModality = inputModality;
        inputModalityPresent = true;
    }

    public void setParameters(WorkflowParameters parameters) {
        this.parameters = parameters;
        parametersPresent = true;
    }

    public boolean hasOperationField() {
        return operationPresent;
    }

    public boolean hasProviderCodeField() {
        return providerCodePresent;
    }

    public boolean hasInputModalityField() {
        return inputModalityPresent;
    }

    public boolean hasParametersField() {
        return parametersPresent;
    }

    @JsonAnySetter
    public void putUnknownField(String name, JsonNode value) {
        unknownFields.put(name, value);
    }

    @JsonAnyGetter
    public Map<String, JsonNode> unknownFields() {
        return Collections.unmodifiableMap(unknownFields);
    }
}
