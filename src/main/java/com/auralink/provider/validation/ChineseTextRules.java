package com.auralink.provider.validation;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Shared strict validation for provider-authored Chinese JSON fields. */
public final class ChineseTextRules {

    private static final Pattern HTML = Pattern.compile("<[^>]+>");
    private static final Pattern UNSAFE_URL = Pattern.compile(
            "(?i)(?:https?|file|ftp)://|www\\.");
    private static final Pattern UNSUPPORTED_AUTHORSHIP = Pattern.compile(
            "(?:真迹|原作|作者为|由.{0,24}(?:创作|所作)|出自.{0,24}(?:之手|笔下))");
    private static final Set<String> LEAKAGE_MARKERS = Set.of(
            "system prompt",
            "developer message",
            "chain of thought",
            "系统提示词",
            "开发者指令",
            "推理过程",
            "思考过程",
            "调用工具",
            "使用工具",
            "tool_call",
            "function_call",
            "作为ai",
            "作为人工智能",
            "语言模型");

    private ChineseTextRules() {
    }

    public static boolean containsChinese(String value) {
        if (value == null) {
            return false;
        }
        return value.codePoints().anyMatch(codePoint ->
                (codePoint >= 0x3400 && codePoint <= 0x4dbf)
                        || (codePoint >= 0x4e00 && codePoint <= 0x9fff)
                        || (codePoint >= 0xf900 && codePoint <= 0xfaff));
    }

    public static boolean containsForbiddenMarkupOrLeakage(String value) {
        if (value == null) {
            return true;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return value.contains("```")
                || HTML.matcher(value).find()
                || UNSAFE_URL.matcher(value).find()
                || LEAKAGE_MARKERS.stream().anyMatch(lower::contains);
    }

    public static boolean containsUnsupportedAuthorshipClaim(String value) {
        return value != null && UNSUPPORTED_AUTHORSHIP.matcher(value).find();
    }
}
