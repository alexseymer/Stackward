package dev.stackward.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardModelCatalogTest {

    @Test
    fun catalog_hasE2BAndE4BDownloads() {
        assertEquals(2, StandardModelCatalog.models.size)
        assertEquals(ModelVariant.E2B, StandardModelCatalog.forVariant(ModelVariant.E2B).variant)
        assertEquals(ModelVariant.E4B, StandardModelCatalog.forVariant(ModelVariant.E4B).variant)
    }

    @Test
    fun catalog_downloadUrlsPointAtHuggingFaceResolve() {
        StandardModelCatalog.models.forEach { model ->
            assertTrue(model.downloadUrl.startsWith("https://huggingface.co/litert-community/"))
            assertTrue(model.downloadUrl.contains("/resolve/main/"))
            assertTrue(model.fileName.endsWith(".litertlm"))
            assertTrue(model.approximateSizeBytes > 1_000_000_000L)
        }
    }

    @Test
    fun recommended_prefersE4BWhenDeviceCanRunIt() {
        val capability = DeviceCapability(
            totalRamGb = 8,
            recommendedVariant = ModelVariant.E4B,
            canRunE2B = true,
            canRunE4B = true,
        )
        assertEquals(ModelVariant.E4B, StandardModelCatalog.recommended(capability).variant)
    }
}
