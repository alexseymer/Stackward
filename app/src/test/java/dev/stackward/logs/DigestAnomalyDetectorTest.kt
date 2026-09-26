package dev.stackward.logs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DigestAnomalyDetectorTest {

    @Test
    fun detect_flagsJournalErrors() {
        val flags = DigestAnomalyDetector.detect(
            journalContent = "Aug 29 nginx[1]: error: bind failed",
            dockerSection = "",
            proxmoxSection = "",
        )
        assertTrue(flags.contains("journal_errors"))
    }

    @Test
    fun detect_ignoresEmptyJournal() {
        val flags = DigestAnomalyDetector.detect(
            journalContent = "(no entries)",
            dockerSection = "",
            proxmoxSection = "",
        )
        assertTrue(flags.isEmpty())
    }

    @Test
    fun detect_flagsDockerAndProxmox() {
        val flags = DigestAnomalyDetector.detect(
            journalContent = "",
            dockerSection = "FATAL: OOM\nRestarting container",
            proxmoxSection = "task failed with exit code 1",
        )
        assertEquals(
            listOf("docker_errors", "docker_restart", "proxmox_task_failure"),
            flags,
        )
    }
}
