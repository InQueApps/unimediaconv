package ab.inque.converter.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatCapabilitiesTest {

    @Test
    fun all_enablesEveryCapability() {
        val caps = FormatCapabilities.ALL
        assertTrue(caps.canEncodeHeic)
        assertTrue(caps.canEncodeOgg)
        assertTrue(caps.canEncodeFlac)
        assertTrue(caps.canTranscribe)
        assertTrue(caps.canOcr)
    }

    @Test
    fun copy_canDisableSttIndependently() {
        val caps = FormatCapabilities.ALL.copy(canTranscribe = false)
        assertFalse(caps.canTranscribe)
        assertTrue(caps.canOcr)
    }
}
