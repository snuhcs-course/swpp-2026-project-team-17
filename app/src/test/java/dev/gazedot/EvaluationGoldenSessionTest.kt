package dev.gazedot

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pins the on-device file format. The logger writes a short, deterministic, SYNTHETIC incomplete session that
 * must equal tools/gaze_eval/testdata/synthetic_kotlin_session byte for byte; the Python analyzer tests read
 * the same files. If the format changes on purpose, inspect app/build/evaluation-golden and copy it over.
 */
class EvaluationGoldenSessionTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun loggerOutputMatchesTheCommittedGoldenSession() {
        val dir = File(tmp.root, "SYNTHETIC/eval-synthetic-0001")
        SyntheticEvaluation.session(dir).also { it.open(); SyntheticEvaluation.simulate(it, 7_600, 66, EvaluationEndReason.BACK) }
        val golden = File(SyntheticEvaluation.repoRoot(), "tools/gaze_eval/testdata/synthetic_kotlin_session")
        val names = dir.list()!!.sorted()
        assertEquals(listOf("SHA256SUMS", "attempts.csv", "events.csv", "metadata.json", "model_snapshot.json"), names)
        val differing = names.filter { !File(golden, it).isFile || !File(golden, it).readBytes().contentEquals(File(dir, it).readBytes()) }
        if (differing.isNotEmpty()) {
            val out = File("build/evaluation-golden").apply { deleteRecursively(); mkdirs() }
            names.forEach { File(dir, it).copyTo(File(out, it)) }
            fail("generated session differs from $golden in $differing; generated copy: ${out.absolutePath}")
        }
        assertTrue(EvaluationReplay.replay(dir).ok)
    }
}
