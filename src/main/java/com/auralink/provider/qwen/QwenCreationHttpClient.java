package com.auralink.provider.qwen;

import java.net.URI;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.config.properties.ProviderProperties;
import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;
import com.auralink.provider.ProviderBulkheadKind;
import com.auralink.provider.ProviderBulkheads;
import com.auralink.provider.http.ProviderHttpExecutor;
import com.auralink.provider.http.ProviderHttpResponse;
import com.auralink.provider.validation.StrictProviderJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Creation-specific Qwen OpenAI-compatible transport, separate from Guide Service. */
@Component
public class QwenCreationHttpClient {

    private static final long MAX_RESPONSE_BYTES = 1024L * 1024L;
    private static final long MAX_STRUCTURED_JSON_OVERHEAD_CHARS = 16L * 1024L;

    private final RestClient restClient;
    private final ProviderHttpExecutor httpExecutor;
    private final ObjectMapper objectMapper;
    private final CreationProviderProperties creationProperties;
    private final ProviderProperties.Provider provider;
    private final QwenEndpointResolver endpointResolver;
    private final ProviderBulkheads bulkheads;

    public QwenCreationHttpClient(
            @Qualifier("qwenProviderRestClient") RestClient restClient,
            ProviderHttpExecutor httpExecutor,
            ObjectMapper objectMapper,
            CreationProviderProperties creationProperties,
            ProviderProperties providerProperties,
            QwenEndpointResolver endpointResolver,
            ProviderBulkheads bulkheads) {
        this.restClient = restClient;
        this.httpExecutor = httpExecutor;
        this.objectMapper = objectMapper;
        this.creationProperties = creationProperties;
        this.provider = providerProperties.getQwen();
        this.endpointResolver = endpointResolver;
        this.bulkheads = bulkheads;
    }

    public String completeTextJson(
            String requestId,
            String systemInstruction,
            String sourceText) {
        ObjectNode request = baseRequest();
        ArrayNode messages = request.putArray("messages");
        messages.add(message("system", systemInstruction));
        messages.add(message("user", sourceText));
        return execute(requestId, request).content();
    }

    public String completeImageJson(
            String requestId,
            String systemInstruction,
            String imageDataUrl,
            String userInstruction) {
        return completeImageJsonWithShape(
                requestId, systemInstruction, imageDataUrl, userInstruction).content();
    }

    QwenResponseContent completeImageJsonWithShape(
            String requestId,
            String systemInstruction,
            String imageDataUrl,
            String userInstruction) {
        ObjectNode request = baseRequest();
        ArrayNode messages = request.putArray("messages");
        messages.add(message("system", systemInstruction));
        ObjectNode user = objectMapper.createObjectNode();
        user.put("role", "user");
        ArrayNode content = user.putArray("content");
        ObjectNode image = content.addObject();
        image.put("type", "image_url");
        image.putObject("image_url").put("url", imageDataUrl);
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", userInstruction);
        messages.add(user);
        return execute(requestId, request);
    }

    private ObjectNode baseRequest() {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", provider.getModel().trim());
        request.put("enable_thinking", false);
        request.putObject("response_format").put("type", "json_object");
        request.put("stream", false);
        return request;
    }

    private ObjectNode message(String role, String content) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private QwenResponseContent execute(String requestId, ObjectNode request) {
        URI endpoint = endpointResolver.resolveChatCompletionsEndpoint();
        long maxRequestBytes = Math.addExact(
                Math.multiplyExact(creationProperties.getMaxImageInputBytes(), 2L),
                Math.multiplyExact((long) creationProperties.getMaxTextChars(), 8L));
        return bulkheads.execute(ProviderBulkheadKind.QWEN, () -> {
            final ProviderHttpResponse response;
            try {
                response = httpExecutor.postJson(
                        restClient,
                        endpoint,
                        provider.getApiKey(),
                        requestId,
                        request,
                        maxRequestBytes,
                        MAX_RESPONSE_BYTES);
            } catch (ProviderExecutionException exception) {
                if (exception.category() == ProviderErrorCategory.PROVIDER_INVALID_RESPONSE
                        && exception.safeDiagnostic() == null) {
                    throw invalid(
                            QwenResponseValidationStage.HTTP_ENVELOPE,
                            QwenResponseValidationCode.QWEN_CONTENT_TOO_LARGE,
                            QwenResponseShapeDiagnostic.builder());
                }
                throw exception;
            }
            return parseContent(response.body());
        });
    }

    private QwenResponseContent parseContent(byte[] responseBody) {
        QwenResponseShapeDiagnostic.Builder shape = QwenResponseShapeDiagnostic.builder()
                .providerEnvelopePresent(responseBody != null && responseBody.length > 0);
        if (responseBody == null || responseBody.length == 0) {
            throw invalid(
                    QwenResponseValidationStage.HTTP_ENVELOPE,
                    QwenResponseValidationCode.QWEN_HTTP_ENVELOPE_MISSING,
                    shape);
        }
        final JsonNode root;
        try {
            root = StrictProviderJson.parse(objectMapper, responseBody);
        } catch (Exception exception) {
            if (QwenResponseInspection.isDuplicateFieldFailure(exception)) {
                shape.duplicateFieldCount(1);
                throw invalid(
                        QwenResponseValidationStage.HTTP_ENVELOPE,
                        QwenResponseValidationCode.QWEN_JSON_DUPLICATE_FIELD,
                        shape);
            }
            if (QwenResponseInspection.isTrailingTokenFailure(exception)) {
                shape.hasLeadingOrTrailingContent(true);
                throw invalid(
                        QwenResponseValidationStage.HTTP_ENVELOPE,
                        QwenResponseValidationCode.QWEN_JSON_TRAILING_CONTENT,
                        shape);
            }
            throw invalid(
                    QwenResponseValidationStage.HTTP_ENVELOPE,
                    QwenResponseValidationCode.QWEN_JSON_PARSE_FAILED,
                    shape);
        }
        if (root == null || !root.isObject()) {
            throw invalid(
                    QwenResponseValidationStage.HTTP_ENVELOPE,
                    QwenResponseValidationCode.QWEN_JSON_ROOT_NOT_OBJECT,
                    shape);
        }

        boolean choicesPresent = root.has("choices");
        shape.choicesPresent(choicesPresent);
        if (!choicesPresent) {
            throw invalid(
                    QwenResponseValidationStage.CHOICES,
                    QwenResponseValidationCode.QWEN_CHOICES_MISSING,
                    shape);
        }
        JsonNode choices = root.get("choices");
        if (!choices.isArray()) {
            throw invalid(
                    QwenResponseValidationStage.CHOICES,
                    QwenResponseValidationCode.QWEN_CHOICES_TYPE_INVALID,
                    shape);
        }
        shape.choiceCount(choices.size());
        if (choices.size() != 1) {
            throw invalid(
                    QwenResponseValidationStage.CHOICES,
                    QwenResponseValidationCode.QWEN_CHOICE_COUNT_INVALID,
                    shape);
        }

        JsonNode choice = choices.get(0);
        boolean messagePresent = choice != null && choice.isObject() && choice.has("message");
        shape.messagePresent(messagePresent);
        if (!messagePresent) {
            throw invalid(
                    QwenResponseValidationStage.MESSAGE,
                    QwenResponseValidationCode.QWEN_MESSAGE_MISSING,
                    shape);
        }
        JsonNode message = choice.get("message");
        if (!message.isObject()) {
            throw invalid(
                    QwenResponseValidationStage.MESSAGE,
                    QwenResponseValidationCode.QWEN_MESSAGE_TYPE_INVALID,
                    shape);
        }
        boolean reasoningContentPresent = message.has("reasoning_content");
        shape.reasoningContentPresent(reasoningContentPresent);
        if (reasoningContentPresent) {
            JsonNode reasoningContent = message.get("reasoning_content");
            shape.reasoningContentType(QwenSafeValueType.from(reasoningContent));
            if (reasoningContent.isNull()) {
                shape.reasoningContentNonblank(false);
            } else if (!reasoningContent.isTextual()) {
                throw invalid(
                        QwenResponseValidationStage.MESSAGE,
                        QwenResponseValidationCode.QWEN_REASONING_CONTENT_TYPE_INVALID,
                        shape);
            } else {
                boolean reasoningContentNonblank = !reasoningContent.textValue().isBlank();
                shape.reasoningContentNonblank(reasoningContentNonblank);
                if (reasoningContentNonblank) {
                    shape.hasReasoningMarker(true);
                    throw invalid(
                            QwenResponseValidationStage.MESSAGE,
                            QwenResponseValidationCode.QWEN_CONTENT_REASONING_MARKER,
                            shape);
                }
            }
        }

        boolean contentPresent = message.has("content");
        shape.contentPresent(contentPresent);
        if (!contentPresent) {
            throw invalid(
                    QwenResponseValidationStage.CONTENT,
                    QwenResponseValidationCode.QWEN_CONTENT_MISSING,
                    shape);
        }
        JsonNode content = message.get("content");
        shape.contentType(QwenSafeValueType.from(content));
        if (!content.isTextual()) {
            throw invalid(
                    QwenResponseValidationStage.CONTENT,
                    QwenResponseValidationCode.QWEN_CONTENT_TYPE_INVALID,
                    shape);
        }
        String value = content.textValue();
        shape.contentLength(value.length());
        if (value.isBlank()) {
            throw invalid(
                    QwenResponseValidationStage.CONTENT,
                    QwenResponseValidationCode.QWEN_CONTENT_BLANK,
                    shape);
        }
        long contentLimit = Math.min(
                QwenResponseShapeDiagnostic.MAX_SAFE_COUNT_OR_LENGTH,
                (long) creationProperties.getMaxTextChars() + MAX_STRUCTURED_JSON_OVERHEAD_CHARS);
        if (value.length() > contentLimit) {
            throw invalid(
                    QwenResponseValidationStage.CONTENT,
                    QwenResponseValidationCode.QWEN_CONTENT_TOO_LARGE,
                    shape);
        }
        return new QwenResponseContent(value.trim(), shape.build());
    }

    private ProviderExecutionException invalid(
            QwenResponseValidationStage stage,
            QwenResponseValidationCode code,
            QwenResponseShapeDiagnostic.Builder shape) {
        return ProviderExecutionException.fromSafeDiagnostic(
                ProviderErrorCategory.PROVIDER_INVALID_RESPONSE,
                "Qwen response failed strict validation",
                new QwenResponseValidationDiagnostic(stage, code, shape.build()));
    }
}
