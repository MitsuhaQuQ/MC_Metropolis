package com.mtm.citybuilder.core.plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SqlitePlanStoreTest {
    @Test
    void plansAreKeptAcrossOpeningsAndOldFilesAreReadIn(@TempDir Path dir) throws Exception {
        Path legacy = dir.resolve("plan");
        Files.createDirectories(legacy.resolve("metropolis"));
        Files.write(legacy.resolve("metropolis/3_-4.bin"), new byte[]{9, 8, 7});
        Path file = dir.resolve("citybuilder.db");
        try (SqlitePlanStore store = SqlitePlanStore.open(file, legacy)) {
            assertNotNull(store, "the driver loads in the tests");
            assertNull(store.load("town/1_2"));
            store.save("town/1_2", new byte[]{1, 2, 3});
            // A second save of a key keeps the first
            store.save("town/1_2", new byte[]{4});
            assertArrayEquals(new byte[]{1, 2, 3}, store.load("town/1_2"));
            assertArrayEquals(new byte[]{9, 8, 7}, store.load("metropolis/3_-4"));
        }
        Files.delete(legacy.resolve("metropolis/3_-4.bin"));
        try (SqlitePlanStore store = SqlitePlanStore.open(file, null)) {
            assertArrayEquals(new byte[]{1, 2, 3}, store.load("town/1_2"));
            assertArrayEquals(new byte[]{9, 8, 7}, store.load("metropolis/3_-4"), "copied in from the old file");
        }
    }
}
