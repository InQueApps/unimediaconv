package ab.inque.converter

import ab.inque.converter.pipeline.ConversionPlanner
import ab.inque.converter.pipeline.FormatCapabilities
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ensures every UI-offered conversion has a valid planner path whose hops match [directTargets].
 */
class PlannerHandlerSyncTest {

    @Test
    fun allReachablePairs_haveValidDirectHopPaths() {
        val caps = FormatCapabilities.ALL
        var pairCount = 0
        for (from in FileFormat.entries) {
            for (to in ConversionPlanner.reachableTargets(from, caps)) {
                pairCount++
                val path = ConversionPlanner.findPath(from, to, caps)
                assertNotNull("No path for $from -> $to", path)
                assertTrue("Path too short for $from -> $to", path!!.size >= 2)
                for (i in 0 until path.size - 1) {
                    val current = path[i]
                    val next = path[i + 1]
                    val neighbors = ConversionPlanner.directTargets(current, caps)
                    assertTrue(
                        "Hop $current -> $next not in directTargets (full path: ${path.joinToString { it.name }})",
                        next in neighbors
                    )
                }
            }
        }
        assertTrue("Expected hundreds of pairs", pairCount > 500)
    }

    @Test
    fun mkv_offeredAsTarget_onCapableDevice() {
        val caps = FormatCapabilities.ALL
        assertTrue(
            "MP4 should reach MKV",
            FileFormat.MKV in ConversionPlanner.reachableTargets(FileFormat.MP4, caps)
        )
        assertTrue(
            "MP3 should reach MKV via audio→video",
            FileFormat.MKV in ConversionPlanner.reachableTargets(FileFormat.MP3, caps)
        )
    }
}
