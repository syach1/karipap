package dev.karipap.app.config

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformsJsonAssetTest {
    @Test fun `bundled platforms_json parses without throwing`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pc = PlatformConfig(File(ctx.cacheDir, "fake-root"), ctx.assets)
        check(pc.getAllTags().isNotEmpty())
    }

    @Test fun `gba defaults to gpsp for bundled libretro`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pc = PlatformConfig(File(ctx.cacheDir, "fake-root-gba"), ctx.assets)
        assertEquals("gpsp_libretro", pc.getCoreMapping("GBA"))
    }
}
