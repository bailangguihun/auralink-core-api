package com.auralink.provider.qwen;

/**
 * Immutable, bounded response-shape facts. Every field is structural; no
 * generated text, raw JSON, field names, hashes, paths, or provider metadata
 * can be stored in this type.
 */
public record QwenResponseShapeDiagnostic(
        Boolean providerEnvelopePresent,
        Boolean choicesPresent,
        Integer choiceCount,
        Boolean messagePresent,
        Boolean reasoningContentPresent,
        QwenSafeValueType reasoningContentType,
        Boolean reasoningContentNonblank,
        Boolean contentPresent,
        QwenSafeValueType contentType,
        Integer contentLength,
        Boolean jsonParsed,
        QwenSafeValueType topLevelType,
        Boolean schemaVersionPresent,
        QwenSafeValueType schemaVersionType,
        Boolean titlePresent,
        QwenSafeValueType titleType,
        Integer titleLength,
        Boolean linesPresent,
        QwenSafeValueType linesType,
        Integer lineCount,
        Integer stringLineCount,
        Integer nonblankLineCount,
        Integer chineseDominantLineCount,
        Integer duplicateLineCount,
        Integer minimumLineLength,
        Integer maximumLineLength,
        Boolean textPresent,
        QwenSafeValueType textType,
        Integer textLength,
        Boolean textMatchesLines,
        Integer unknownFieldCount,
        Integer duplicateFieldCount,
        Boolean hasLeadingOrTrailingContent,
        Boolean hasMarkdownFence,
        Boolean hasHtml,
        Boolean hasReasoningMarker,
        Boolean hasAiSelfReference) {

    public static final int MAX_SAFE_COUNT_OR_LENGTH = 1_048_576;

    public QwenResponseShapeDiagnostic {
        requireBounded("choiceCount", choiceCount);
        requireBounded("contentLength", contentLength);
        requireBounded("titleLength", titleLength);
        requireBounded("lineCount", lineCount);
        requireBounded("stringLineCount", stringLineCount);
        requireBounded("nonblankLineCount", nonblankLineCount);
        requireBounded("chineseDominantLineCount", chineseDominantLineCount);
        requireBounded("duplicateLineCount", duplicateLineCount);
        requireBounded("minimumLineLength", minimumLineLength);
        requireBounded("maximumLineLength", maximumLineLength);
        requireBounded("textLength", textLength);
        requireBounded("unknownFieldCount", unknownFieldCount);
        requireBounded("duplicateFieldCount", duplicateFieldCount);
        if (minimumLineLength != null && maximumLineLength != null
                && minimumLineLength > maximumLineLength) {
            throw new IllegalArgumentException("Minimum line length exceeds maximum line length");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    private static void requireBounded(String field, Integer value) {
        if (value != null && (value < 0 || value > MAX_SAFE_COUNT_OR_LENGTH)) {
            throw new IllegalArgumentException(field + " is outside the safe diagnostic bound");
        }
    }

    private static int bounded(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("Diagnostic counts and lengths must be nonnegative");
        }
        return (int) Math.min(value, MAX_SAFE_COUNT_OR_LENGTH);
    }

    /** Mutable construction helper; only the built diagnostic is propagated. */
    public static final class Builder {

        private Boolean providerEnvelopePresent;
        private Boolean choicesPresent;
        private Integer choiceCount;
        private Boolean messagePresent;
        private Boolean reasoningContentPresent;
        private QwenSafeValueType reasoningContentType;
        private Boolean reasoningContentNonblank;
        private Boolean contentPresent;
        private QwenSafeValueType contentType;
        private Integer contentLength;
        private Boolean jsonParsed;
        private QwenSafeValueType topLevelType;
        private Boolean schemaVersionPresent;
        private QwenSafeValueType schemaVersionType;
        private Boolean titlePresent;
        private QwenSafeValueType titleType;
        private Integer titleLength;
        private Boolean linesPresent;
        private QwenSafeValueType linesType;
        private Integer lineCount;
        private Integer stringLineCount;
        private Integer nonblankLineCount;
        private Integer chineseDominantLineCount;
        private Integer duplicateLineCount;
        private Integer minimumLineLength;
        private Integer maximumLineLength;
        private Boolean textPresent;
        private QwenSafeValueType textType;
        private Integer textLength;
        private Boolean textMatchesLines;
        private Integer unknownFieldCount;
        private Integer duplicateFieldCount;
        private Boolean hasLeadingOrTrailingContent;
        private Boolean hasMarkdownFence;
        private Boolean hasHtml;
        private Boolean hasReasoningMarker;
        private Boolean hasAiSelfReference;

        private Builder() {
        }

        private Builder(QwenResponseShapeDiagnostic source) {
            providerEnvelopePresent = source.providerEnvelopePresent();
            choicesPresent = source.choicesPresent();
            choiceCount = source.choiceCount();
            messagePresent = source.messagePresent();
            reasoningContentPresent = source.reasoningContentPresent();
            reasoningContentType = source.reasoningContentType();
            reasoningContentNonblank = source.reasoningContentNonblank();
            contentPresent = source.contentPresent();
            contentType = source.contentType();
            contentLength = source.contentLength();
            jsonParsed = source.jsonParsed();
            topLevelType = source.topLevelType();
            schemaVersionPresent = source.schemaVersionPresent();
            schemaVersionType = source.schemaVersionType();
            titlePresent = source.titlePresent();
            titleType = source.titleType();
            titleLength = source.titleLength();
            linesPresent = source.linesPresent();
            linesType = source.linesType();
            lineCount = source.lineCount();
            stringLineCount = source.stringLineCount();
            nonblankLineCount = source.nonblankLineCount();
            chineseDominantLineCount = source.chineseDominantLineCount();
            duplicateLineCount = source.duplicateLineCount();
            minimumLineLength = source.minimumLineLength();
            maximumLineLength = source.maximumLineLength();
            textPresent = source.textPresent();
            textType = source.textType();
            textLength = source.textLength();
            textMatchesLines = source.textMatchesLines();
            unknownFieldCount = source.unknownFieldCount();
            duplicateFieldCount = source.duplicateFieldCount();
            hasLeadingOrTrailingContent = source.hasLeadingOrTrailingContent();
            hasMarkdownFence = source.hasMarkdownFence();
            hasHtml = source.hasHtml();
            hasReasoningMarker = source.hasReasoningMarker();
            hasAiSelfReference = source.hasAiSelfReference();
        }

        public Builder providerEnvelopePresent(boolean value) {
            providerEnvelopePresent = value;
            return this;
        }

        public Builder choicesPresent(boolean value) {
            choicesPresent = value;
            return this;
        }

        public Builder choiceCount(long value) {
            choiceCount = bounded(value);
            return this;
        }

        public Builder messagePresent(boolean value) {
            messagePresent = value;
            return this;
        }

        public Builder reasoningContentPresent(boolean value) {
            reasoningContentPresent = value;
            return this;
        }

        public Builder reasoningContentType(QwenSafeValueType value) {
            reasoningContentType = value;
            return this;
        }

        public Builder reasoningContentNonblank(boolean value) {
            reasoningContentNonblank = value;
            return this;
        }

        public Builder contentPresent(boolean value) {
            contentPresent = value;
            return this;
        }

        public Builder contentType(QwenSafeValueType value) {
            contentType = value;
            return this;
        }

        public Builder contentLength(long value) {
            contentLength = bounded(value);
            return this;
        }

        public Builder jsonParsed(boolean value) {
            jsonParsed = value;
            return this;
        }

        public Builder topLevelType(QwenSafeValueType value) {
            topLevelType = value;
            return this;
        }

        public Builder schemaVersionPresent(boolean value) {
            schemaVersionPresent = value;
            return this;
        }

        public Builder schemaVersionType(QwenSafeValueType value) {
            schemaVersionType = value;
            return this;
        }

        public Builder titlePresent(boolean value) {
            titlePresent = value;
            return this;
        }

        public Builder titleType(QwenSafeValueType value) {
            titleType = value;
            return this;
        }

        public Builder titleLength(long value) {
            titleLength = bounded(value);
            return this;
        }

        public Builder linesPresent(boolean value) {
            linesPresent = value;
            return this;
        }

        public Builder linesType(QwenSafeValueType value) {
            linesType = value;
            return this;
        }

        public Builder lineCount(long value) {
            lineCount = bounded(value);
            return this;
        }

        public Builder stringLineCount(long value) {
            stringLineCount = bounded(value);
            return this;
        }

        public Builder nonblankLineCount(long value) {
            nonblankLineCount = bounded(value);
            return this;
        }

        public Builder chineseDominantLineCount(long value) {
            chineseDominantLineCount = bounded(value);
            return this;
        }

        public Builder duplicateLineCount(long value) {
            duplicateLineCount = bounded(value);
            return this;
        }

        public Builder minimumLineLength(long value) {
            minimumLineLength = bounded(value);
            return this;
        }

        public Builder maximumLineLength(long value) {
            maximumLineLength = bounded(value);
            return this;
        }

        public Builder textPresent(boolean value) {
            textPresent = value;
            return this;
        }

        public Builder textType(QwenSafeValueType value) {
            textType = value;
            return this;
        }

        public Builder textLength(long value) {
            textLength = bounded(value);
            return this;
        }

        public Builder textMatchesLines(boolean value) {
            textMatchesLines = value;
            return this;
        }

        public Builder unknownFieldCount(long value) {
            unknownFieldCount = bounded(value);
            return this;
        }

        public Builder duplicateFieldCount(long value) {
            duplicateFieldCount = bounded(value);
            return this;
        }

        public Builder hasLeadingOrTrailingContent(boolean value) {
            hasLeadingOrTrailingContent = value;
            return this;
        }

        public Builder hasMarkdownFence(boolean value) {
            hasMarkdownFence = value;
            return this;
        }

        public Builder hasHtml(boolean value) {
            hasHtml = value;
            return this;
        }

        public Builder hasReasoningMarker(boolean value) {
            hasReasoningMarker = value;
            return this;
        }

        public Builder hasAiSelfReference(boolean value) {
            hasAiSelfReference = value;
            return this;
        }

        public QwenResponseShapeDiagnostic build() {
            return new QwenResponseShapeDiagnostic(
                    providerEnvelopePresent,
                    choicesPresent,
                    choiceCount,
                    messagePresent,
                    reasoningContentPresent,
                    reasoningContentType,
                    reasoningContentNonblank,
                    contentPresent,
                    contentType,
                    contentLength,
                    jsonParsed,
                    topLevelType,
                    schemaVersionPresent,
                    schemaVersionType,
                    titlePresent,
                    titleType,
                    titleLength,
                    linesPresent,
                    linesType,
                    lineCount,
                    stringLineCount,
                    nonblankLineCount,
                    chineseDominantLineCount,
                    duplicateLineCount,
                    minimumLineLength,
                    maximumLineLength,
                    textPresent,
                    textType,
                    textLength,
                    textMatchesLines,
                    unknownFieldCount,
                    duplicateFieldCount,
                    hasLeadingOrTrailingContent,
                    hasMarkdownFence,
                    hasHtml,
                    hasReasoningMarker,
                    hasAiSelfReference);
        }
    }
}
