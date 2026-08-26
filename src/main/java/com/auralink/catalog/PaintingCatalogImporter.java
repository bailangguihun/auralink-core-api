package com.auralink.catalog;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.auralink.config.properties.PaintingProperties;
import com.auralink.entity.CatalogImportRun;
import com.auralink.entity.MediaAsset;
import com.auralink.entity.Painting;
import com.auralink.repository.CatalogImportRunRepository;
import com.auralink.repository.PaintingRepository;
import com.auralink.service.media.MediaAssetService;

import lombok.RequiredArgsConstructor;

/**
 * Restartable, idempotent official-catalog synchronization.
 *
 * <p>Every batch commits independently. A later failure leaves completed
 * batches valid; rerunning the same snapshot upserts them by stable source key.
 * Rows absent from a newer source are deliberately never deleted.</p>
 */
@Service
@RequiredArgsConstructor
public class PaintingCatalogImporter {

    private static final String PAINTING_STATUS_ACTIVE = "ACTIVE";

    private final PaintingProperties properties;
    private final CatalogSourceSnapshotFactory snapshotFactory;
    private final DynastyNormalizer dynastyNormalizer;
    private final PaintingRepository paintingRepository;
    private final CatalogImportRunRepository importRunRepository;
    private final MediaAssetService mediaAssetService;
    private final PlatformTransactionManager transactionManager;

    public CatalogImportResult importCatalog() {
        try {
            Path csvPath = Path.of(properties.getMetadataCsvPath()).toAbsolutePath().normalize();
            Path pictureDirectory = Path.of(properties.getPictureDir()).toAbsolutePath().normalize();
            return importCatalog(snapshotFactory.create(csvPath, pictureDirectory));
        } catch (InvalidPathException exception) {
            throw new CatalogImportException("Catalog import configuration is invalid", exception);
        }
    }

    public synchronized CatalogImportResult importCatalog(CatalogSourceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "Catalog source snapshot is required");

        CatalogImportRun previous = importRunRepository
                .findTopBySourceSha256AndStatusOrderByFinishedAtDesc(
                        snapshot.fingerprint(), CatalogImportStatus.SUCCESS)
                .orElse(null);
        if (previous != null) {
            CatalogImportRun skipped = inNewTransaction(() -> saveSkippedRun(snapshot));
            return CatalogImportResult.from(skipped);
        }

        CatalogImportRun running = inNewTransaction(() -> saveRunningRun(snapshot));
        ImportCounters counters = new ImportCounters();
        try {
            int batchSize = properties.getImportBatchSize();
            List<CatalogSourceRow> rows = snapshot.rows();
            for (int start = 0; start < rows.size(); start += batchSize) {
                int end = Math.min(start + batchSize, rows.size());
                List<CatalogSourceRow> batch = rows.subList(start, end);
                BatchCounts batchCounts = inNewTransaction(() -> importBatch(batch));
                counters.add(batchCounts);
                inNewTransaction(() -> updateRunProgress(running.getId(), snapshot, counters));
            }

            CatalogImportRun completed = inNewTransaction(
                    () -> finishRun(running.getId(), snapshot, counters, CatalogImportStatus.SUCCESS, null));
            return CatalogImportResult.from(completed);
        } catch (RuntimeException exception) {
            try {
                inNewTransaction(() -> finishRun(
                        running.getId(),
                        snapshot,
                        counters,
                        CatalogImportStatus.FAILED,
                        safeFailureMessage(exception)));
            } catch (RuntimeException auditFailure) {
                exception.addSuppressed(auditFailure);
            }
            throw new CatalogImportException("Official painting catalog import failed", exception);
        }
    }

    private BatchCounts importBatch(List<CatalogSourceRow> rows) {
        int inserted = 0;
        int updated = 0;
        int unchanged = 0;

        for (CatalogSourceRow sourceRow : rows) {
            MediaAsset imageAsset = sourceRow.imageAvailable()
                    ? mediaAssetService.registerCatalogReference(sourceRow.imageFileName())
                    : null;
            OfficialPaintingRecord record = sourceRow.record();
            Painting existing = paintingRepository.findBySourceKey(record.sourceKey()).orElse(null);
            if (existing == null) {
                paintingRepository.save(newPainting(record, imageAsset));
                inserted++;
            } else if (matches(existing, record, imageAsset)) {
                unchanged++;
            } else {
                apply(existing, record, imageAsset);
                paintingRepository.save(existing);
                updated++;
            }
        }
        paintingRepository.flush();
        return new BatchCounts(inserted, updated, unchanged);
    }

    private Painting newPainting(OfficialPaintingRecord record, MediaAsset imageAsset) {
        Painting painting = Painting.builder().build();
        apply(painting, record, imageAsset);
        return painting;
    }

    private void apply(Painting painting, OfficialPaintingRecord record, MediaAsset imageAsset) {
        painting.setSourceKey(record.sourceKey());
        painting.setSourceSequence(record.sourceSequence());
        painting.setImageStorageName(record.imageStorageName());
        painting.setTitle(record.title());
        painting.setAuthorName(record.authorName());
        painting.setAuthorBirthYear(record.authorBirthYear());
        painting.setAuthorBirthPlace(record.authorBirthPlace());
        painting.setAuthorSchool(record.authorSchool());
        painting.setCreationYear(record.creationYear());
        painting.setCreationDynastyRaw(record.creationDynastyRaw());
        painting.setCreationDynastyNormalized(normalizedDynasty(record));
        painting.setActualSize(record.actualSize());
        painting.setCollectionInstitution(record.collectionInstitution());
        painting.setCategory(record.category());
        painting.setSubject(record.subject());
        painting.setPaintingSchool(record.paintingSchool());
        painting.setStyle(record.style());
        painting.setColor(record.color());
        painting.setComposition(record.composition());
        painting.setArtisticConception(record.artisticConception());
        painting.setBrushwork(record.brushwork());
        painting.setInkMethod(record.inkMethod());
        painting.setPaintingMaterial(record.paintingMaterial());
        painting.setPigment(record.pigment());
        painting.setSeal(record.seal());
        painting.setCulturalSymbol(record.culturalSymbol());
        painting.setGeneratedText(record.generatedText());
        painting.setMusicSceneDescription(record.musicSceneDescription());
        painting.setCollectionPlatform(record.collectionPlatform());
        painting.setImageAsset(imageAsset);
        painting.setImageAvailable(imageAsset != null);
        painting.setVisibleInGallery(imageAsset != null);
        painting.setStatus(PAINTING_STATUS_ACTIVE);
    }

    private boolean matches(Painting painting, OfficialPaintingRecord record, MediaAsset imageAsset) {
        return Objects.equals(painting.getSourceKey(), record.sourceKey())
                && Objects.equals(painting.getSourceSequence(), record.sourceSequence())
                && Objects.equals(painting.getImageStorageName(), record.imageStorageName())
                && Objects.equals(painting.getTitle(), record.title())
                && Objects.equals(painting.getAuthorName(), record.authorName())
                && Objects.equals(painting.getAuthorBirthYear(), record.authorBirthYear())
                && Objects.equals(painting.getAuthorBirthPlace(), record.authorBirthPlace())
                && Objects.equals(painting.getAuthorSchool(), record.authorSchool())
                && Objects.equals(painting.getCreationYear(), record.creationYear())
                && Objects.equals(painting.getCreationDynastyRaw(), record.creationDynastyRaw())
                && Objects.equals(
                        painting.getCreationDynastyNormalized(),
                        normalizedDynasty(record))
                && Objects.equals(painting.getActualSize(), record.actualSize())
                && Objects.equals(painting.getCollectionInstitution(), record.collectionInstitution())
                && Objects.equals(painting.getCategory(), record.category())
                && Objects.equals(painting.getSubject(), record.subject())
                && Objects.equals(painting.getPaintingSchool(), record.paintingSchool())
                && Objects.equals(painting.getStyle(), record.style())
                && Objects.equals(painting.getColor(), record.color())
                && Objects.equals(painting.getComposition(), record.composition())
                && Objects.equals(painting.getArtisticConception(), record.artisticConception())
                && Objects.equals(painting.getBrushwork(), record.brushwork())
                && Objects.equals(painting.getInkMethod(), record.inkMethod())
                && Objects.equals(painting.getPaintingMaterial(), record.paintingMaterial())
                && Objects.equals(painting.getPigment(), record.pigment())
                && Objects.equals(painting.getSeal(), record.seal())
                && Objects.equals(painting.getCulturalSymbol(), record.culturalSymbol())
                && Objects.equals(painting.getGeneratedText(), record.generatedText())
                && Objects.equals(painting.getMusicSceneDescription(), record.musicSceneDescription())
                && Objects.equals(painting.getCollectionPlatform(), record.collectionPlatform())
                && Objects.equals(entityId(painting.getImageAsset()), entityId(imageAsset))
                && painting.isImageAvailable() == (imageAsset != null)
                && painting.isVisibleInGallery() == (imageAsset != null)
                && Objects.equals(painting.getStatus(), PAINTING_STATUS_ACTIVE);
    }

    private Long entityId(MediaAsset asset) {
        return asset == null ? null : asset.getId();
    }

    private String normalizedDynasty(OfficialPaintingRecord record) {
        return dynastyNormalizer.normalize(record.creationDynastyRaw());
    }

    private CatalogImportRun saveRunningRun(CatalogSourceSnapshot snapshot) {
        CatalogImportRun run = baseRun(snapshot, CatalogImportStatus.RUNNING);
        return importRunRepository.saveAndFlush(run);
    }

    private CatalogImportRun saveSkippedRun(CatalogSourceSnapshot snapshot) {
        CatalogImportRun run = baseRun(snapshot, CatalogImportStatus.SKIPPED);
        run.setUnchangedRows(snapshot.totalRows());
        run.setFinishedAt(LocalDateTime.now());
        return importRunRepository.saveAndFlush(run);
    }

    private CatalogImportRun baseRun(CatalogSourceSnapshot snapshot, String status) {
        return CatalogImportRun.builder()
                .sourceName(snapshot.sourceName())
                .sourceSha256(snapshot.fingerprint())
                .totalRows(snapshot.totalRows())
                .matchedImages(snapshot.matchedImages())
                .missingImages(snapshot.missingImages())
                .orphanImages(snapshot.orphanImages())
                .status(status)
                .startedAt(LocalDateTime.now())
                .build();
    }

    private CatalogImportRun updateRunProgress(
            Long runId,
            CatalogSourceSnapshot snapshot,
            ImportCounters counters) {
        CatalogImportRun run = requireRun(runId);
        copyCounts(run, snapshot, counters);
        return importRunRepository.saveAndFlush(run);
    }

    private CatalogImportRun finishRun(
            Long runId,
            CatalogSourceSnapshot snapshot,
            ImportCounters counters,
            String status,
            String errorMessage) {
        CatalogImportRun run = requireRun(runId);
        copyCounts(run, snapshot, counters);
        run.setStatus(status);
        run.setFinishedAt(LocalDateTime.now());
        run.setErrorMessage(errorMessage);
        return importRunRepository.saveAndFlush(run);
    }

    private void copyCounts(
            CatalogImportRun run,
            CatalogSourceSnapshot snapshot,
            ImportCounters counters) {
        run.setTotalRows(snapshot.totalRows());
        run.setInsertedRows(counters.inserted);
        run.setUpdatedRows(counters.updated);
        run.setUnchangedRows(counters.unchanged);
        run.setMatchedImages(snapshot.matchedImages());
        run.setMissingImages(snapshot.missingImages());
        run.setOrphanImages(snapshot.orphanImages());
    }

    private CatalogImportRun requireRun(Long runId) {
        return importRunRepository.findById(runId)
                .orElseThrow(() -> new IllegalStateException("Catalog import audit row is unavailable"));
    }

    private String safeFailureMessage(RuntimeException exception) {
        return "Catalog synchronization failed (" + exception.getClass().getSimpleName() + ")";
    }

    private <T> T inNewTransaction(Supplier<T> operation) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        T result = transaction.execute(status -> operation.get());
        if (result == null) {
            throw new IllegalStateException("Catalog transaction returned no result");
        }
        return result;
    }

    private record BatchCounts(int inserted, int updated, int unchanged) {
    }

    private static final class ImportCounters {
        private int inserted;
        private int updated;
        private int unchanged;

        private void add(BatchCounts counts) {
            inserted += counts.inserted();
            updated += counts.updated();
            unchanged += counts.unchanged();
        }
    }
}
