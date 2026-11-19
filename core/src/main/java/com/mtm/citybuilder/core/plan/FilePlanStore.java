package com.mtm.citybuilder.core.plan;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * A plan store in a directory, one file per key ({@code <key>.bin}), as in the world save's {@code citybuilder/plan}.
 * Files are written to a temporary name and moved into place, so a crash leaves either the old state or the new.
 */
public final class FilePlanStore implements PlanStore {
    private final Path root;

    public FilePlanStore(Path root) {
        this.root = root;
    }

    private Path file(String key) {
        return root.resolve(PlanStore.checkKey(key) + ".bin");
    }

    @Override
    public byte[] load(String key) {
        try {
            return Files.readAllBytes(file(key));
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read plan " + key, e);
        }
    }

    @Override
    public void save(String key, byte[] data) {
        Path target = file(key);
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
            Files.write(temporary, data);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write plan " + key, e);
        }
    }
}
