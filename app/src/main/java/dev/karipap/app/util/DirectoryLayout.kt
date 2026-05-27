package dev.karipap.app.util

import android.content.res.AssetManager
import dev.karipap.app.config.CannoliPaths
import dev.karipap.app.config.PlatformConfig
import java.io.File

object DirectoryLayout {
    fun ensure(cannoliRoot: File, romDirectory: File, biosDirectory: File, assets: AssetManager, platformConfig: PlatformConfig) {
        val paths = CannoliPaths(cannoliRoot)
        val dirs = listOf(
            cannoliRoot,
            romDirectory,
            biosDirectory,
            paths.artDir,
            paths.savesDir,
            paths.saveStatesDir,
            paths.mediaScreenshotsDir,
            paths.mediaRecordingsDir,
            paths.configDir,
            paths.configState,
            paths.configRetroArch,
            paths.configOverrides,
            paths.configOverridesCores,
            paths.configOverridesSystems,
            paths.configOverridesGames,
            paths.configCache,
            paths.configProfiles,
            paths.configFonts,
            paths.configOrdering,
            paths.configLaunchScripts,
            paths.toolsDir,
            paths.portsDir,
            paths.configRetroAchievements,
            paths.configAssets,
            paths.configInputMappings,
            paths.collectionsDir,
            paths.backupDir,
            paths.guidesDir,
            paths.wallpapersDir,
            paths.shadersDir,
            paths.overlaysDir,
            paths.logsDir,
        )
        dirs.forEach { it.mkdirs() }
        StoragePermissions.ensurePcWritable(cannoliRoot, romDirectory, biosDirectory)

        val arcadeMap = paths.arcadeMapFile
        if (!arcadeMap.exists()) {
            try {
                assets.open("arcade_map.txt").use { input ->
                    arcadeMap.outputStream().use { input.copyTo(it) }
                }
            } catch (_: Exception) {}
        }

        for (tag in platformConfig.getAllTags()) {
            paths.artFor(tag).mkdirs()
            paths.savesFor(tag).mkdirs()
            paths.saveStatesFor(tag).mkdirs()
            paths.guidesFor(tag).mkdirs()
        }
    }
}
