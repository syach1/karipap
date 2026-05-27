package dev.karipap.app.setup

import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.karipap.app.util.NaturalSort
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SetupCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var volumeMap: Map<String, String> = emptyMap()

    fun detectExistingCannoli(): String? {
        val volumes = detectStorageVolumes()
        for ((_, path) in volumes.reversed()) {
            for (name in listOf("Karipap", "Synnoli", "Cannoli")) {
                val root = File(path, name)
                if (root.exists() && root.isDirectory && File(root, "Config/settings.json").exists()) {
                    return root.absolutePath + "/"
                }
            }
        }
        return null
    }

    fun detectStorageVolumes(): List<Pair<String, String>> {
        val volumes = linkedMapOf("Internal Storage" to "/storage/emulated/0/")
        val seenPaths = mutableSetOf("/storage/emulated/0")

        fun addVolume(label: String, path: String) {
            val normalizedPath = path.trimEnd('/')
            if (normalizedPath.isBlank() || normalizedPath in seenPaths) return

            val dir = File(normalizedPath)
            if (!dir.isDirectory || !dir.canRead()) return

            val baseLabel = label.ifBlank { dir.name }
            var uniqueLabel = baseLabel
            var suffix = 2
            while (volumes.containsKey(uniqueLabel)) {
                uniqueLabel = "$baseLabel $suffix"
                suffix += 1
            }

            volumes[uniqueLabel] = "$normalizedPath/"
            seenPaths.add(normalizedPath)
        }

        val sm = context.getSystemService(StorageManager::class.java)
        for (sv in sm.storageVolumes) {
            if (sv.isPrimary) continue
            val path = if (Build.VERSION.SDK_INT >= 30) {
                sv.directory?.absolutePath
            } else {
                try { sv.javaClass.getMethod("getPath").invoke(sv) as? String } catch (_: Exception) { null }
            } ?: continue
            val label = sv.getDescription(context) ?: File(path).name
            addVolume(label, path)
        }

        val storageDir = File("/storage")
        storageDir.listFiles()?.forEach { dir ->
            if (dir.name != "emulated" && dir.name != "self") {
                addVolume(dir.name, dir.absolutePath)
            }
        }
        addVolume("SYACH1", "/storage/SYACH1")

        return volumes.toList()
    }

    fun listDirectories(path: String): List<String> {
        if (path == "/storage/") {
            val volumes = detectStorageVolumes()
            volumeMap = volumes.associate { (label, volPath) -> label to volPath }
            return volumes.map { it.first }
        }
        val dir = File(path)
        return dir.listFiles()
            ?.filter { it.isDirectory && !it.isHidden }
            ?.map { it.name }
            ?.sortedWith(NaturalSort)
            ?: emptyList()
    }

    fun resolveDirectoryEntry(currentPath: String, entryName: String): String {
        if (currentPath == "/storage/") {
            return volumeMap[entryName] ?: "/storage/$entryName/"
        }
        return currentPath + entryName + "/"
    }

    fun parentDirectory(path: String): String? {
        val trimmed = path.trimEnd('/')
        if (trimmed == "/storage") return null
        if (volumeMap.values.any { it.trimEnd('/') == trimmed }) return "/storage/"
        return if (trimmed.contains('/')) trimmed.substringBeforeLast('/') + "/" else null
    }

    fun isVolumeRoot(path: String): Boolean = detectStorageVolumes().any { it.second == path }
}
