package com.auralink.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OfficialPaintingRecordTest {

    @Test
    void sourceKeyUsesTheFrozenPrefixAndTrimmedOfficialImageName() {
        OfficialPaintingRecord record = recordWithImageName("  1（1）  ");

        assertThat(record.imageStorageName()).isEqualTo("1（1）");
        assertThat(record.sourceKey()).isEqualTo("painting-dataset:1（1）");
    }

    @Test
    void blankOptionalAnnotationsBecomeNullWhileLiteralZeroIsPreserved() {
        OfficialPaintingRecord record = new OfficialPaintingRecord(
                " 0 ", "image-1", "  ", "作者", "0", "", "", "", "未知朝代",
                "", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
                "  官方文本  ", "", "");

        assertThat(record.sourceSequence()).isEqualTo("0");
        assertThat(record.title()).isNull();
        assertThat(record.authorBirthYear()).isEqualTo("0");
        assertThat(record.generatedText()).isEqualTo("官方文本");
        assertThat(record.creationDynastyRaw()).isEqualTo("未知朝代");
        assertThat(record.culturalSymbol()).isNull();
        assertThat(record.collectionPlatform()).isNull();
        assertThat(record.category()).isNull();
    }

    @Test
    void blankImageStorageNameIsRejectedBeforeAKeyCanBeCreated() {
        assertThatThrownBy(() -> recordWithImageName(" \t "))
                .isInstanceOf(CatalogSourceException.class)
                .hasMessageContaining("image storage name");
    }

    private OfficialPaintingRecord recordWithImageName(String imageStorageName) {
        return new OfficialPaintingRecord(
                "1", imageStorageName, "画作", "作者", "", "", "", "", "明代",
                "", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
                "", "", "");
    }
}
