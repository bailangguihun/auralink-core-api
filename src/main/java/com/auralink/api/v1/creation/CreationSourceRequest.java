package com.auralink.api.v1.creation;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

import lombok.Getter;

/** Strict modality-specific source envelope for a Creation submission. */
@Getter
@JsonPropertyOrder({"modality", "text", "assetId", "paintingId"})
public final class CreationSourceRequest {

    private String modality;
    private String text;
    private String assetId;
    private String paintingId;

    @JsonIgnore
    private boolean textPresent;
    @JsonIgnore
    private boolean assetIdPresent;
    @JsonIgnore
    private boolean paintingIdPresent;
    @JsonIgnore
    private final Map<String, JsonNode> unknownFields = new TreeMap<>();

    public void setModality(String modality) {
        this.modality = modality;
    }

    public void setText(String text) {
        this.text = text;
        textPresent = true;
    }

    public void setAssetId(String assetId) {
        this.assetId = assetId;
        assetIdPresent = true;
    }

    public void setPaintingId(String paintingId) {
        this.paintingId = paintingId;
        paintingIdPresent = true;
    }

    public boolean hasTextField() {
        return textPresent;
    }

    public boolean hasAssetIdField() {
        return assetIdPresent;
    }

    public boolean hasPaintingIdField() {
        return paintingIdPresent;
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
