package com.auralink.ops.round9cc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;

import com.auralink.creation.CreationExecutionBoundary;
import com.auralink.creation.CreationExecutionBoundaryHook;

/**
 * Private, bounded barrier implementation used only by the dedicated C.2
 * packaged harness. Markers intentionally contain only the boundary name.
 */
public final class Round9CcBarrierExecutionBoundaryHook implements CreationExecutionBoundaryHook {

    private static final long PARK_NANOS = Duration.ofMillis(25).toNanos();
    private static final String ARTIFACT_STEP = "RESULT_ARTIFACT";

    private final Path control;
    private final Set<CreationExecutionBoundary> selected;
    private final long timeoutNanos;
    private final Round9CcMockJournal journal;

    public Round9CcBarrierExecutionBoundaryHook(
            Round9CcFixture fixture,
            String instance,
            Set<CreationExecutionBoundary> selected,
            Duration timeout,
            Round9CcMockJournal journal) {
        this.control = fixture.controlDirectory(instance);
        this.selected = selected == null || selected.isEmpty()
                ? Set.of() : Set.copyOf(EnumSet.copyOf(selected));
        if (timeout == null || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("ROUND 9C-C failpoint timeout is invalid");
        }
        this.timeoutNanos = timeout.toNanos();
        this.journal = Objects.requireNonNull(journal, "journal");
    }

    @Override
    public void reached(CreationExecutionBoundary boundary) {
        if (boundary == null || !selected.contains(boundary)) {
            return;
        }
        Path reached = marker(boundary, "reached");
        Path release = marker(boundary, "release");
        writeReached(reached, boundary);
        long deadline = System.nanoTime() + timeoutNanos;
        while (System.nanoTime() < deadline) {
            if (released(release)) {
                return;
            }
            LockSupport.parkNanos(PARK_NANOS);
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new Round9CcFailpointTimeoutException();
            }
        }
        throw new Round9CcFailpointTimeoutException();
    }

    @Override
    public void artifactCloseAttempted() {
        journal.closed(ARTIFACT_STEP);
    }

    private Path marker(CreationExecutionBoundary boundary, String suffix) {
        Path marker = control.resolve(boundary.name() + "." + suffix).normalize();
        if (!marker.getParent().equals(control)) {
            throw new IllegalStateException("ROUND 9C-C failpoint control is invalid");
        }
        return marker;
    }

    private void writeReached(Path reached, CreationExecutionBoundary boundary) {
        try {
            Files.writeString(reached, boundary.name() + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Round9CcFixture.setPrivateFile(reached);
        } catch (IOException exception) {
            throw new IllegalStateException("ROUND 9C-C failpoint marker could not be recorded");
        }
    }

    private boolean released(Path release) {
        try {
            if (!Files.exists(release, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            if (Files.isSymbolicLink(release) || !Files.isRegularFile(release, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("ROUND 9C-C failpoint release is invalid");
            }
            return true;
        } catch (SecurityException exception) {
            throw new IllegalStateException("ROUND 9C-C failpoint release is invalid");
        }
    }
}
