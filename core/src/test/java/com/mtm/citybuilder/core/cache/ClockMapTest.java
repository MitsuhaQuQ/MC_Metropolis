package com.mtm.citybuilder.core.cache;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ClockMapTest {
    @Test
    void keepsTheUsedAndEvictsTheOldest() {
        ClockMap<String> map = new ClockMap<>(2);
        map.computeIfAbsent(1, k -> "a");
        map.computeIfAbsent(2, k -> "b");
        // 1 used since added: its second chance; 2 goes
        assertEquals("a", map.get(1));
        map.computeIfAbsent(3, k -> "c");
        assertEquals("a", map.get(1));
        assertNull(map.get(2));
        assertEquals("c", map.get(3));
        map.remove(3, "c");
        assertNull(map.get(3));
    }

    @Test
    void makesEachValueOnceAcrossThreads() {
        ClockMap<Object> map = new ClockMap<>(1000);
        AtomicInteger made = new AtomicInteger();
        IntStream.range(0, 20000).parallel().forEach(i -> map.computeIfAbsent(i % 100, k -> {
            made.incrementAndGet();
            return new Object();
        }));
        assertEquals(100, made.get());
    }

    @Test
    void onceWorksOutEachKeyOnce() throws Exception {
        Once<Object> once = new Once<>();
        AtomicInteger made = new AtomicInteger();
        Object[] seen = new Object[8];
        Thread[] threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            int at = t;
            threads[t] = new Thread(() -> seen[at] = once.get(7, () -> {
                made.incrementAndGet();
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new Object();
            }));
            threads[t].start();
        }
        for (Thread t : threads) {
            t.join();
        }
        assertEquals(1, made.get());
        assertNotNull(seen[0]);
        for (Object o : seen) {
            assertSame(seen[0], o);
        }
    }
}
