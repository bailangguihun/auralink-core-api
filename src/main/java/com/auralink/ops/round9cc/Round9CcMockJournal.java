package com.auralink.ops.round9cc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Append-only, restart-durable counter for the isolated ROUND 9C-C Mock. */
public final class Round9CcMockJournal {

    public enum Event {
        ENTRY,
        RETURN,
        CLOSE
    }

    private final Path file;
    private final String scenario;
    private final String role;
    private final AtomicLong nextSequence;

    public Round9CcMockJournal(Round9CcFixture fixture, String scenario, String role) {
        Round9CcFixture.validateLabel(scenario);
        Round9CcFixture.validateInstance(role);
        this.file = fixture.journalFile(role);
        this.scenario = scenario;
        this.role = role;
        ensurePrivateJournal(file);
        this.nextSequence = new AtomicLong(read(file).size());
    }

    private static void ensurePrivateJournal(Path file) {
        try {
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createFile(file);
                } catch (java.nio.file.FileAlreadyExistsException ignored) {
                    // Another same-role harness can only leave a regular private journal.
                }
            }
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("journal file invalid");
            }
            Round9CcFixture.setPrivateFile(file);
        } catch (IOException exception) {
            throw new IllegalStateException("ROUND 9C-C Mock journal could not be recorded");
        }
    }

    public void entry(String step) {
        append(step, Event.ENTRY);
    }

    public void returned(String step) {
        append(step, Event.RETURN);
    }

    public void closed(String step) {
        append(step, Event.CLOSE);
    }

    public synchronized void append(String step, Event event) {
        Round9CcFixture.validateLabel(step);
        if (event == null) {
            throw new IllegalArgumentException("ROUND 9C-C journal event is invalid");
        }
        String line = nextSequence.incrementAndGet() + "|" + scenario + "|" + role + "|" + step + "|"
                + event.name() + "\n";
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            if (Files.isSymbolicLink(file)) {
                throw new IOException("journal symlink");
            }
            Round9CcFixture.setPrivateFile(file);
            try (FileLock ignored = channel.tryLock()) {
                if (ignored == null) {
                    throw new IOException("journal lock unavailable");
                }
                channel.write(ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8)));
                channel.force(true);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("ROUND 9C-C Mock journal could not be recorded");
        }
    }

    public static List<Record> read(Path file) {
        if (file == null || !Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("ROUND 9C-C Mock journal is invalid");
            }
            List<Record> records = new ArrayList<>();
            long expected = 1L;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] values = line.split("\\|", -1);
                if (values.length != 5) {
                    throw new IllegalArgumentException("ROUND 9C-C Mock journal is invalid");
                }
                long sequence = Long.parseLong(values[0]);
                Round9CcFixture.validateLabel(values[1]);
                Round9CcFixture.validateInstance(values[2]);
                Round9CcFixture.validateLabel(values[3]);
                Event event = Event.valueOf(values[4]);
                if (sequence != expected++) {
                    throw new IllegalArgumentException("ROUND 9C-C Mock journal is invalid");
                }
                records.add(new Record(sequence, values[1], values[2], values[3], event));
            }
            return List.copyOf(records);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("ROUND 9C-C Mock journal is invalid");
        }
    }

    Path file() {
        return file;
    }

    public record Record(long sequence, String scenario, String role, String step, Event event) {
    }
}
