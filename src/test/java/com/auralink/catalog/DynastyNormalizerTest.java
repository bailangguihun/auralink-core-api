package com.auralink.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DynastyNormalizerTest {

    private final DynastyNormalizer normalizer = new DynastyNormalizer();

    @Test
    void normalizesDocumentedDynastyVariantsToStableFilterValues() {
        assertThat(normalizer.normalize("唐")).isEqualTo("唐代");
        assertThat(normalizer.normalize("唐朝")).isEqualTo("唐代");
        assertThat(normalizer.normalize("宋")).isEqualTo("宋代");
        assertThat(normalizer.normalize("宋朝")).isEqualTo("宋代");
        assertThat(normalizer.normalize("元")).isEqualTo("元代");
        assertThat(normalizer.normalize("元朝")).isEqualTo("元代");
        assertThat(normalizer.normalize("明")).isEqualTo("明代");
        assertThat(normalizer.normalize("明朝")).isEqualTo("明代");
        assertThat(normalizer.normalize("清")).isEqualTo("清代");
        assertThat(normalizer.normalize("清朝")).isEqualTo("清代");
        assertThat(normalizer.normalize("民国年间")).isEqualTo("民国");
    }

    @Test
    void preservesUnknownValuesInsteadOfGuessingAndNormalizesOnlyOuterWhitespace() {
        assertThat(normalizer.normalize("  东晋  ")).isEqualTo("东晋");
        assertThat(normalizer.normalize("北宋")).isEqualTo("北宋");
        assertThat(normalizer.normalize("南宋")).isEqualTo("南宋");
        assertThat(normalizer.normalize("清初")).isEqualTo("清初");
        assertThat(normalizer.normalize("晚清")).isEqualTo("晚清");
        assertThat(normalizer.normalize("清末民初")).isEqualTo("清末民初");
        assertThat(normalizer.normalize("当代实验水墨")).isEqualTo("当代实验水墨");
        assertThat(normalizer.normalize("清代中期")).isEqualTo("清代中期");
        assertThat(normalizer.normalize("未知   年代")).isEqualTo("未知 年代");
    }

    @Test
    void nullAndBlankValuesBecomeNull() {
        assertThat(normalizer.normalize(null)).isNull();
        assertThat(normalizer.normalize(" \t\n ")).isNull();
    }
}
