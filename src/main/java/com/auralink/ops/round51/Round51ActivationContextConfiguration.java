package com.auralink.ops.round51;

import java.io.IOException;
import java.util.Set;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import com.auralink.catalog.CatalogSourceSnapshotFactory;
import com.auralink.catalog.DynastyNormalizer;
import com.auralink.catalog.OfficialPaintingCsvReader;
import com.auralink.catalog.PaintingCatalogImporter;
import com.auralink.catalog.PaintingImageMatcher;
import com.auralink.config.properties.MediaAssetProperties;
import com.auralink.config.properties.PaintingProperties;
import com.auralink.entity.CatalogImportRun;
import com.auralink.entity.MediaAsset;
import com.auralink.entity.Painting;
import com.auralink.entity.User;
import com.auralink.repository.CatalogImportRunRepository;
import com.auralink.repository.MediaAssetRepository;
import com.auralink.repository.PaintingRepository;
import com.auralink.repository.UserRepository;
import com.auralink.security.access.MediaAssetAccessPolicy;
import com.auralink.service.CurrentUserService;
import com.auralink.service.media.ImageContentValidator;
import com.auralink.service.media.MediaAssetService;
import com.auralink.service.media.MediaAssetStorageResolver;
import com.auralink.service.media.MediaAssetStorageService;

/**
 * Minimal Spring source used only by the server-local activation CLI.
 *
 * <p>This class deliberately has no {@code @Configuration}, {@code @Component},
 * or other component stereotype. {@link Round51ActivationCommand} registers it
 * explicitly as its primary source, while the normal {@code Application}
 * component scan cannot discover it. The explicit imports are the complete
 * activation dependency graph; MVC, security, controllers, servlet filters,
 * provider clients, and application runners are excluded by construction.</p>
 */
@ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        TransactionAutoConfiguration.class
})
@EnableTransactionManagement
@EnableConfigurationProperties({PaintingProperties.class, MediaAssetProperties.class})
@EnableJpaRepositories(
        basePackages = "com.auralink.repository",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.CUSTOM,
                classes = Round51ActivationContextConfiguration.NonActivationRepositoryFilter.class))
@Import({
        OfficialPaintingCsvReader.class,
        PaintingImageMatcher.class,
        CatalogSourceSnapshotFactory.class,
        DynastyNormalizer.class,
        ImageContentValidator.class,
        MediaAssetStorageResolver.class,
        MediaAssetStorageService.class,
        CurrentUserService.class,
        MediaAssetAccessPolicy.class,
        MediaAssetService.class,
        PaintingCatalogImporter.class,
        Round51ActivationCoordinator.class
})
final class Round51ActivationContextConfiguration {

    private static final Set<String> ACTIVATION_REPOSITORIES = Set.of(
            UserRepository.class.getName(),
            MediaAssetRepository.class.getName(),
            PaintingRepository.class.getName(),
            CatalogImportRunRepository.class.getName());

    private Round51ActivationContextConfiguration() {
    }

    @Bean
    static PersistenceManagedTypes round51PersistenceManagedTypes() {
        return PersistenceManagedTypes.of(
                User.class.getName(),
                MediaAsset.class.getName(),
                Painting.class.getName(),
                CatalogImportRun.class.getName());
    }

    /** Keeps repository discovery limited to the four activation repositories. */
    public static final class NonActivationRepositoryFilter implements TypeFilter {
        @Override
        public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory)
                throws IOException {
            return !ACTIVATION_REPOSITORIES.contains(metadataReader.getClassMetadata().getClassName());
        }
    }
}
