package com.auralink.ops.round51;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.BaselineResult;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.api.output.ValidateResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.auralink.catalog.CatalogImportResult;
import com.auralink.catalog.CatalogSourceSnapshot;
import com.auralink.catalog.CatalogSourceSnapshotFactory;
import com.auralink.catalog.PaintingCatalogImporter;
import com.auralink.config.properties.PaintingProperties;
import com.auralink.ops.round51.Round51DatabaseVerifier.Expectations;

/**
 * Controlled one-time database activation. It is deliberately callable only
 * from the local operations CLI; no controller or scheduled path references it.
 */
public final class Round51ActivationCoordinator {

    private static final String MIGRATION_LOCATION = "classpath:db/migration";
    /**
     * Round 5.1 is the historical catalog activation boundary.  Later product
     * migrations are intentionally not part of this controlled activation.
     */
    private static final MigrationVersion ROUND51_TARGET_VERSION = MigrationVersion.fromVersion("2");

    private final DataSource dataSource;
    private final PaintingCatalogImporter importer;
    private final CatalogSourceSnapshotFactory snapshotFactory;
    private final PaintingProperties paintingProperties;
    private final Round51DatabaseVerifier verifier;
    private final Expectations expected;
    private final ActivationFaultInjector faultInjector;

    @Autowired
    public Round51ActivationCoordinator(
            DataSource dataSource,
            PaintingCatalogImporter importer,
            CatalogSourceSnapshotFactory snapshotFactory,
            PaintingProperties paintingProperties) {
        this(
                dataSource,
                importer,
                snapshotFactory,
                paintingProperties,
                Expectations.production(),
                checkpoint -> { });
    }

    Round51ActivationCoordinator(
            DataSource dataSource,
            PaintingCatalogImporter importer,
            CatalogSourceSnapshotFactory snapshotFactory,
            PaintingProperties paintingProperties,
            Expectations expected,
            ActivationFaultInjector faultInjector) {
        this.dataSource = dataSource;
        this.importer = importer;
        this.snapshotFactory = snapshotFactory;
        this.paintingProperties = paintingProperties;
        this.verifier = new Round51DatabaseVerifier(new JdbcTemplate(dataSource));
        this.expected = expected;
        this.faultInjector = faultInjector;
    }

    public Round51ActivationResult activate() {
        CatalogSourceSnapshot snapshot = snapshotFactory.create(
                java.nio.file.Path.of(paintingProperties.getMetadataCsvPath()),
                java.nio.file.Path.of(paintingProperties.getPictureDir()));
        verifier.verifySnapshot(snapshot, expected);

        Round51ActivationState state = verifier.classify(snapshot, expected);
        if (state == Round51ActivationState.ALREADY_ACTIVATED_HEALTHY) {
            Flyway flyway = configuredFlyway();
            ValidateResult validation = flyway.validateWithResult();
            require(validation.validationSuccessful, "FLYWAY_VALIDATE_FAILED");
            require(flyway.info().pending().length == 0, "FLYWAY_PENDING_MIGRATIONS_FOUND");
            return result(state, snapshot);
        }
        if (state != Round51ActivationState.INHERITED_READY) {
            throw new Round51ActivationException(
                    "PARTIALLY_ACTIVATED_STATE_REFUSED",
                    "Database state is neither the exact inherited state nor a healthy activated state");
        }

        String legacyDigest = verifier.legacyDigest();
        Flyway flyway = configuredFlyway();
        try {
            BaselineResult baseline = flyway.baseline();
            require(baseline.successfullyBaselined && "1".equals(baseline.baselineVersion),
                    "FLYWAY_BASELINE_FAILED");
            verifier.verifyBaseline();
            faultInjector.after(ActivationCheckpoint.AFTER_BASELINE);

            MigrateResult firstMigrate = flyway.migrate();
            require(firstMigrate.success && firstMigrate.migrationsExecuted == 1, "FLYWAY_V2_MIGRATION_FAILED");
            ValidateResult validation = flyway.validateWithResult();
            require(validation.validationSuccessful, "FLYWAY_VALIDATE_FAILED");
            MigrateResult repeatMigrate = flyway.migrate();
            require(repeatMigrate.success && repeatMigrate.migrationsExecuted == 0, "FLYWAY_REPEAT_MIGRATE_FAILED");
            verifier.verifyMigratedEmpty(expected);
            verifier.verifyLegacyDigest(legacyDigest);
            faultInjector.after(ActivationCheckpoint.AFTER_MIGRATE);

            System.out.println("CATALOG_IMPORT_STARTED_LONG_RUNNING");
            faultInjector.after(ActivationCheckpoint.BEFORE_CATALOG_IMPORT);
            CatalogImportResult firstImport = importer.importCatalog(snapshot);
            verifier.verifyFirstImport(firstImport, snapshot, expected);
            String identityDigest = verifier.identityDigest();

            // Rebuild the source snapshot after the long first import. Reusing the
            // original object could incorrectly declare a changed CSV/image corpus
            // unchanged after an hour-long activation.
            CatalogSourceSnapshot reimportSnapshot = snapshotFactory.create(
                    java.nio.file.Path.of(paintingProperties.getMetadataCsvPath()),
                    java.nio.file.Path.of(paintingProperties.getPictureDir()));
            verifier.verifySnapshot(reimportSnapshot, expected);
            require(snapshot.fingerprint().equals(reimportSnapshot.fingerprint()),
                    "CATALOG_SOURCE_CHANGED_DURING_ACTIVATION");
            CatalogImportResult secondImport = importer.importCatalog(reimportSnapshot);
            verifier.verifySecondImport(secondImport, reimportSnapshot, expected, identityDigest);
            verifier.verifyLegacyDigest(legacyDigest);
            return result(Round51ActivationState.ACTIVATED_NOW, reimportSnapshot);
        } catch (Round51ActivationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new Round51ActivationException(
                    "ACTIVATION_PHASE_FAILED",
                    "Controlled activation phase failed",
                    exception);
        }
    }

    private Flyway configuredFlyway() {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(MIGRATION_LOCATION)
                .baselineOnMigrate(false)
                .baselineVersion(MigrationVersion.fromVersion("1"))
                .target(ROUND51_TARGET_VERSION)
                .baselineDescription("Inherited legacy schema baseline")
                .validateOnMigrate(true)
                .validateMigrationNaming(true)
                .cleanDisabled(true)
                .initSql("PRAGMA foreign_keys=ON")
                .load();
    }

    private Round51ActivationResult result(Round51ActivationState state, CatalogSourceSnapshot snapshot) {
        return new Round51ActivationResult(
                state,
                snapshot.fingerprint(),
                expected.paintings(),
                expected.catalogMediaAssets());
    }

    private void require(boolean condition, String code) {
        if (!condition) {
            throw new Round51ActivationException(code, "Flyway activation phase failed");
        }
    }

    enum ActivationCheckpoint {
        AFTER_BASELINE,
        AFTER_MIGRATE,
        BEFORE_CATALOG_IMPORT
    }

    @FunctionalInterface
    interface ActivationFaultInjector {
        void after(ActivationCheckpoint checkpoint);
    }
}
