package ab.inque.converter

import ab.inque.converter.pipeline.ConversionPlanner
import ab.inque.converter.pipeline.FormatCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks capability-gated pair counts documented in FEATURES_MATRIX.md. */
class CapabilityPairCountsTest {

    private fun pairCount(caps: FormatCapabilities): Int =
        FileFormat.entries.sumOf { from -> ConversionPlanner.reachableTargets(from, caps).size }

    @Test
    fun maxDevicePairCount() {
        assertEquals(600, pairCount(FormatCapabilities.ALL))
    }

    @Test
    fun noSttProfileDropsSttDocumentTargets() {
        assertEquals(535, pairCount(FormatCapabilities.ALL.copy(canTranscribe = false)))
    }

    @Test
    fun api27BaselinePairCount() {
        val baseline = FormatCapabilities(
            canEncodeHeic = false,
            canEncodeOgg = false,
            canEncodeFlac = false,
            canTranscribe = false,
            canOcr = true
        )
        assertEquals(463, pairCount(baseline))
    }

    @Test
    fun api27BaselineHasFewerPairsThanMax() {
        val baseline = FormatCapabilities(
            canEncodeHeic = false,
            canEncodeOgg = false,
            canEncodeFlac = false,
            canTranscribe = false,
            canOcr = true
        )
        assertTrue(pairCount(baseline) < pairCount(FormatCapabilities.ALL))
    }

    @Test
    fun api27BaselineHidesHeicOggFlacOutput() {
        val baseline = FormatCapabilities(
            canEncodeHeic = false,
            canEncodeOgg = false,
            canEncodeFlac = false,
            canTranscribe = false,
            canOcr = true
        )
        assertFalse(FileFormat.HEIC in ConversionPlanner.reachableTargets(FileFormat.PNG, baseline))
        assertFalse(FileFormat.OGG in ConversionPlanner.reachableTargets(FileFormat.MP3, baseline))
        assertFalse(FileFormat.FLAC in ConversionPlanner.reachableTargets(FileFormat.MP3, baseline))
    }

    @Test
    fun videoToPdfDoesNotRequireSttDeviceTag() {
        val withoutStt = FormatCapabilities.ALL.copy(canTranscribe = false)
        assertTrue(FileFormat.PDF in ConversionPlanner.reachableTargets(FileFormat.MP4, withoutStt))
        assertFalse(
            ConversionPlanner.directTargets(FileFormat.MP4, withoutStt).contains(FileFormat.TXT)
        )
    }
}
