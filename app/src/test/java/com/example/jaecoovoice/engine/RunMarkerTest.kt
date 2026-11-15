package com.example.jaecoovoice.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunMarkerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun theNextProcessReadsWhatTheLastOneWrote() {
        RunMarker.write(tmp.root, LogRun("1.5.1", 15L, "ab12cd34"))
        val previous = RunMarker.read(tmp.root)!!
        assertEquals("ab12cd34", previous.run)
        assertEquals("1.5.1", previous.version)
        assertEquals(15L, previous.versionCode)
    }

    @Test
    fun theFirstRunHasNoPreviousOne() {
        assertNull(RunMarker.read(tmp.root))
    }

    @Test
    fun aMarkerThatIsNotOursIsIgnored() {
        java.io.File(tmp.root, "last-run.txt").writeText("garbage")
        assertNull(RunMarker.read(tmp.root))
    }

    @Test
    fun writingReplacesTheEarlierRun() {
        RunMarker.write(tmp.root, LogRun("1", 1L, "first000"))
        RunMarker.write(tmp.root, LogRun("2", 2L, "second00"))
        assertEquals("second00", RunMarker.read(tmp.root)!!.run)
    }
}
