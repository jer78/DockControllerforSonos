package io.github.jer78.dockcontroller

import org.junit.Assert.assertEquals
import org.junit.Test

class TvShowNameTest {
    @Test
    fun readsShowBeforeNonBreakingHyphen() {
        // As the Apple TV reports it, with U+2011 NON-BREAKING HYPHEN.
        assertEquals(
            "The Traitors Canada",
            detectTvShowName("The Traitors Canada ‑ S1 E10 Trust Your Instincts", "", "Home Assistant")
        )
    }
}
