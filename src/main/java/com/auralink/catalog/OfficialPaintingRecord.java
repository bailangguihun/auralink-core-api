package com.auralink.catalog;

import java.util.List;

/**
 * One typed row from the inherited 27-column official painting catalog.
 *
 * <p>Outer whitespace is removed because the inherited loader already treats
 * cells that way. Literal values (including {@code "0"}) are preserved exactly;
 * truly blank optional metadata is represented as {@code null}.</p>
 */
public record OfficialPaintingRecord(
        String sourceSequence,
        String imageStorageName,
        String title,
        String authorName,
        String authorBirthYear,
        String authorBirthPlace,
        String authorSchool,
        String creationYear,
        String creationDynastyRaw,
        String actualSize,
        String collectionInstitution,
        String category,
        String subject,
        String paintingSchool,
        String style,
        String color,
        String composition,
        String artisticConception,
        String brushwork,
        String inkMethod,
        String paintingMaterial,
        String pigment,
        String seal,
        String culturalSymbol,
        String generatedText,
        String musicSceneDescription,
        String collectionPlatform) {

    public static final String SOURCE_KEY_PREFIX = "painting-dataset:";

    public static final List<String> CSV_HEADERS = List.of(
            "序号",
            "图像存储名称",
            "画作名称",
            "作者姓名",
            "作者出生年份",
            "作者出生地",
            "作者流派",
            "创作年代",
            "创作朝代",
            "实际尺寸",
            "收藏机构",
            "分类",
            "题材",
            "画作流派",
            "风格",
            "色彩",
            "构图",
            "意境",
            "笔法",
            "墨法",
            "绘画材料",
            "颜料",
            "印章",
            "文化符号",
            "文本生成",
            "音乐情境生成",
            "收集平台");

    public OfficialPaintingRecord {
        sourceSequence = cleanOptional(sourceSequence);
        imageStorageName = cleanPrimary(imageStorageName);
        title = cleanOptional(title);
        authorName = cleanOptional(authorName);
        authorBirthYear = cleanOptional(authorBirthYear);
        authorBirthPlace = cleanOptional(authorBirthPlace);
        authorSchool = cleanOptional(authorSchool);
        creationYear = cleanOptional(creationYear);
        creationDynastyRaw = cleanOptional(creationDynastyRaw);
        actualSize = cleanOptional(actualSize);
        collectionInstitution = cleanOptional(collectionInstitution);
        category = cleanOptional(category);
        subject = cleanOptional(subject);
        paintingSchool = cleanOptional(paintingSchool);
        style = cleanOptional(style);
        color = cleanOptional(color);
        composition = cleanOptional(composition);
        artisticConception = cleanOptional(artisticConception);
        brushwork = cleanOptional(brushwork);
        inkMethod = cleanOptional(inkMethod);
        paintingMaterial = cleanOptional(paintingMaterial);
        pigment = cleanOptional(pigment);
        seal = cleanOptional(seal);
        culturalSymbol = cleanOptional(culturalSymbol);
        generatedText = cleanOptional(generatedText);
        musicSceneDescription = cleanOptional(musicSceneDescription);
        collectionPlatform = cleanOptional(collectionPlatform);

        if (imageStorageName.isBlank()) {
            throw new CatalogSourceException("Official painting image storage name must not be blank");
        }
    }

    public String sourceKey() {
        return SOURCE_KEY_PREFIX + imageStorageName;
    }

    private static String cleanPrimary(String value) {
        return value == null ? "" : value.trim();
    }

    private static String cleanOptional(String value) {
        String cleaned = cleanPrimary(value);
        return cleaned.isBlank() ? null : cleaned;
    }
}
