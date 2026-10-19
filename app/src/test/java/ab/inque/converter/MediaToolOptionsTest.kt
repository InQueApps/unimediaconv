package ab.inque.converter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaToolOptionsTest {

    @Test
    fun exportAllVideoFrames_countsAsTool() {
        val opts = MediaToolOptions(exportAllVideoFrames = true)
        assertTrue(opts.hasAnyTool)
    }

    @Test
    fun extractFrame_countsAsTool() {
        val opts = MediaToolOptions(extractFrame = true, extractFrameMs = 5000L)
        assertTrue(opts.hasAnyTool)
        assertTrue(opts.hasExtractFrame)
    }

    @Test
    fun defaults_noTools() {
        val opts = MediaToolOptions()
        assertFalse(opts.hasAnyTool)
    }
}
