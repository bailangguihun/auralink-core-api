package com.auralink.provider.qwen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.creation.provider.ProviderErrorCategory;
import com.auralink.creation.provider.ProviderExecutionException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PaintingPoemResultValidatorTest {
   private final ObjectMapper mapper = new ObjectMapper();
   private final PaintingPoemResultValidator validator = new PaintingPoemResultValidator(mapper, new CreationProviderProperties());

   @Test
   void acceptsChineseVerseWithPunctuationAndOptionalTitle() {
      List<String> lines = List.of("柳丝垂碧蘸春烟，", "黄鸟双鸣亭畔前。", "白鹭一行穿薄雾，", "远山如黛水连天。");

      assertThat(validator.validateGenerated(poem("《春江》", lines)).lines()).isEqualTo(lines);
      assertThat(validator.validateGenerated(poem(null, lines)).title()).isNull();
   }

   @ParameterizedTest
   @ValueSource(strings = {"白鹭一行穿 mist 雾", "白鹭一行穿 ｍｉｓｔ 雾", "白鹭一行穿 mù 雾", "白鹭一行飞过 2 座山"})
   void rejectsMixedLanguageOrDigitsOnTheProviderAndWritePaths(String mixedLine) {
      String json = poem(null, List.of("柳丝垂碧蘸春烟", "黄鸟双鸣亭畔前", mixedLine, "远山如黛水连天"));
      QwenResponseContent response = new QwenResponseContent(json, QwenResponseShapeDiagnostic.builder().build());

      assertLanguageFailure(assertThrows(ProviderExecutionException.class, () -> validator.validate(response)),
         QwenResponseValidationCode.QWEN_LINE_NON_CHINESE);
      assertLanguageFailure(assertThrows(ProviderExecutionException.class, () -> validator.validateGenerated(json)),
         QwenResponseValidationCode.QWEN_LINE_NON_CHINESE);
      assertThat(validator.validate(json).lines()).contains(mixedLine);
   }

   @Test
   void rejectsEnglishInTheTitleAndUnmatchedText() {
      List<String> lines = List.of("柳丝垂碧蘸春烟", "黄鸟双鸣亭畔前", "白鹭一行穿薄雾", "远山如黛水连天");

      assertLanguageFailure(assertThrows(ProviderExecutionException.class, () -> validator.validateGenerated(poem("春江 Mist", lines))),
         QwenResponseValidationCode.QWEN_TITLE_NON_CHINESE);
      String unmatched = poem(null, lines).replace("白鹭一行穿薄雾\\n", "白鹭一行穿 mist 雾\\n");
      assertLanguageFailure(assertThrows(ProviderExecutionException.class, () -> validator.validateGenerated(unmatched)),
         QwenResponseValidationCode.QWEN_TEXT_MISMATCH);
   }

   private String poem(String title, List<String> lines) {
      var poem = mapper.createObjectNode().put("schemaVersion", "1").put("title", title).put("text", String.join("\n", lines));
      poem.set("lines", mapper.valueToTree(lines));
      return poem.toString();
   }

   private static void assertLanguageFailure(ProviderExecutionException failure, QwenResponseValidationCode code) {
      assertThat(failure.category()).isEqualTo(ProviderErrorCategory.PROVIDER_INVALID_RESPONSE);
      assertThat(failure.safeDiagnostic()).isInstanceOfSatisfying(QwenResponseValidationDiagnostic.class,
         diagnostic -> assertThat(diagnostic.validationCode()).isEqualTo(code));
   }
}
