package dev.stackward.check

import org.junit.Assert.assertEquals
import org.junit.Test

class HostHealthTest {
    private val now = 1_000_000_000_000L
    private val hour = 60 * 60 * 1000L

    private fun health(checked: Long?, failed: Long?, issues: Int = 0) =
        HostHealthMapper.health(checked, failed, issues, now)

    @Test fun neverChecked() = assertEquals(HostHealth.NEVER_CHECKED, health(null, null))

    @Test fun cleanRecent() = assertEquals(HostHealth.CLEAN, health(now - hour, null))

    @Test fun issuesRecent() = assertEquals(HostHealth.ISSUES, health(now - hour, null, issues = 2))

    @Test fun staleAfterThreshold() = assertEquals(HostHealth.STALE, health(now - 25 * hour, null))

    @Test fun staleBeatsIssuesCountOnlyWhenOld() = assertEquals(HostHealth.STALE, health(now - 30 * hour, null, issues = 3))

    @Test fun failureNewerThanSuccessIsUnreachable() =
        assertEquals(HostHealth.UNREACHABLE, health(now - 3 * hour, now - hour))

    @Test fun failureWithoutAnySuccessIsUnreachable() = assertEquals(HostHealth.UNREACHABLE, health(null, now - hour))

    @Test fun successNewerThanFailureRecovers() = assertEquals(HostHealth.CLEAN, health(now - hour, now - 3 * hour))

    @Test fun ageLabels() {
        assertEquals("just now", HostHealthMapper.ageLabel(now - 10_000, now))
        assertEquals("1 minute ago", HostHealthMapper.ageLabel(now - 60_000, now))
        assertEquals("5 hours ago", HostHealthMapper.ageLabel(now - 5 * hour, now))
        assertEquals("3 days ago", HostHealthMapper.ageLabel(now - 72 * hour, now))
    }
}
