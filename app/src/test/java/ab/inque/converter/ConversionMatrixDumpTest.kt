package ab.inque.converter

import ab.inque.converter.pipeline.ConversionPlanner
import ab.inque.converter.pipeline.FormatCapabilities
import org.junit.Test

/** Dev helper: run with `./gradlew :app:testDebugUnitTest --tests ConversionMatrixDumpTest` */
class ConversionMatrixDumpTest {

    @Test
    fun dumpReachablePairCounts() {
        val caps = FormatCapabilities.ALL
        var total = 0
        val sb = StringBuilder()
        for (from in FileFormat.entries) {
            val targets = ConversionPlanner.reachableTargets(from, caps).sortedBy { it.name }
            total += targets.size
            sb.appendLine("${from.name}: ${targets.joinToString(",") { it.name }}")
        }
        sb.appendLine("TOTAL=$total")
        val out = java.io.File("build/conversion-matrix-dump.txt")
        out.parentFile.mkdirs()
        out.writeText(sb.toString())
    }
}
