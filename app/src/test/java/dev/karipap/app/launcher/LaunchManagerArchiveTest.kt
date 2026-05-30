package dev.karipap.app.launcher

import androidx.test.core.app.ApplicationProvider
import dev.karipap.app.config.PlatformConfig
import dev.karipap.app.model.LaunchTarget
import dev.karipap.app.model.Rom
import dev.karipap.app.settings.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchManagerArchiveTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `external retroarch launch keeps archive on public path`() {
        val manager = launchManager()
        val archive = zipWithRom("game.zip", "game.gbc")
        val rom = Rom(
            id = 1,
            path = archive,
            platformTag = "GBC",
            displayName = "Game",
            launchTarget = LaunchTarget.RetroArch,
        )

        val external = manager.resolveLaunchFile(rom, extractArchives = false)
        val internal = manager.resolveLaunchFile(rom, extractArchives = true)

        assertEquals(archive.absolutePath, external?.absolutePath)
        assertNotEquals(archive.absolutePath, internal?.absolutePath)
        assertEquals("gbc", internal?.extension)
        assertTrue(internal?.absolutePath?.contains("/rom_cache/") == true)
    }

    private fun launchManager(): LaunchManager {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = SettingsRepository(context)
        val platformConfig = PlatformConfig(tmp.newFolder("root"), context.assets)
        return LaunchManager(
            context = context,
            settings = settings,
            platformConfig = platformConfig,
            retroArchLauncher = RetroArchLauncher(context) { "com.retroarch.aarch64" },
            emuLauncher = EmuLauncher(context),
            apkLauncher = ApkLauncher(context, ShellLauncher(context)),
        )
    }

    private fun zipWithRom(zipName: String, entryName: String): File {
        val archive = tmp.newFile(zipName)
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(byteArrayOf(0x01, 0x02, 0x03))
            zip.closeEntry()
        }
        return archive
    }
}
