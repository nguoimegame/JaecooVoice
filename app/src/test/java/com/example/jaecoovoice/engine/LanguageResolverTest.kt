package com.example.jaecoovoice.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageResolverTest {

    @Test
    fun regionIsDropped() {
        assertEquals("vi", LanguageResolver.normalize("vi-VN"))
        assertEquals("en", LanguageResolver.normalize("en_US"))
        assertEquals("zh", LanguageResolver.normalize("ZH"))
        assertNull(LanguageResolver.normalize(" "))
        assertNull(LanguageResolver.normalize(null))
    }

    @Test
    fun requestedInstalledLanguageWins() = assertEquals("vi", LanguageResolver.forOtherApp("vi-VN", "en", setOf("vi")))

    @Test
    fun systemLanguageIsTheFallback() = assertEquals("vi", LanguageResolver.forOtherApp(null, "vi", setOf("vi")))

    @Test
    fun anyInstalledModelBeatsNothing() = assertEquals("vi", LanguageResolver.forOtherApp("en-US", "fr", setOf("vi")))

    @Test
    fun noModelMeansNull() = assertNull(LanguageResolver.forOtherApp("vi", "vi", emptySet()))
}
