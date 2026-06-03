package dev.karipap.app.scanner

import android.os.FileObserver
import dev.karipap.app.config.PlatformConfig
import dev.karipap.app.db.ScanScheduler
import dev.karipap.app.util.PlatformFolderAliases
import dev.karipap.app.util.ScanLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RomDirectoryWatcher @Inject constructor(
    private val scanScheduler: ScanScheduler,
    private val platformConfig: PlatformConfig,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val observers = ConcurrentHashMap<String, FileObserver>()
    private val debounceJobs = ConcurrentHashMap<String, Job>()
    private var rootObserver: FileObserver? = null

    private val perPlatformMask =
        FileObserver.CREATE or FileObserver.DELETE or
        FileObserver.MOVED_FROM or FileObserver.MOVED_TO or
        FileObserver.CLOSE_WRITE

    private val rootMask = FileObserver.CREATE or FileObserver.MOVED_TO

    private val debounceMs = 750L

    fun start(romDir: File, platformTags: List<String>) {
        stop()
        if (!romDir.exists()) {
            ScanLog.write("RomDirectoryWatcher: rom dir does not exist: ${romDir.absolutePath}")
            return
        }
        startRootObserver(romDir)
        for (tag in platformTags) startPlatformObserver(romDir, tag.uppercase())
    }

    fun restart(romDir: File, platformTags: List<String>) = start(romDir, platformTags)

    fun stop() {
        rootObserver?.stopWatching()
        rootObserver = null
        observers.values.forEach { it.stopWatching() }
        observers.clear()
        debounceJobs.values.forEach { it.cancel() }
        debounceJobs.clear()
    }

    private fun startRootObserver(romDir: File) {
        val obs = object : FileObserver(romDir, rootMask) {
            override fun onEvent(event: Int, path: String?) {
                val name = path ?: return
                val child = File(romDir, name)
                if (!child.isDirectory) return
                val tag = tagForDirectoryName(name) ?: return
                startPlatformObserver(romDir, tag)
                scanScheduler.enqueue(tag)
            }
        }
        rootObserver = obs
        try {
            obs.startWatching()
        } catch (t: Throwable) {
            ScanLog.write("RomDirectoryWatcher: startWatching(root) failed: ${t.message}")
            rootObserver = null
        }
    }

    private fun startPlatformObserver(romDir: File, tag: String) {
        val dirs = romDir.listFiles()
            ?.filter { it.isDirectory && PlatformFolderAliases.matches(tag, it.name) }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(File(romDir, tag))
        for (dir in dirs) startPlatformObserver(tag, dir)
    }

    private fun startPlatformObserver(tag: String, dir: File) {
        if (!dir.isDirectory) return
        val observerKey = "$tag:${dir.absolutePath}"
        if (observers.containsKey(observerKey)) return
        val obs = object : FileObserver(dir, perPlatformMask) {
            override fun onEvent(event: Int, path: String?) {
                scheduleScan(tag)
            }
        }
        try {
            obs.startWatching()
            observers[observerKey] = obs
        } catch (t: Throwable) {
            ScanLog.write("RomDirectoryWatcher: startWatching($tag:${dir.name}) failed: ${t.message}")
        }
    }

    private fun tagForDirectoryName(name: String): String? =
        platformConfig.getAllTags()
            .map { it.uppercase() }
            .firstOrNull { tag -> PlatformFolderAliases.matches(tag, name) }

    private fun scheduleScan(tag: String) {
        debounceJobs[tag]?.cancel()
        debounceJobs[tag] = scope.launch {
            delay(debounceMs)
            scanScheduler.enqueue(tag)
        }
    }
}
