package com.auralink.provider.qwen;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;
import com.auralink.provider.validation.ChineseTextRules;
import com.auralink.provider.validation.StrictProviderJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class PaintingPoemResultValidator {
   private static final Set<String> FIELDS = Set.of("schemaVersion", "title", "lines", "text");
   private static final Pattern POEM_CHARACTERS = Pattern.compile("[\\p{IsHan}\\p{P}\\p{Zs}]+");
   private static final int MAX_LINE_CHARS = 100;
   private static final long MAX_STRUCTURED_JSON_OVERHEAD_CHARS = 16384L;
   private final ObjectMapper objectMapper;
   private final CreationProviderProperties properties;

   /** Keep the read contract for poems saved before strict language validation. */
   public PaintingPoemResult validate(String rawJson) {
      return this.validate(rawJson, false);
   }

   public PaintingPoemResult validateGenerated(String rawJson) {
      return this.validate(rawJson, true);
   }

   private PaintingPoemResult validate(String rawJson, boolean generated) {
      QwenResponseShapeDiagnostic.Builder shape = QwenResponseShapeDiagnostic.builder().contentPresent(rawJson != null);
      if (rawJson != null) {
         shape.contentType(QwenSafeValueType.STRING).contentLength(rawJson.length());
      }

      return this.validate(rawJson, shape.build(), generated);
   }

   PaintingPoemResult validate(QwenResponseContent response) {
      return response == null ? this.validateGenerated(null) : this.validate(response.content(), response.responseShape(), true);
   }

   private PaintingPoemResult validate(String rawJson, QwenResponseShapeDiagnostic baseShape, boolean generated) {
      QwenResponseShapeDiagnostic.Builder shape = baseShape.toBuilder();
      if (rawJson == null) {
         throw this.invalid(QwenResponseValidationStage.CONTENT, QwenResponseValidationCode.QWEN_CONTENT_MISSING, shape);
      }

      if (rawJson.isBlank()) {
         throw this.invalid(QwenResponseValidationStage.CONTENT, QwenResponseValidationCode.QWEN_CONTENT_BLANK, shape);
      }

      boolean markdown = QwenResponseInspection.hasMarkdownFence(rawJson);
      boolean html = QwenResponseInspection.hasHtml(rawJson);
      boolean reasoning = QwenResponseInspection.hasReasoningMarker(rawJson);
      boolean aiSelfReference = QwenResponseInspection.hasAiSelfReference(rawJson);
      boolean outsideJson = QwenResponseInspection.hasLeadingOrTrailingContent(rawJson);
      shape.hasMarkdownFence(markdown).hasHtml(html).hasReasoningMarker(reasoning).hasAiSelfReference(aiSelfReference).hasLeadingOrTrailingContent(outsideJson);
      long rawContentLimit = Math.min(1048576L, this.properties.getMaxTextChars() + 16384L);
      if (rawJson.length() > rawContentLimit) {
         throw this.invalid(QwenResponseValidationStage.CONTENT, QwenResponseValidationCode.QWEN_CONTENT_TOO_LARGE, shape);
      }

      JsonNode root;
      try {
         root = StrictProviderJson.parse(this.objectMapper, rawJson);
         shape.jsonParsed(true).topLevelType(QwenSafeValueType.from(root));
      } catch (Exception exception) {
         shape.jsonParsed(false);
         if (markdown) {
            throw this.invalid(QwenResponseValidationStage.CONTENT, QwenResponseValidationCode.QWEN_CONTENT_MARKDOWN_FENCE, shape);
         }

         if (html) {
            throw this.invalid(QwenResponseValidationStage.CONTENT, QwenResponseValidationCode.QWEN_CONTENT_HTML, shape);
         }

         if (reasoning) {
            throw this.invalid(QwenResponseValidationStage.CONTENT, QwenResponseValidationCode.QWEN_CONTENT_REASONING_MARKER, shape);
         }

         if (aiSelfReference) {
            throw this.invalid(QwenResponseValidationStage.CONTENT, QwenResponseValidationCode.QWEN_OUTPUT_FORBIDDEN_CONTENT, shape);
         }

         if (!outsideJson && !QwenResponseInspection.isTrailingTokenFailure(exception)) {
            if (QwenResponseInspection.isDuplicateFieldFailure(exception)) {
               shape.duplicateFieldCount(1L);
               throw this.invalid(QwenResponseValidationStage.JSON_STRUCTURE, QwenResponseValidationCode.QWEN_JSON_DUPLICATE_FIELD, shape);
            }

            throw this.invalid(QwenResponseValidationStage.JSON_SYNTAX, QwenResponseValidationCode.QWEN_JSON_PARSE_FAILED, shape);
         }

         shape.hasLeadingOrTrailingContent(true);
         throw this.invalid(QwenResponseValidationStage.JSON_SYNTAX, QwenResponseValidationCode.QWEN_JSON_TRAILING_CONTENT, shape);
      }

      if (markdown) {
         throw this.invalid(QwenResponseValidationStage.CONTENT, QwenResponseValidationCode.QWEN_CONTENT_MARKDOWN_FENCE, shape);
      }

      if (html) {
         throw this.invalid(QwenResponseValidationStage.CONTENT, QwenResponseValidationCode.QWEN_CONTENT_HTML, shape);
      }

      if (reasoning) {
         throw this.invalid(QwenResponseValidationStage.CONTENT, QwenResponseValidationCode.QWEN_CONTENT_REASONING_MARKER, shape);
      }

      if (aiSelfReference) {
         throw this.invalid(QwenResponseValidationStage.CONTENT, QwenResponseValidationCode.QWEN_OUTPUT_FORBIDDEN_CONTENT, shape);
      }

      if (ChineseTextRules.containsForbiddenMarkupOrLeakage(rawJson) || ChineseTextRules.containsUnsupportedAuthorshipClaim(rawJson)) {
         throw this.invalid(QwenResponseValidationStage.CONTENT, QwenResponseValidationCode.QWEN_OUTPUT_FORBIDDEN_CONTENT, shape);
      }

      if (root != null && root.isObject()) {
         PaintingPoemResultValidator.ForbiddenFacts decodedForbidden = this.inspectDecodedKnownContent(root);
         shape.hasMarkdownFence(markdown || decodedForbidden.markdown())
            .hasHtml(html || decodedForbidden.html())
            .hasReasoningMarker(reasoning || decodedForbidden.reasoning())
            .hasAiSelfReference(aiSelfReference || decodedForbidden.aiSelfReference());
         if (decodedForbidden.markdown()) {
            throw this.invalid(QwenResponseValidationStage.CONTENT, QwenResponseValidationCode.QWEN_CONTENT_MARKDOWN_FENCE, shape);
         }

         if (decodedForbidden.html()) {
            throw this.invalid(QwenResponseValidationStage.CONTENT, QwenResponseValidationCode.QWEN_CONTENT_HTML, shape);
         }

         if (decodedForbidden.reasoning()) {
            throw this.invalid(QwenResponseValidationStage.CONTENT, QwenResponseValidationCode.QWEN_CONTENT_REASONING_MARKER, shape);
         }

         if (!decodedForbidden.aiSelfReference() && !decodedForbidden.otherForbidden()) {
            this.collectAvailableShape(root, shape);
            int unknownFields = this.countUnknownFields(root);
            shape.unknownFieldCount(unknownFields);
            if (unknownFields > 0) {
               throw this.invalid(QwenResponseValidationStage.JSON_STRUCTURE, QwenResponseValidationCode.QWEN_JSON_UNKNOWN_FIELDS, shape);
            }

            boolean schemaPresent = root.has("schemaVersion");
            shape.schemaVersionPresent(schemaPresent);
            if (!schemaPresent) {
               throw this.invalid(QwenResponseValidationStage.POEM_SCHEMA, QwenResponseValidationCode.QWEN_SCHEMA_VERSION_MISSING, shape);
            }

            JsonNode schemaNode = root.get("schemaVersion");
            shape.schemaVersionType(QwenSafeValueType.from(schemaNode));
            if (!schemaNode.isTextual()) {
               throw this.invalid(QwenResponseValidationStage.POEM_SCHEMA, QwenResponseValidationCode.QWEN_SCHEMA_VERSION_TYPE_INVALID, shape);
            }

            String schemaVersion = schemaNode.textValue().trim();
            if (!"1".equals(schemaVersion)) {
               throw this.invalid(QwenResponseValidationStage.POEM_SCHEMA, QwenResponseValidationCode.QWEN_SCHEMA_VERSION_UNSUPPORTED, shape);
            }

            boolean titlePresent = root.has("title");
            shape.titlePresent(titlePresent);
            if (!titlePresent) {
               throw this.invalid(QwenResponseValidationStage.POEM_SCHEMA, QwenResponseValidationCode.QWEN_TITLE_MISSING, shape);
            }

            JsonNode titleNode = root.get("title");
            shape.titleType(QwenSafeValueType.from(titleNode));
            String title = null;
            if (!titleNode.isNull()) {
               if (!titleNode.isTextual()) {
                  throw this.invalid(QwenResponseValidationStage.POEM_SCHEMA, QwenResponseValidationCode.QWEN_TITLE_TYPE_INVALID, shape);
               }

               title = titleNode.textValue().trim();
               shape.titleLength(title.length());
               if (title.isEmpty()) {
                  throw this.invalid(QwenResponseValidationStage.POEM_SEMANTICS, QwenResponseValidationCode.QWEN_TITLE_BLANK, shape);
               }

               if (title.length() > 100) {
                  throw this.invalid(QwenResponseValidationStage.POEM_SEMANTICS, QwenResponseValidationCode.QWEN_TITLE_TOO_LONG, shape);
               }

               if (!(generated ? isChinesePoemText(title) : ChineseTextRules.containsChinese(title))) {
                  throw this.invalid(QwenResponseValidationStage.POEM_SEMANTICS, QwenResponseValidationCode.QWEN_TITLE_NON_CHINESE, shape);
               }
            }

            boolean linesPresent = root.has("lines");
            shape.linesPresent(linesPresent);
            if (!linesPresent) {
               throw this.invalid(QwenResponseValidationStage.POEM_SCHEMA, QwenResponseValidationCode.QWEN_LINES_MISSING, shape);
            }

            JsonNode linesNode = root.get("lines");
            shape.linesType(QwenSafeValueType.from(linesNode));
            if (!linesNode.isArray()) {
               throw this.invalid(QwenResponseValidationStage.POEM_SCHEMA, QwenResponseValidationCode.QWEN_LINES_TYPE_INVALID, shape);
            }

            PaintingPoemResultValidator.LineFacts lineFacts = this.inspectLines(linesNode);
            shape.lineCount(linesNode.size())
               .stringLineCount(lineFacts.stringCount())
               .nonblankLineCount(lineFacts.nonblankCount())
               .chineseDominantLineCount(lineFacts.chineseDominantCount())
               .duplicateLineCount(lineFacts.duplicateCount());
            if (lineFacts.minimumLength() != null) {
               shape.minimumLineLength(lineFacts.minimumLength().intValue()).maximumLineLength(lineFacts.maximumLength().intValue());
            }

            if (linesNode.size() != 4) {
               throw this.invalid(QwenResponseValidationStage.POEM_SCHEMA, QwenResponseValidationCode.QWEN_LINES_COUNT_INVALID, shape);
            } else if (lineFacts.stringCount() != linesNode.size()) {
               throw this.invalid(QwenResponseValidationStage.POEM_SCHEMA, QwenResponseValidationCode.QWEN_LINE_TYPE_INVALID, shape);
            } else if (lineFacts.nonblankCount() != linesNode.size()) {
               throw this.invalid(QwenResponseValidationStage.POEM_SEMANTICS, QwenResponseValidationCode.QWEN_LINE_BLANK, shape);
            } else if (lineFacts.maximumLength() != null && lineFacts.maximumLength() > 100) {
               throw this.invalid(QwenResponseValidationStage.POEM_SEMANTICS, QwenResponseValidationCode.QWEN_LINE_TOO_LONG, shape);
            } else if (generated
               ? lineFacts.normalizedLines().stream().anyMatch(line -> !isChinesePoemText(line))
               : lineFacts.chineseDominantCount() != linesNode.size()) {
               throw this.invalid(QwenResponseValidationStage.POEM_SEMANTICS, QwenResponseValidationCode.QWEN_LINE_NON_CHINESE, shape);
            } else if (lineFacts.duplicateCount() > 0) {
               throw this.invalid(QwenResponseValidationStage.POEM_SEMANTICS, QwenResponseValidationCode.QWEN_LINE_DUPLICATE, shape);
            } else {
               boolean textPresent = root.has("text");
               shape.textPresent(textPresent);
               if (!textPresent) {
                  throw this.invalid(QwenResponseValidationStage.POEM_SCHEMA, QwenResponseValidationCode.QWEN_TEXT_MISSING, shape);
               } else {
                  JsonNode textNode = root.get("text");
                  shape.textType(QwenSafeValueType.from(textNode));
                  if (!textNode.isTextual()) {
                     throw this.invalid(QwenResponseValidationStage.POEM_SCHEMA, QwenResponseValidationCode.QWEN_TEXT_TYPE_INVALID, shape);
                  } else {
                     String text = textNode.textValue().trim();
                     shape.textLength(text.length());
                     if (text.isEmpty()) {
                        throw this.invalid(QwenResponseValidationStage.POEM_SEMANTICS, QwenResponseValidationCode.QWEN_TEXT_BLANK, shape);
                     } else {
                        boolean textMatches = text.equals(String.join("\n", lineFacts.normalizedLines()));
                        shape.textMatchesLines(textMatches);
                        if (!textMatches) {
                           throw this.invalid(QwenResponseValidationStage.POEM_SEMANTICS, QwenResponseValidationCode.QWEN_TEXT_MISMATCH, shape);
                        } else {
                           int total = text.length() + (title == null ? 0 : title.length());
                           if (total > this.properties.getMaxTextChars()) {
                              throw this.invalid(QwenResponseValidationStage.POEM_SEMANTICS, QwenResponseValidationCode.QWEN_CONTENT_TOO_LARGE, shape);
                           } else {
                              return new PaintingPoemResult(schemaVersion, title, lineFacts.normalizedLines(), text);
                           }
                        }
                     }
                  }
               }
            }
         } else {
            throw this.invalid(QwenResponseValidationStage.CONTENT, QwenResponseValidationCode.QWEN_OUTPUT_FORBIDDEN_CONTENT, shape);
         }
      } else {
         throw this.invalid(QwenResponseValidationStage.JSON_STRUCTURE, QwenResponseValidationCode.QWEN_JSON_ROOT_NOT_OBJECT, shape);
      }
   }

   private static boolean isChinesePoemText(String value) {
      return POEM_CHARACTERS.matcher(value).matches()
         && value.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
   }

   private void collectAvailableShape(JsonNode root, QwenResponseShapeDiagnostic.Builder shape) {
      boolean schemaPresent = root.has("schemaVersion");
      shape.schemaVersionPresent(schemaPresent);
      if (schemaPresent) {
         shape.schemaVersionType(QwenSafeValueType.from(root.get("schemaVersion")));
      }

      boolean titlePresent = root.has("title");
      shape.titlePresent(titlePresent);
      if (titlePresent) {
         JsonNode titleNode = root.get("title");
         shape.titleType(QwenSafeValueType.from(titleNode));
         if (titleNode.isTextual()) {
            shape.titleLength(titleNode.textValue().trim().length());
         }
      }

      boolean linesPresent = root.has("lines");
      shape.linesPresent(linesPresent);
      PaintingPoemResultValidator.LineFacts lineFacts = null;
      if (linesPresent) {
         JsonNode linesNode = root.get("lines");
         shape.linesType(QwenSafeValueType.from(linesNode));
         if (linesNode.isArray()) {
            lineFacts = this.inspectLines(linesNode);
            shape.lineCount(linesNode.size())
               .stringLineCount(lineFacts.stringCount())
               .nonblankLineCount(lineFacts.nonblankCount())
               .chineseDominantLineCount(lineFacts.chineseDominantCount())
               .duplicateLineCount(lineFacts.duplicateCount());
            if (lineFacts.minimumLength() != null) {
               shape.minimumLineLength(lineFacts.minimumLength().intValue()).maximumLineLength(lineFacts.maximumLength().intValue());
            }
         }
      }

      boolean textPresent = root.has("text");
      shape.textPresent(textPresent);
      if (textPresent) {
         JsonNode textNode = root.get("text");
         shape.textType(QwenSafeValueType.from(textNode));
         if (textNode.isTextual()) {
            String text = textNode.textValue().trim();
            shape.textLength(text.length());
            JsonNode linesNode = root.get("lines");
            if (lineFacts != null && linesNode != null && lineFacts.stringCount() == linesNode.size()) {
               shape.textMatchesLines(text.equals(String.join("\n", lineFacts.normalizedLines())));
            }
         }
      }
   }

   private int countUnknownFields(JsonNode root) {
      int count = 0;
      Iterator<String> names = root.fieldNames();

      while (names.hasNext()) {
         if (!FIELDS.contains(names.next())) {
            count++;
         }
      }

      return count;
   }

   private PaintingPoemResultValidator.ForbiddenFacts inspectDecodedKnownContent(JsonNode root) {
      PaintingPoemResultValidator.ForbiddenFacts facts = this.inspectDecodedText(root.get("title"));
      JsonNode linesNode = root.get("lines");
      if (linesNode != null && linesNode.isArray()) {
         for (JsonNode lineNode : linesNode) {
            facts = facts.merge(this.inspectDecodedText(lineNode));
         }
      }

      return facts.merge(this.inspectDecodedText(root.get("text")));
   }

   private PaintingPoemResultValidator.ForbiddenFacts inspectDecodedText(JsonNode node) {
      if (node != null && node.isTextual()) {
         String value = node.textValue();
         boolean markdown = QwenResponseInspection.hasMarkdownFence(value);
         boolean html = QwenResponseInspection.hasHtml(value);
         boolean reasoning = QwenResponseInspection.hasReasoningMarker(value);
         boolean aiSelfReference = QwenResponseInspection.hasAiSelfReference(value);
         boolean otherForbidden = ChineseTextRules.containsForbiddenMarkupOrLeakage(value) || ChineseTextRules.containsUnsupportedAuthorshipClaim(value);
         return new PaintingPoemResultValidator.ForbiddenFacts(markdown, html, reasoning, aiSelfReference, otherForbidden);
      } else {
         return PaintingPoemResultValidator.ForbiddenFacts.NONE;
      }
   }

   private PaintingPoemResultValidator.LineFacts inspectLines(JsonNode linesNode) {
      List<String> normalized = new ArrayList<>();
      Set<String> distinct = new HashSet<>();
      int strings = 0;
      int nonblank = 0;
      int chineseDominant = 0;
      int duplicates = 0;
      Integer minimum = null;
      Integer maximum = null;

      for (JsonNode lineNode : linesNode) {
         if (lineNode != null && lineNode.isTextual()) {
            strings++;
            String line = lineNode.textValue().trim();
            normalized.add(line);
            int length = line.length();
            minimum = minimum == null ? length : Math.min(minimum, length);
            maximum = maximum == null ? length : Math.max(maximum, length);
            if (!line.isEmpty()) {
               nonblank++;
            }

            if (QwenResponseInspection.isChineseDominant(line)) {
               chineseDominant++;
            }

            if (!distinct.add(line)) {
               duplicates++;
            }
         }
      }

      return new PaintingPoemResultValidator.LineFacts(List.copyOf(normalized), strings, nonblank, chineseDominant, duplicates, minimum, maximum);
   }

   private ProviderExecutionException invalid(QwenResponseValidationStage stage, QwenResponseValidationCode code, QwenResponseShapeDiagnostic.Builder shape) {
      return ProviderExecutionException.fromSafeDiagnostic(
         ProviderErrorCategory.PROVIDER_INVALID_RESPONSE,
         "Qwen painting-to-poem response failed strict validation",
         new QwenResponseValidationDiagnostic(stage, code, shape.build())
      );
   }

   public PaintingPoemResultValidator(final ObjectMapper objectMapper, final CreationProviderProperties properties) {
      this.objectMapper = objectMapper;
      this.properties = properties;
   }

   private record ForbiddenFacts(boolean markdown, boolean html, boolean reasoning, boolean aiSelfReference, boolean otherForbidden) {
      private static final PaintingPoemResultValidator.ForbiddenFacts NONE = new PaintingPoemResultValidator.ForbiddenFacts(false, false, false, false, false);

      private PaintingPoemResultValidator.ForbiddenFacts merge(PaintingPoemResultValidator.ForbiddenFacts other) {
         return new PaintingPoemResultValidator.ForbiddenFacts(
            this.markdown || other.markdown,
            this.html || other.html,
            this.reasoning || other.reasoning,
            this.aiSelfReference || other.aiSelfReference,
            this.otherForbidden || other.otherForbidden
         );
      }
   }

   private record LineFacts(
      List<String> normalizedLines,
      int stringCount,
      int nonblankCount,
      int chineseDominantCount,
      int duplicateCount,
      Integer minimumLength,
      Integer maximumLength
   ) {
   }
}
