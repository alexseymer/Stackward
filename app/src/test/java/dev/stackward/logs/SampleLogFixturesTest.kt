package dev.stackward.logs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleLogFixturesTest {

    @Test
    fun fixtures_haveContent() {
        SampleLogFixture.entries.forEach { fixture ->
            assertTrue(fixture.content.isNotBlank())
            assertTrue(fixture.title.isNotBlank())
        }
    }

    @Test
    fun fromId_resolvesFixture() {
        assertEquals(SampleLogFixture.DOCKER_OOM, SampleLogFixture.fromId("docker-oom"))
        assertEquals(null, SampleLogFixture.fromId("missing"))
    }

    @Test
    fun nginxSample_contains502() {
        assertNotNull(SampleLogFixture.NGINX_502.content.contains("502"))
    }
}
