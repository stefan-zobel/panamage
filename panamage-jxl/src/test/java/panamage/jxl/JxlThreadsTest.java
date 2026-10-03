package panamage.jxl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JxlThreadsTest {

    @Test
    void factories() {
        assertEquals(JxlThreads.auto(), JxlThreads.auto());
        assertEquals(JxlThreads.none(), JxlThreads.none());
        assertEquals(JxlThreads.fixed(3), JxlThreads.fixed(3));
        assertEquals(JxlThreads.fixed(3).hashCode(), JxlThreads.fixed(3).hashCode());
        assertNotEquals(JxlThreads.auto(), JxlThreads.none());
        assertNotEquals(JxlThreads.fixed(1), JxlThreads.fixed(2));
        assertNotEquals(JxlThreads.none(), JxlThreads.fixed(1));
        assertEquals("JxlThreads[auto]", JxlThreads.auto().toString());
        assertEquals("JxlThreads[none]", JxlThreads.none().toString());
        assertEquals("JxlThreads[fixed=4]", JxlThreads.fixed(4).toString());
    }

    @Test
    void rejectsInvalidSettings() {
        assertThrows(IllegalArgumentException.class, () -> JxlThreads.fixed(0));
        assertThrows(IllegalArgumentException.class, () -> JxlThreads.fixed(-1));
        assertThrows(NullPointerException.class, () -> JxlDecodeOptions.defaults().withThreads(null));
        assertThrows(NullPointerException.class, () -> JxlEncodeOptions.defaults().withThreads(null));
    }

    @Test
    void optionsUseAutomaticThreadsByDefault() {
        assertEquals(JxlThreads.auto(), JxlDecodeOptions.defaults().threads());
        assertEquals(JxlThreads.auto(), JxlEncodeOptions.defaults().threads());
        assertEquals(JxlThreads.auto(), JxlEncodeOptions.ofLossless().threads());
        assertEquals(JxlThreads.none(), JxlEncodeOptions.ofQuality(90).withThreads(JxlThreads.none()).withEffort(3)
                .threads());
    }

    @Test
    void automaticRunnerFollowsTheImageSize() {
        try (ParallelRunner runner = ParallelRunner.create(JxlThreads.auto())) {
            assertEquals(0, runner.threads());
            runner.fitTo(64, 48);
            assertEquals(0, runner.threads());
            // Only the first size counts.
            runner.fitTo(4096, 4096);
            assertEquals(0, runner.threads());
        }
        try (ParallelRunner runner = ParallelRunner.create(JxlThreads.auto())) {
            runner.fitTo(4096, 4096);
            int processors = Runtime.getRuntime().availableProcessors();
            assertTrue(runner.threads() >= Math.min(processors, 2) && runner.threads() <= processors,
                    runner.threads() + " threads for " + processors + " processors");
        }
        try (ParallelRunner runner = ParallelRunner.create(JxlThreads.auto())) {
            runner.fitToUnknownSize();
            assertTrue(runner.threads() >= 1, runner.threads() + " threads");
        }
    }

    @Test
    void fixedRunnerIgnoresTheImageSize() {
        try (ParallelRunner runner = ParallelRunner.create(JxlThreads.fixed(3))) {
            assertEquals(3, runner.threads());
            runner.fitTo(64, 48);
            runner.fitToUnknownSize();
            assertEquals(3, runner.threads());
        }
        assertNull(ParallelRunner.create(JxlThreads.none()));
    }
}
