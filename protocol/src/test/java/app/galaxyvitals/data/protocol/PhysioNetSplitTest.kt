package app.galaxyvitals.data.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class PhysioNetSplitTest {
    @Test
    fun splitIsRecordAndParticipantDisjointAndComplete() {
        val split = PhysioNetBenchmarkSplit.load()
        assertThat(split.version).isEqualTo(1)
        assertThat(split.splitRule).isEqualTo("record-and-participant")

        val mitdb = split.rows.filter { it.dataset == "mitdb" }
        val nstdb = split.rows.filter { it.dataset == "nstdb" }
        assertThat(mitdb.map { it.recordId }).containsExactlyElementsIn(EXPECTED_MITDB)
        assertThat(nstdb.map { it.recordId }).containsExactlyElementsIn(EXPECTED_NSTDB)

        val ids = split.rows.map { "${it.dataset}/${it.recordId}" }
        assertThat(ids).containsNoDuplicates()

        val participants = split.rows.groupBy { "${it.datasetKind()}:${it.participantId}" }
        participants.forEach { (_, rows) ->
            val splits = rows.map { it.split }.toSet()
            assertThat(splits).hasSize(1)
        }

        val mit118 = split.rows.single { it.dataset == "mitdb" && it.recordId == "118" }
        val mit119 = split.rows.single { it.dataset == "mitdb" && it.recordId == "119" }
        nstdb.filter { it.recordId.startsWith("118") }.forEach { row ->
            assertThat(row.participantId).isEqualTo("118")
            assertThat(row.split).isEqualTo(mit118.split)
        }
        nstdb.filter { it.recordId.startsWith("119") }.forEach { row ->
            assertThat(row.participantId).isEqualTo("119")
            assertThat(row.split).isEqualTo(mit119.split)
        }

        val rec201 = split.rows.single { it.recordId == "201" }
        val rec202 = split.rows.single { it.recordId == "202" }
        assertThat(rec201.participantId).isEqualTo("201")
        assertThat(rec202.participantId).isEqualTo("201")
        assertThat(rec201.split).isEqualTo(rec202.split)

        assertThat(split.devMitdb).hasSize(22)
        assertThat(split.lockedMitdb).hasSize(22)
        assertThat(split.devNstdb).containsExactly(
            "118e24", "118e18", "118e12", "118e06", "118e00", "118e_6",
        )
        assertThat(split.lockedNstdb).containsExactly(
            "119e24", "119e18", "119e12", "119e06", "119e00", "119e_6",
        )
        PACED_MITDB.forEach { paced ->
            assertThat(split.rows.none { it.recordId == paced }).isTrue()
        }
    }

    private fun PhysioNetBenchmarkSplit.Row.datasetKind(): String =
        if (dataset == "nstdb") "mitdb-parent" else dataset

    private companion object {
        val PACED_MITDB = setOf("102", "104", "107", "217")
        val EXPECTED_MITDB = listOf(
            "100", "101", "103", "105", "106", "108", "109", "111", "112", "113",
            "114", "115", "116", "117", "118", "119", "121", "122", "123", "124",
            "200", "201", "202", "203", "205", "207", "208", "209", "210", "212",
            "213", "214", "215", "219", "220", "221", "222", "223", "228", "230",
            "231", "232", "233", "234",
        )
        val EXPECTED_NSTDB = listOf(
            "118e24", "118e18", "118e12", "118e06", "118e00", "118e_6",
            "119e24", "119e18", "119e12", "119e06", "119e00", "119e_6",
        )
    }
}

data class PhysioNetBenchmarkSplit(
    val version: Int,
    val splitRule: String,
    val rows: List<Row>,
) {
    data class Row(
        val dataset: String,
        val recordId: String,
        val split: String,
        val participantId: String,
    )

    val devMitdb: List<String> get() = records("mitdb", "dev")
    val lockedMitdb: List<String> get() = records("mitdb", "locked")
    val devNstdb: List<String> get() = records("nstdb", "dev")
    val lockedNstdb: List<String> get() = records("nstdb", "locked")

    fun records(dataset: String, split: String): List<String> =
        rows.filter { it.dataset == dataset && it.split == split }.map { it.recordId }

    companion object {
        fun load(root: File = repoRoot()): PhysioNetBenchmarkSplit {
            val json = File(root, "tools/ecg_benchmark/physionet_split.json").readText(Charsets.UTF_8)
            val csv = File(root, "tools/ecg_benchmark/physionet_split.csv").readText(Charsets.UTF_8)
            val version = jsonField(json, "version").toInt()
            val splitRule = jsonField(json, "split_rule")
            val rows = csv.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .drop(1)
                .map { line ->
                    val cells = line.split(',')
                    Row(
                        dataset = cells[0],
                        recordId = cells[1],
                        split = cells[2],
                        participantId = cells[3],
                    )
                }
                .toList()
            return PhysioNetBenchmarkSplit(version, splitRule, rows)
        }

        private fun jsonField(json: String, key: String): String {
            val match = Regex("\"$key\"\\s*:\\s*(\"[^\"]*\"|\\d+)").find(json)
                ?: error("physionet_split.json missing $key")
            return match.groupValues[1].trim('"')
        }

        fun repoRoot(): File {
            var dir = File(System.getProperty("user.dir")).canonicalFile
            while (true) {
                if (File(dir, "settings.gradle.kts").isFile) return dir
                dir = dir.parentFile ?: error("repo root not found from ${System.getProperty("user.dir")}")
            }
        }
    }
}
