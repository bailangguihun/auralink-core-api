package com.auralink.ops.round9b2;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.auralink.creation.CreationStatus;
import com.auralink.entity.Creation;
import com.auralink.entity.User;
import com.auralink.repository.CreationRepository;
import com.auralink.repository.UserRepository;

/** SQLite V1--V4 proof for stale scanning and token/lease conditional fencing. */
class CreationRecoveryRepositoryIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void staleCandidateScanIsBoundedOrderedAndFencingRejectsStaleWriters() throws Exception {
        Path root = temporaryDirectory.resolve("recovery-repository");
        Files.createDirectory(root);
        assertThat(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(Files.isSymbolicLink(root)).isFalse();
        try (ConfigurableApplicationContext context = Round9B2PackagedMockHarness.startContext(root, new String[0])) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            CreationRepository creations = context.getBean(CreationRepository.class);
            UserRepository users = context.getBean(UserRepository.class);
            TransactionTemplate transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            User owner = users.saveAndFlush(User.builder()
                    .username("recovery-repository-owner")
                    .password("not-used")
                    .fullName("Recovery Repository")
                    .email("recovery-repository@example.invalid")
                    .build());
            LocalDateTime cutoff = LocalDateTime.now(Clock.systemUTC()).minusSeconds(90).withNano(0);

            Creation oldest = expired(creations, owner, cutoff.minusMinutes(3), "oldest");
            Creation next = expired(creations, owner, cutoff.minusMinutes(2), "next");
            expired(creations, owner, cutoff.minusMinutes(1), "third");
            expired(creations, owner, cutoff.plusSeconds(30), "inside-grace");
            Creation validLease = expired(creations, owner, cutoff.plusMinutes(5), "valid-lease");

            List<Creation> candidates = creations.findExpiredRecoveryCandidates(cutoff, 2);
            assertThat(candidates).extracting(Creation::getId).containsExactly(oldest.getId(), next.getId());
            assertThat(candidates).extracting(Creation::getId).doesNotContain(validLease.getId());
            assertThat(jdbc.queryForList(
                    "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank", String.class))
                    .containsExactly("1", "2", "3", "4");

            LocalDateTime observedLease = oldest.getLeaseExpiresAt();
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService fences = Executors.newFixedThreadPool(2);
            try {
                Future<Integer> firstFence = fences.submit(() -> fenceAfterStart(
                        transactions, creations, oldest, observedLease, cutoff, "recovery-fence-one", ready, start));
                Future<Integer> secondFence = fences.submit(() -> fenceAfterStart(
                        transactions, creations, oldest, observedLease, cutoff, "recovery-fence-two", ready, start));
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                assertThat(List.of(firstFence.get(5, TimeUnit.SECONDS), secondFence.get(5, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder(0, 1);
            } finally {
                fences.shutdownNow();
                assertThat(fences.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }

            int staleHeartbeat = transactions.execute(status -> creations.refreshLease(
                    oldest.getId(), "oldest", cutoff.plusMinutes(10), cutoff));
            assertThat(staleHeartbeat).isZero();
        }
    }

    private static Creation expired(
            CreationRepository creations, User owner, LocalDateTime leaseExpiresAt, String suffix) {
        return creations.saveAndFlush(Creation.builder()
                .user(owner)
                .workflowSnapshot("{}")
                .sourceModality("TEXT_DESCRIPTION")
                .status(CreationStatus.RUNNING.name())
                .createdAt(leaseExpiresAt.minusMinutes(1))
                .updatedAt(leaseExpiresAt.minusMinutes(1))
                .claimToken(suffix)
                .leaseExpiresAt(leaseExpiresAt)
                .retryVersion(0)
                .build());
    }

    private static int fenceAfterStart(
            TransactionTemplate transactions,
            CreationRepository creations,
            Creation creation,
            LocalDateTime observedLease,
            LocalDateTime cutoff,
            String recoveryToken,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("test recovery fence start timed out");
        }
        return transactions.execute(status -> creations.fenceExpiredClaim(
                creation.getId(), creation.getClaimToken(), observedLease, cutoff,
                recoveryToken, cutoff.plusMinutes(5), cutoff));
    }
}
