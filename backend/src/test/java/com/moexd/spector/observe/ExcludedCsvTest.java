package com.moexd.spector.observe;

import com.moexd.spector.velocity.VelocityBridge;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 跨服选人排除列表与 CSV 解析的纯逻辑测试 */
class ExcludedCsvTest {

    @Test
    void composeExcludedCsvMergesUsedAndObservers() {
        UUID used = UUID.randomUUID();
        UUID observerA = UUID.randomUUID();
        UUID observerB = UUID.randomUUID();

        String csv = ObservationManager.composeExcludedCsv(used.toString(), Set.of(observerA, observerB));

        List<UUID> parsed = VelocityBridge.parseCsv(csv);
        assertEquals(3, parsed.size());
        assertTrue(parsed.contains(used));
        assertTrue(parsed.contains(observerA));
        assertTrue(parsed.contains(observerB));
    }

    @Test
    void composeExcludedCsvHandlesEmptyInputs() {
        assertEquals("", ObservationManager.composeExcludedCsv(null, Set.of()));
        assertEquals("", ObservationManager.composeExcludedCsv("", Set.of()));

        UUID observer = UUID.randomUUID();
        String csv = ObservationManager.composeExcludedCsv(null, Set.of(observer));
        assertEquals(observer.toString(), csv);
    }

    @Test
    void parseCsvIgnoresBlankEntries() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertEquals(List.of(a, b), VelocityBridge.parseCsv(a + ",," + b + ","));
        assertEquals(List.of(), VelocityBridge.parseCsv(""));
    }
}
