package com.auralink.api.v1.painting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.auralink.entity.MediaAsset;
import com.auralink.entity.Painting;
import com.auralink.entity.PaintingFavorite;
import com.auralink.entity.User;
import com.auralink.media.MediaAssetValues;
import com.auralink.repository.MediaAssetRepository;
import com.auralink.repository.PaintingFavoriteRepository;
import com.auralink.repository.PaintingRepository;
import com.auralink.repository.UserRepository;

@SpringBootTest(properties = {
        "spring.config.import=optional:file:/tmp/auralink-round5-api-no-env.properties",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.flyway.baseline-on-migrate=false",
        "spring.datasource.hikari.connection-init-sql=PRAGMA foreign_keys=ON",
        "auralink.jwt.secret=round5-painting-api-test-secret-that-is-not-used-elsewhere",
        "auralink.paintings.import-enabled=false",
        "auralink.paintings.daily-zone=Pacific/Kiritimati"
})
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional
class PaintingApiIntegrationTest {

    private static final ZoneId DAILY_ZONE = ZoneId.of("Pacific/Kiritimati");
    private static final Path ROOT = Path.of(
            "/tmp", "auralink-round5-painting-api-" + UUID.randomUUID());
    private static final Path DATABASE = ROOT.resolve("painting-api.db");
    private static final AtomicInteger IDS = new AtomicInteger();

    @DynamicPropertySource
    static void isolatedRuntime(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE);
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("auralink.media-assets.managed-dir",
                () -> ROOT.resolve("managed").toString());
        registry.add("auralink.paintings.metadata-csv-path",
                () -> ROOT.resolve("not-read.csv").toString());
        registry.add("auralink.paintings.picture-dir",
                () -> ROOT.resolve("catalog").toString());
        registry.add("auralink.storage.upload-dir",
                () -> ROOT.resolve("legacy-uploads").toString());
        registry.add("auralink.storage.audio-dir",
                () -> ROOT.resolve("legacy-audio").toString());
        registry.add("auralink.storage.legacy-frontend-audio-dir",
                () -> ROOT.resolve("legacy-frontend-audio").toString());
        try {
            Files.createDirectories(ROOT);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Unable to create isolated painting API test root", exception);
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private MediaAssetRepository mediaAssets;
    @Autowired private PaintingRepository paintings;
    @Autowired private PaintingFavoriteRepository favorites;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void galleryRequiresActiveVisibleImageRowsAndSupportsEveryFrozenFilter() throws Exception {
        User owner = saveUser("gallery");
        Painting first = savePainting("painting-dataset:a.jpg", true, true, "ACTIVE", true);
        Painting second = savePainting("painting-dataset:b.jpg", true, true, "ACTIVE", true);
        second.setTitle("花鸟图");
        second.setAuthorName("另一画家");
        second.setCreationDynastyRaw("宋朝");
        second.setCreationDynastyNormalized("宋代");
        second.setCategory("油画");
        second.setSubject("花鸟");
        second.setPaintingSchool("院体");
        second.setStyle("工丽");
        second.setArtisticConception("富丽");
        second.setPaintingMaterial("纸本");
        second.setCollectionInstitution("另一博物馆");
        second.setGeneratedText("另一段知识说明");
        second.setCollectionPlatform("其他平台");
        paintings.saveAndFlush(second);
        savePainting("painting-dataset:hidden.jpg", true, false, "ACTIVE", true);
        savePainting("painting-dataset:missing.jpg", false, true, "ACTIVE", false);
        savePainting("painting-dataset:inactive.jpg", true, true, "INACTIVE", true);
        Painting privateImage = savePainting(
                "painting-dataset:private-image.jpg", true, true, "ACTIVE", true);
        privateImage.getImageAsset().setVisibility(MediaAssetValues.Visibility.PRIVATE);
        mediaAssets.saveAndFlush(privateImage.getImageAsset());
        Painting inactiveImage = savePainting(
                "painting-dataset:inactive-image.jpg", true, true, "ACTIVE", true);
        inactiveImage.getImageAsset().setStatus(MediaAssetValues.Status.DELETED);
        mediaAssets.saveAndFlush(inactiveImage.getImageAsset());
        favorites.saveAndFlush(PaintingFavorite.builder().user(owner).painting(first).build());

        mockMvc.perform(get("/api/v1/paintings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(24))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].paintingId").value(first.getPublicId()))
                .andExpect(jsonPath("$.items[0].favorited").value(false))
                .andExpect(jsonPath("$.items[0].paintingSchool").value("山水流派"))
                .andExpect(jsonPath("$.items[0].style").value("雄浑"))
                .andExpect(jsonPath("$.items[0].artisticConception").value("清远"))
                .andExpect(jsonPath("$.items[0].sourceKey").doesNotExist())
                .andExpect(jsonPath("$.items[0].id").doesNotExist())
                .andExpect(jsonPath("$.items[0].image.storageKey").doesNotExist());

        assertFilterReturnsOnly("keyword", "官方知识文本", first.getPublicId());
        assertFilterReturnsOnly("dynasty", "清朝", first.getPublicId());
        assertFilterReturnsOnly("category", "中国画", first.getPublicId());
        assertFilterReturnsOnly("author", "测试画家", first.getPublicId());
        assertFilterReturnsOnly("subject", "山水", first.getPublicId());
        assertFilterReturnsOnly("paintingSchool", "山水流派", first.getPublicId());
        assertFilterReturnsOnly("style", "雄浑", first.getPublicId());
        assertFilterReturnsOnly("artisticConception", "清远", first.getPublicId());
        assertFilterReturnsOnly("paintingMaterial", "绢本", first.getPublicId());
        assertFilterReturnsOnly("collectionInstitution", "测试博物馆", first.getPublicId());
        assertFilterReturnsOnly("collectionPlatform", "官方平台", first.getPublicId());

        mockMvc.perform(get("/api/v1/paintings")
                        .param("keyword", "官方知识文本")
                        .param("dynasty", "清朝")
                        .param("category", "中国画")
                        .param("author", "测试画家")
                        .param("subject", "山水")
                        .param("paintingSchool", "山水流派")
                        .param("style", "雄浑")
                        .param("artisticConception", "清远")
                        .param("paintingMaterial", "绢本")
                        .param("collectionInstitution", "测试博物馆")
                        .param("collectionPlatform", "官方平台")
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].paintingId").value(first.getPublicId()))
                .andExpect(jsonPath("$.items[0].favorited").value(true));

        assertThat(first.getSourceKey()).isLessThan(second.getSourceKey());
    }

    @Test
    void everyPublicSortSupportsBothDirectionsWithStablePublicIdTieBreaks() throws Exception {
        Painting first = savePainting("painting-dataset:c.jpg", true, true, "ACTIVE", true);
        Painting second = savePainting("painting-dataset:a.jpg", true, true, "ACTIVE", true);
        Painting third = savePainting("painting-dataset:b.jpg", true, true, "ACTIVE", true);

        first.setTitle("same-title");
        first.setAuthorName("beta-author");
        first.setCreationDynastyNormalized("qing-dynasty");
        second.setTitle("same-title");
        second.setAuthorName("alpha-author");
        second.setCreationDynastyNormalized("ming-dynasty");
        third.setTitle("other-title");
        third.setAuthorName("gamma-author");
        third.setCreationDynastyNormalized("tang-dynasty");
        paintings.saveAllAndFlush(List.of(first, second, third));

        List<Painting> all = List.of(first, second, third);
        assertPaintingOrder("source", "asc", ordered(
                all,
                Comparator.comparing(Painting::getSourceKey)
                        .thenComparing(Painting::getPublicId)));
        assertPaintingOrder("source", "desc", ordered(
                all,
                Comparator.comparing(Painting::getSourceKey, Comparator.reverseOrder())
                        .thenComparing(Painting::getPublicId)));
        assertPaintingOrder("title", "asc", ordered(
                all,
                Comparator.comparing(Painting::getTitle)
                        .thenComparing(Painting::getPublicId)));
        assertPaintingOrder("title", "desc", ordered(
                all,
                Comparator.comparing(Painting::getTitle, Comparator.reverseOrder())
                        .thenComparing(Painting::getPublicId)));
        assertPaintingOrder("author", "asc", ordered(
                all,
                Comparator.comparing(Painting::getAuthorName)
                        .thenComparing(Painting::getPublicId)));
        assertPaintingOrder("author", "desc", ordered(
                all,
                Comparator.comparing(Painting::getAuthorName, Comparator.reverseOrder())
                        .thenComparing(Painting::getPublicId)));
        assertPaintingOrder("dynasty", "asc", ordered(
                all,
                Comparator.comparing(Painting::getCreationDynastyNormalized)
                        .thenComparing(Painting::getPublicId)));
        assertPaintingOrder("dynasty", "desc", ordered(
                all,
                Comparator.comparing(
                                Painting::getCreationDynastyNormalized,
                                Comparator.reverseOrder())
                        .thenComparing(Painting::getPublicId)));
    }

    @Test
    void authenticatedDetailReturnsAllOfficialFieldsForHiddenMissingImageRow() throws Exception {
        User owner = saveUser("detail");
        Painting hiddenMissing = savePainting(
                "painting-dataset:hidden-detail.jpg", false, false, "ACTIVE", false);
        Painting inactive = savePainting(
                "painting-dataset:inactive-detail.jpg", false, false, "INACTIVE", false);

        mockMvc.perform(get("/api/v1/paintings/{id}", hiddenMissing.getPublicId())
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paintingId").value(hiddenMissing.getPublicId()))
                .andExpect(jsonPath("$.sourceSequence").value("7"))
                .andExpect(jsonPath("$.imageStorageName").value("official-image"))
                .andExpect(jsonPath("$.title").value("山水图"))
                .andExpect(jsonPath("$.authorName").value("测试画家"))
                .andExpect(jsonPath("$.authorBirthYear").value("1600"))
                .andExpect(jsonPath("$.authorBirthPlace").value("江南"))
                .andExpect(jsonPath("$.authorSchool").value("正统派"))
                .andExpect(jsonPath("$.creationYear").value("1650"))
                .andExpect(jsonPath("$.creationDynastyRaw").value("清朝"))
                .andExpect(jsonPath("$.creationDynastyNormalized").value("清代"))
                .andExpect(jsonPath("$.actualSize").value("100x50cm"))
                .andExpect(jsonPath("$.collectionInstitution").value("测试博物馆"))
                .andExpect(jsonPath("$.category").value("中国画"))
                .andExpect(jsonPath("$.subject").value("山水"))
                .andExpect(jsonPath("$.paintingSchool").value("山水流派"))
                .andExpect(jsonPath("$.style").value("雄浑"))
                .andExpect(jsonPath("$.color").value("水墨"))
                .andExpect(jsonPath("$.composition").value("高远"))
                .andExpect(jsonPath("$.artisticConception").value("清远"))
                .andExpect(jsonPath("$.brushwork").value("披麻皴"))
                .andExpect(jsonPath("$.inkMethod").value("积墨"))
                .andExpect(jsonPath("$.paintingMaterial").value("绢本"))
                .andExpect(jsonPath("$.pigment").value("墨"))
                .andExpect(jsonPath("$.seal").value("藏印"))
                .andExpect(jsonPath("$.culturalSymbol").value("松石"))
                .andExpect(jsonPath("$.generatedText").value("官方知识文本"))
                .andExpect(jsonPath("$.musicSceneDescription").value("幽远笛声"))
                .andExpect(jsonPath("$.collectionPlatform").value("官方平台"))
                .andExpect(jsonPath("$.imageAvailable").value(false))
                .andExpect(jsonPath("$.visibleInGallery").value(false))
                .andExpect(jsonPath("$.image").value(nullValue()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.sourceKey").doesNotExist())
                .andExpect(jsonPath("$.storageKey").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(ROOT.toString()))));

        mockMvc.perform(get("/api/v1/paintings/{id}", inactive.getPublicId())
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAINTING_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/paintings/not-a-uuid")
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAINTING_ID"));
    }

    @Test
    void favoritesAreIdempotentOwnedAndIncludeActiveHiddenMissingImageRows() throws Exception {
        User owner = saveUser("favorite-owner");
        User other = saveUser("favorite-other");
        Painting hiddenMissing = savePainting(
                "painting-dataset:favorite-hidden.jpg", false, false, "ACTIVE", false);
        int legacyLogsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM generation_logs", Integer.class);

        mockMvc.perform(put("/api/v1/paintings/{id}/favorite", hiddenMissing.getPublicId())
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isNoContent());
        mockMvc.perform(put("/api/v1/paintings/{id}/favorite", hiddenMissing.getPublicId())
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isNoContent());
        assertThat(favorites.count()).isEqualTo(1);

        mockMvc.perform(get("/api/v1/me/favorites/paintings")
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].paintingId").value(hiddenMissing.getPublicId()))
                .andExpect(jsonPath("$.items[0].imageAvailable").value(false))
                .andExpect(jsonPath("$.items[0].favorited").value(true));
        mockMvc.perform(get("/api/v1/me/favorites/paintings")
                        .with(user(other.getUsername()).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(delete("/api/v1/paintings/{id}/favorite", hiddenMissing.getPublicId())
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/paintings/{id}/favorite", hiddenMissing.getPublicId())
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isNoContent());
        assertThat(favorites.count()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM generation_logs", Integer.class))
                .isEqualTo(legacyLogsBefore);
    }

    @Test
    void dailyIsDeterministicEligibleSourceOrderedAndQueryErrorsAreStable() throws Exception {
        List<Painting> eligible = List.of(
                savePainting("painting-dataset:c.jpg", true, true, "ACTIVE", true),
                savePainting("painting-dataset:a.jpg", true, true, "ACTIVE", true),
                savePainting("painting-dataset:b.jpg", true, true, "ACTIVE", true))
                .stream()
                .sorted(Comparator.comparing(Painting::getSourceKey))
                .toList();
        int index = Math.toIntExact(Math.floorMod(
                LocalDate.now(DAILY_ZONE).toEpochDay(), eligible.size()));
        String expectedId = eligible.get(index).getPublicId();

        mockMvc.perform(get("/api/v1/paintings/daily"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paintingId").value(expectedId));
        mockMvc.perform(get("/api/v1/paintings/daily"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paintingId").value(expectedId));

        mockMvc.perform(get("/api/v1/paintings").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE_SIZE"));
        mockMvc.perform(get("/api/v1/paintings").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAINTING_QUERY"));
        mockMvc.perform(get("/api/v1/paintings").param("keyword", "x".repeat(201)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAINTING_QUERY"));
        mockMvc.perform(get("/api/v1/paintings").param("sort", "sourceKey"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SORT"));
        mockMvc.perform(get("/api/v1/paintings").param("direction", "sideways"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SORT"));
    }

    @Test
    void malformedPagingDetailAndFavoriteRequestsUseStableV1Errors() throws Exception {
        User owner = saveUser("error-owner");
        Painting inactive = savePainting(
                "painting-dataset:error-inactive.jpg", false, false, "INACTIVE", false);
        String unknownId = "00000000-0000-0000-0000-000000000999";

        mockMvc.perform(get("/api/v1/paintings").param("page", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(get("/api/v1/paintings").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE_SIZE"));
        mockMvc.perform(get("/api/v1/paintings").param("category", "x".repeat(513)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAINTING_QUERY"));
        mockMvc.perform(get("/api/v1/me/favorites/paintings")
                        .param("page", "-1")
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAINTING_QUERY"));
        mockMvc.perform(get("/api/v1/me/favorites/paintings")
                        .param("size", "101")
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE_SIZE"));

        mockMvc.perform(get("/api/v1/paintings/{id}", unknownId)
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAINTING_NOT_FOUND"));
        mockMvc.perform(put("/api/v1/paintings/not-a-uuid/favorite")
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAINTING_ID"));
        mockMvc.perform(put("/api/v1/paintings/{id}/favorite", unknownId)
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAINTING_NOT_FOUND"));
        mockMvc.perform(delete("/api/v1/paintings/{id}/favorite", inactive.getPublicId())
                        .with(user(owner.getUsername()).roles("USER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAINTING_NOT_FOUND"));
    }

    private void assertFilterReturnsOnly(String parameter, String value, String expectedId)
            throws Exception {
        mockMvc.perform(get("/api/v1/paintings").param(parameter, value))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].paintingId").value(expectedId));
    }

    private void assertPaintingOrder(
            String sort,
            String direction,
            List<Painting> expectedOrder) throws Exception {
        var result = mockMvc.perform(get("/api/v1/paintings")
                        .param("sort", sort)
                        .param("direction", direction))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(expectedOrder.size()))
                .andExpect(jsonPath("$.items", hasSize(expectedOrder.size())));
        for (int index = 0; index < expectedOrder.size(); index++) {
            result.andExpect(jsonPath("$.items[" + index + "].paintingId")
                    .value(expectedOrder.get(index).getPublicId()));
        }
    }

    private List<Painting> ordered(List<Painting> paintingsToOrder, Comparator<Painting> comparator) {
        return paintingsToOrder.stream().sorted(comparator).toList();
    }

    private User saveUser(String label) {
        int id = IDS.incrementAndGet();
        return users.saveAndFlush(User.builder()
                .username(label + "-" + id)
                .password("round5-test-only-password-hash")
                .fullName("Round 5 Test User")
                .email(label + "-" + id + "@example.test")
                .role("ROLE_USER")
                .build());
    }

    private Painting savePainting(
            String sourceKey,
            boolean imageAvailable,
            boolean visible,
            String status,
            boolean withPublicImage) {
        MediaAsset image = withPublicImage ? saveImage() : null;
        return paintings.saveAndFlush(Painting.builder()
                .sourceKey(sourceKey)
                .sourceSequence("7")
                .imageStorageName("official-image")
                .title("山水图")
                .authorName("测试画家")
                .authorBirthYear("1600")
                .authorBirthPlace("江南")
                .authorSchool("正统派")
                .creationYear("1650")
                .creationDynastyRaw("清朝")
                .creationDynastyNormalized("清代")
                .actualSize("100x50cm")
                .collectionInstitution("测试博物馆")
                .category("中国画")
                .subject("山水")
                .paintingSchool("山水流派")
                .style("雄浑")
                .color("水墨")
                .composition("高远")
                .artisticConception("清远")
                .brushwork("披麻皴")
                .inkMethod("积墨")
                .paintingMaterial("绢本")
                .pigment("墨")
                .seal("藏印")
                .culturalSymbol("松石")
                .generatedText("官方知识文本")
                .musicSceneDescription("幽远笛声")
                .collectionPlatform("官方平台")
                .imageAsset(image)
                .imageAvailable(imageAvailable)
                .visibleInGallery(visible)
                .status(status)
                .build());
    }

    private MediaAsset saveImage() {
        String id = UUID.randomUUID().toString();
        return mediaAssets.saveAndFlush(MediaAsset.builder()
                .publicId(id)
                .storageKey("catalog/" + id + ".jpg")
                .originalFilename("official.jpg")
                .mimeType(MediaType.IMAGE_JPEG_VALUE)
                .fileSize(3L)
                .sha256("a".repeat(64))
                .width(1)
                .height(1)
                .assetType(MediaAssetValues.AssetType.IMAGE)
                .semanticType(MediaAssetValues.SemanticType.PAINTING)
                .sourceType(MediaAssetValues.SourceType.CATALOG_REFERENCE)
                .visibility(MediaAssetValues.Visibility.PUBLIC)
                .status(MediaAssetValues.Status.ACTIVE)
                .build());
    }
}
