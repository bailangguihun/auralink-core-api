package com.auralink.provider.qwen;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Structural-only inspection helpers; none returns or retains response text. */
final class QwenResponseInspection {

    private static final Pattern HTML = Pattern.compile("<[^>]+>");
    private static final Set<String> REASONING_MARKERS = Set.of(
            "chain of thought",
            "reasoning_content",
            "reasoning process",
            "推理过程",
            "思考过程",
            "分析过程",
            "思考：",
            "推理：");
    private static final Set<String> AI_SELF_REFERENCES = Set.of(
            "作为ai",
            "作为 ai",
            "作为人工智能",
            "我是ai",
            "我是 ai",
            "我是人工智能",
            "语言模型",
            "as an ai",
            "i am an ai",
            "i'm an ai",
            "i’m an ai");

    private QwenResponseInspection() {
    }

    static boolean hasMarkdownFence(String value) {
        return value != null && value.contains("```");
    }

    static boolean hasHtml(String value) {
        return value != null && HTML.matcher(value).find();
    }

    static boolean hasReasoningMarker(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return REASONING_MARKERS.stream().anyMatch(lower::contains);
    }

    static boolean hasAiSelfReference(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return AI_SELF_REFERENCES.stream().anyMatch(lower::contains);
    }

    static boolean isDuplicateFieldFailure(Throwable failure) {
        return failureMessageContains(failure, "duplicate field");
    }

    static boolean isTrailingTokenFailure(Throwable failure) {
        return failureMessageContains(failure, "trailing token");
    }

    private static boolean failureMessageContains(Throwable failure, String marker) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains(marker)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Detects a non-whitespace prefix before a candidate object or content
     * after its matching top-level brace. It never extracts or repairs JSON.
     */
    static boolean hasLeadingOrTrailingContent(String value) {
        if (value == null) {
            return false;
        }
        int first = firstNonWhitespace(value, 0);
        if (first < 0) {
            return false;
        }
        if (value.charAt(first) != '{') {
            return value.indexOf('{', first + 1) >= 0;
        }

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = first; index < value.length(); index++) {
            char character = value.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    inString = false;
                }
                continue;
            }
            if (character == '"') {
                inString = true;
            } else if (character == '{' || character == '[') {
                depth++;
            } else if (character == '}' || character == ']') {
                depth--;
                if (depth == 0) {
                    return firstNonWhitespace(value, index + 1) >= 0;
                }
                if (depth < 0) {
                    return false;
                }
            }
        }
        return false;
    }

    static boolean isChineseDominant(String value) {
        if (value == null) {
            return false;
        }
        long chinese = value.codePoints().filter(QwenResponseInspection::isChinese).count();
        long lexical = value.codePoints().filter(Character::isLetterOrDigit).count();
        return chinese > 0 && chinese * 2 >= lexical;
    }

    private static boolean isChinese(int codePoint) {
        return (codePoint >= 0x3400 && codePoint <= 0x4dbf)
                || (codePoint >= 0x4e00 && codePoint <= 0x9fff)
                || (codePoint >= 0xf900 && codePoint <= 0xfaff);
    }

    private static int firstNonWhitespace(String value, int start) {
        for (int index = start; index < value.length(); index++) {
            if (!Character.isWhitespace(value.charAt(index))) {
                return index;
            }
        }
        return -1;
    }
}
