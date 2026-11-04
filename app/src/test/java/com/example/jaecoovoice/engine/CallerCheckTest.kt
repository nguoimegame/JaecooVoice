package com.example.jaecoovoice.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallerCheckTest {

    @Test
    fun onlyJaecooAppMayCall() {
        assertTrue(CallerCheck.isAllowed(arrayOf("com.example.jaecooapp")))
        assertFalse(CallerCheck.isAllowed(arrayOf("com.evil.app")))
        assertFalse(CallerCheck.isAllowed(null))
        assertFalse(CallerCheck.isAllowed(emptyArray()))
        assertFalse(CallerCheck.isAllowed(arrayOf("com.example.jaecooapp.fake")))
    }

    @Test
    fun aSharedUidThatIncludesJaecooAppIsAllowed() = assertTrue(CallerCheck.isAllowed(arrayOf("com.other", "com.example.jaecooapp")))
}
