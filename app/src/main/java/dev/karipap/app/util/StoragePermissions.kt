package dev.karipap.app.util

import android.system.Os
import java.io.File

object StoragePermissions {
    private const val DIR_MODE_WORLD_WRITABLE = 0x1FF

    fun ensurePcWritable(vararg dirs: File) {
        dirs.forEach { dir ->
            if (!dir.exists()) dir.mkdirs()
            try {
                dir.setReadable(true, false)
                dir.setWritable(true, false)
                dir.setExecutable(true, false)
                Os.chmod(dir.absolutePath, DIR_MODE_WORLD_WRITABLE)
            } catch (_: Exception) {
            }
        }
    }
}
