package dev.karipap.app.util

import android.content.res.AssetManager
import dev.karipap.app.config.CannoliPaths
import dev.karipap.app.config.PlatformConfig
import dev.karipap.app.di.CannoliPathsProvider
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Walks a ROM directory and returns the in-memory list of ROMs that should exist for a platform.
 * Honors ignore lists, m3u/cue dir launches, disc grouping, name-map overrides, and
 * tag/region splitting without creating platform or game subfolders.
 */
class RomDirectoryWalker(
    private val pathsProvider: CannoliPathsProvider,
    private val assets: AssetManager,
    private val platformConfig: PlatformConfig,
    private val arcadeTitleLookup: ArcadeTitleLookup,
) {
    private val cannoliRoot: File get() = pathsProvider.root
    private val romDirectory: File get() = pathsProvider.romDir

    private val discRegex = Regex("""\s*\((Disc|Disk)\s*\d+\)|\s*\(CD\d+\)""", RegexOption.IGNORE_CASE)
    private val tagRegex = Regex("""\s*(\([^)]*\)|\[[^\]]*\])""")
    private val cueFileLineRegex = Regex("""^\s*FILE\s+(?:"([^"]+)"|(\S+))\s+\w+\s*$""", RegexOption.IGNORE_CASE)

    @Volatile private var ignoredExtensions: Set<String> = emptySet()
    @Volatile private var ignoredFiles: Set<String> = emptySet()
    @Volatile private var ignoreListsLoaded = false
    private val detectionCache = ConcurrentHashMap<String, DetectionEntry>()
    private val rootIndexLock = Any()
    @Volatile private var rootDetectionIndex: RootDetectionIndex? = null
    @Volatile private var scanPass: ScanPass? = null

    data class ScannedRom(
        val relativePath: String,
        val displayName: String,
        val tags: String?,
        val discPaths: List<String>?,
    )

    /** A multi-disc set that the organizer relocated; `oldRelPath` is what the DB previously
     *  referenced (the first disc's path), `newRelPath` is the generated m3u. */
    data class RekeyMove(val oldRelPath: String, val newRelPath: String)

    private fun ensureIgnoreLists() {
        if (ignoreListsLoaded) return
        val paths = CannoliPaths(cannoliRoot)
        seedFromAsset(assets, "ignore_extensions_roms.txt", paths.ignoreExtensionsRoms)
        seedFromAsset(assets, "ignore_files_roms.txt", paths.ignoreFilesRoms)
        ignoredExtensions = readSetLowercase(paths.ignoreExtensionsRoms) { it.removePrefix(".") }
        ignoredFiles = readSetLowercase(paths.ignoreFilesRoms) { it }
        ignoreListsLoaded = true
    }

    fun beginScanPass() {
        ensureIgnoreLists()
        val root = romDirectory
        val mtime = if (root.isDirectory) computeTreeMtime(root) else root.lastModified()
        synchronized(rootIndexLock) {
            scanPass = ScanPass(root.absolutePath, mtime)
        }
    }

    fun endScanPass() {
        synchronized(rootIndexLock) {
            scanPass = null
        }
    }

    fun canDetectPlatform(tag: String): Boolean =
        tag.uppercase() in detectablePlatformTags

    fun isPlatformDirectoryName(platformTag: String, dirName: String): Boolean {
        return PlatformFolderAliases.matches(platformTag, dirName)
    }

    /** Returns null when the platform directory does not exist. */
    fun walk(platformTag: String, isArcade: Boolean): WalkResult? {
        ensureIgnoreLists()
        val tag = platformTag.uppercase()
        val tagDir = resolveTagDir(tag)
        val rekeys = mutableListOf<RekeyMove>()
        val out = mutableListOf<ScannedRom>()
        val seen = mutableSetOf<String>()
        var scannedRoot = false
        val rootMtime = currentScanPass()?.rootMtime
            ?: if (romDirectory.isDirectory) computeTreeMtime(romDirectory) else romDirectory.lastModified()

        if (tagDir != null) {
            scanDir(
                dir = tagDir,
                relPrefix = relativePrefix(tagDir),
                tag = tag,
                isArcade = isArcade,
                out = out,
                seen = seen,
                depth = 0,
                filterByDetection = false,
            )
        }

        if (romDirectory.isDirectory && shouldScanDetectedRoot(tag)) {
            scannedRoot = true
            detectedRootIndex(rootMtime).byTag[tag].orEmpty().forEach { rom ->
                addScanned(out, seen, tag, rom)
            }
        }

        if (tagDir == null && out.isEmpty()) return null
        val mtime = if (scannedRoot) rootMtime else computeTreeMtime(tagDir ?: romDirectory)
        return WalkResult(tagDir = tagDir ?: romDirectory, mtime = mtime, roms = out, rekeys = rekeys)
    }

    fun computeTreeMtime(dir: File): Long {
        var max = dir.lastModified()
        val children = dir.listFiles() ?: return max
        for (child in children) {
            if (child.name.startsWith(".")) continue
            if (child.isDirectory) {
                val sub = computeTreeMtime(child)
                if (sub > max) max = sub
            }
        }
        return max
    }

    fun resolveTagDir(tag: String): File? {
        val dirs = romDirectory.listFiles()?.filter { it.isDirectory } ?: return null
        val upperTag = tag.uppercase()
        return dirs.firstOrNull { it.name == upperTag }
            ?: dirs.firstOrNull { dir -> PlatformFolderAliases.matches(upperTag, dir.name) }
    }

    fun invalidateNameMap(tagDir: File) = arcadeTitleLookup.invalidate(tagDir)

    data class WalkResult(
        val tagDir: File,
        val mtime: Long,
        val roms: List<ScannedRom>,
        val rekeys: List<RekeyMove> = emptyList(),
    )

    private data class DirLaunch(val file: File, val discFiles: List<File>?)
    private data class PendingRom(val relativePath: String, val rawName: String, val sourceFileName: String, val discPaths: List<String>?)
    private data class DetectionEntry(val length: Long, val modified: Long, val tag: String?)
    private data class RootDetectionIndex(val rootPath: String, val mtime: Long, val byTag: Map<String, List<ScannedRom>>)
    private class ScanPass(val rootPath: String, val rootMtime: Long) {
        @Volatile var detectedIndex: RootDetectionIndex? = null
    }

    private fun scanDir(
        dir: File,
        relPrefix: String,
        tag: String,
        isArcade: Boolean,
        out: MutableList<ScannedRom>,
        seen: MutableSet<String>,
        depth: Int,
        filterByDetection: Boolean,
    ) {
        if (depth > MAX_DEPTH) return
        val entries = dir.listFiles()?.filter { !it.name.startsWith(".") && !isIgnored(it) } ?: return
        val (subdirs, files) = entries.partition { it.isDirectory }

        val inHidden = relPrefix.contains("${File.separator}_hidden${File.separator}")

        for (subdir in subdirs) {
            if (inHidden || subdir.name.equals("_hidden", ignoreCase = true)) {
                if (subdir.listFiles()?.any { !it.name.startsWith(".") } == true) {
                    scanDir(
                        dir = subdir,
                        relPrefix = "$relPrefix${subdir.name}${File.separator}",
                        tag = tag,
                        isArcade = isArcade,
                        out = out,
                        seen = seen,
                        depth = depth + 1,
                        filterByDetection = filterByDetection,
                    )
                }
                continue
            }
            val launch = findDirLaunchFile(subdir)
            val launchMatches = launch != null && (!filterByDetection || launch.discFiles?.any { matchesPlatform(it, tag) } == true || matchesPlatform(launch.file, tag))
            if (launchMatches) {
                val launchRel = "$relPrefix${subdir.name}${File.separator}${launch.file.name}"
                val discRels = launch.discFiles?.map { "$relPrefix${subdir.name}${File.separator}${it.name}" }
                val (displayName, tags) = splitNameAndTags(subdir.name)
                addScanned(out, seen, tag, ScannedRom(launchRel, displayName, tags, discRels))
            } else if (subdir.listFiles()?.any { !it.name.startsWith(".") } == true) {
                scanDir(
                    dir = subdir,
                    relPrefix = "$relPrefix${subdir.name}${File.separator}",
                    tag = tag,
                    isArcade = isArcade,
                    out = out,
                    seen = seen,
                    depth = depth + 1,
                    filterByDetection = filterByDetection,
                )
            }
        }

        if (files.isEmpty()) return

        val romFiles = files
            .filterNot { isIgnoredExtension(it) }
            .filter { !filterByDetection || matchesPlatform(it, tag) }
        val discCandidates = romFiles.filter { discRegex.containsMatchIn(it.nameWithoutExtension) }
        val discGroups = discCandidates.groupBy { discRegex.replace(it.nameWithoutExtension, "").trim() }
        val m3uByBase = romFiles
            .filter { it.extension.equals("m3u", ignoreCase = true) }
            .associateBy { it.nameWithoutExtension }

        val suppressed = mutableSetOf<String>()
        val pending = mutableListOf<PendingRom>()
        for ((baseName, discs) in discGroups) {
            if (discs.size <= 1) continue
            val sorted = discs.sortedBy { it.name }
            if (m3uByBase[baseName] != null) {
                sorted.forEach { suppressed.add(it.absolutePath) }
            } else {
                val discRels = sorted.map { "$relPrefix${it.name}" }
                pending.add(PendingRom("$relPrefix${sorted.first().name}", baseName, sorted.first().name, discRels))
                sorted.forEach { suppressed.add(it.absolutePath) }
            }
        }
        for (file in romFiles) {
            if (file.absolutePath in suppressed) continue
            pending.add(PendingRom("$relPrefix${file.name}", file.nameWithoutExtension, file.name, null))
        }

        val nameOverrides = arcadeTitleLookup.mapFor(dir, fallbackToArcade = isArcade)
        for (p in pending) {
            val override = nameOverrides[p.sourceFileName]
            val (displayName, tags) = if (override != null) override to null else splitNameAndTags(p.rawName)
            addScanned(out, seen, tag, ScannedRom(p.relativePath, displayName, tags, p.discPaths))
        }
    }

    private fun addScanned(out: MutableList<ScannedRom>, seen: MutableSet<String>, tag: String, rom: ScannedRom) {
        val key = PlatformFolderAliases.normalizedPlatformRelativePath(tag, rom.relativePath)
        if (seen.add(key)) out.add(rom)
    }

    private fun detectedRootIndex(rootMtime: Long): RootDetectionIndex {
        val rootPath = romDirectory.absolutePath
        currentScanPass()?.detectedIndex
            ?.takeIf { it.rootPath == rootPath && it.mtime == rootMtime }
            ?.let { return it }
        rootDetectionIndex
            ?.takeIf { it.rootPath == rootPath && it.mtime == rootMtime }
            ?.let { return it }

        val built = RootDetectionIndex(rootPath, rootMtime, buildDetectedRootMap())
        synchronized(rootIndexLock) {
            rootDetectionIndex = built
            currentScanPass()?.takeIf { it.rootPath == rootPath && it.rootMtime == rootMtime }?.detectedIndex = built
        }
        return built
    }

    private fun buildDetectedRootMap(): Map<String, List<ScannedRom>> {
        val byTag = linkedMapOf<String, MutableList<ScannedRom>>()
        val seenByTag = hashMapOf<String, MutableSet<String>>()
        scanDetectedRootDir(romDirectory, "", byTag, seenByTag, depth = 0)
        return byTag.mapValues { it.value.toList() }
    }

    private fun scanDetectedRootDir(
        dir: File,
        relPrefix: String,
        byTag: MutableMap<String, MutableList<ScannedRom>>,
        seenByTag: MutableMap<String, MutableSet<String>>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) return
        val entries = dir.listFiles()?.filter { !it.name.startsWith(".") && !isIgnored(it) } ?: return
        val (subdirs, files) = entries.partition { it.isDirectory }

        for (subdir in subdirs) {
            val launch = findDirLaunchFile(subdir)
            val launchTag = launch?.let { detectedLaunchTag(it) }
            if (launch != null && launchTag != null) {
                val launchRel = "$relPrefix${subdir.name}${File.separator}${launch.file.name}"
                val discRels = launch.discFiles?.map { "$relPrefix${subdir.name}${File.separator}${it.name}" }
                val (displayName, tags) = splitNameAndTags(subdir.name)
                addDetectedScanned(byTag, seenByTag, launchTag, ScannedRom(launchRel, displayName, tags, discRels))
            } else if (subdir.listFiles()?.any { !it.name.startsWith(".") } == true) {
                scanDetectedRootDir(
                    dir = subdir,
                    relPrefix = "$relPrefix${subdir.name}${File.separator}",
                    byTag = byTag,
                    seenByTag = seenByTag,
                    depth = depth + 1,
                )
            }
        }

        val filesByTag = files
            .filterNot { isIgnoredExtension(it) }
            .mapNotNull { file -> detectPlatformTag(file)?.let { tag -> tag to file } }
            .groupBy({ it.first }, { it.second })
        for ((tag, romFiles) in filesByTag) {
            addDetectedFilesForTag(relPrefix, tag, romFiles, byTag, seenByTag)
        }
    }

    private fun detectedLaunchTag(launch: DirLaunch): String? =
        launch.discFiles?.mapNotNull { detectPlatformTag(it) }?.firstOrNull()
            ?: detectPlatformTag(launch.file)

    private fun addDetectedFilesForTag(
        relPrefix: String,
        tag: String,
        romFiles: List<File>,
        byTag: MutableMap<String, MutableList<ScannedRom>>,
        seenByTag: MutableMap<String, MutableSet<String>>,
    ) {
        val discCandidates = romFiles.filter { discRegex.containsMatchIn(it.nameWithoutExtension) }
        val discGroups = discCandidates.groupBy { discRegex.replace(it.nameWithoutExtension, "").trim() }
        val m3uByBase = romFiles
            .filter { it.extension.equals("m3u", ignoreCase = true) }
            .associateBy { it.nameWithoutExtension }

        val suppressed = mutableSetOf<String>()
        val pending = mutableListOf<PendingRom>()
        for ((baseName, discs) in discGroups) {
            if (discs.size <= 1) continue
            val sorted = discs.sortedBy { it.name }
            if (m3uByBase[baseName] != null) {
                sorted.forEach { suppressed.add(it.absolutePath) }
            } else {
                val discRels = sorted.map { "$relPrefix${it.name}" }
                pending.add(PendingRom("$relPrefix${sorted.first().name}", baseName, sorted.first().name, discRels))
                sorted.forEach { suppressed.add(it.absolutePath) }
            }
        }
        for (file in romFiles) {
            if (file.absolutePath in suppressed) continue
            pending.add(PendingRom("$relPrefix${file.name}", file.nameWithoutExtension, file.name, null))
        }
        for (p in pending) {
            val (displayName, tags) = splitNameAndTags(p.rawName)
            addDetectedScanned(byTag, seenByTag, tag, ScannedRom(p.relativePath, displayName, tags, p.discPaths))
        }
    }

    private fun addDetectedScanned(
        byTag: MutableMap<String, MutableList<ScannedRom>>,
        seenByTag: MutableMap<String, MutableSet<String>>,
        tag: String,
        rom: ScannedRom,
    ) {
        val seen = seenByTag.getOrPut(tag) { mutableSetOf() }
        val key = PlatformFolderAliases.normalizedPlatformRelativePath(tag, rom.relativePath)
        if (!seen.add(key)) return
        byTag.getOrPut(tag) { mutableListOf() }.add(rom)
    }

    private fun currentScanPass(): ScanPass? {
        val rootPath = romDirectory.absolutePath
        return scanPass?.takeIf { it.rootPath == rootPath }
    }

    private fun matchesPlatform(file: File, tag: String): Boolean {
        if (!file.isFile || isIgnoredExtension(file)) return false
        val detected = detectPlatformTag(file)
        if (detected != null) return detected == tag
        return isArcadeArchive(file, tag)
    }

    private fun detectPlatformTag(file: File): String? {
        val key = file.absolutePath
        val length = file.length()
        val modified = file.lastModified()
        detectionCache[key]?.takeIf { it.length == length && it.modified == modified }?.let {
            return it.tag
        }
        val detected = computePlatformTag(file)
        if (detectionCache.size > MAX_DETECTION_CACHE) detectionCache.clear()
        detectionCache[key] = DetectionEntry(length, modified, detected)
        return detected
    }

    private fun computePlatformTag(file: File): String? {
        detectNintendoPortable(file)?.let { return it }
        val ext = file.extension.lowercase(Locale.US)
        if (ext == "zip") return detectZipPlatform(file)
        if (ext == "7z") return detectSevenZPlatform(file)
        return extensionPlatformMap[ext]
    }

    private fun detectNintendoPortable(file: File): String? {
        val ext = file.extension.lowercase(Locale.US)
        if (ext !in setOf("gb", "gbc", "gba")) return null
        return when (ext) {
            "gba" -> "GBA"
            "gbc" -> "GBC"
            "gb" -> {
                val header = try {
                    java.io.RandomAccessFile(file, "r").use { raf ->
                        if (raf.length() <= 0x143) return@use null
                        raf.seek(0x143)
                        raf.readUnsignedByte()
                    }
                } catch (_: Throwable) {
                    null
                }
                if (header == 0x80 || header == 0xC0) "GBC" else "GB"
            }
            else -> null
        }
    }

    private fun detectZipPlatform(file: File): String? {
        return try {
            ZipFile(file).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    .mapNotNull { extensionPlatformMap[File(it.name).extension.lowercase(Locale.US)] }
                    .firstOrNull()
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun detectSevenZPlatform(file: File): String? {
        return try {
            SevenZFile.builder().setFile(file).get().use { seven ->
                var entry = seven.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        extensionPlatformMap[File(entry.name).extension.lowercase(Locale.US)]?.let { return it }
                    }
                    entry = seven.nextEntry
                }
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun isArcadeArchive(file: File, tag: String): Boolean {
        if (!platformConfig.isArcade(tag)) return false
        if (!file.extension.equals("zip", ignoreCase = true)) return false
        val rel = relativePath(file)
        return rel.substringBefore(File.separator).equals(tag, ignoreCase = true)
    }

    private fun shouldScanDetectedRoot(tag: String): Boolean =
        tag in detectablePlatformTags

    private fun relativePrefix(dir: File): String {
        val rel = relativePath(dir).trim(File.separatorChar)
        return if (rel.isEmpty()) "" else "$rel${File.separator}"
    }

    private fun relativePath(file: File): String {
        val root = romDirectory.absoluteFile
        val absolute = file.absoluteFile
        return try {
            root.toPath().relativize(absolute.toPath()).toString()
        } catch (_: Throwable) {
            absolute.path.removePrefix(root.path).removePrefix(File.separator)
        }
    }

    private fun splitNameAndTags(rawName: String): Pair<String, String?> {
        val base = tagRegex.replace(rawName, "").trim()
        if (base.isEmpty() || base == rawName) return rawName to null
        val tags = tagRegex.findAll(rawName).joinToString(" ") { it.value.trim() }.takeIf { it.isNotBlank() }
        return base to tags
    }

    private fun findDirLaunchFile(dir: File): DirLaunch? {
        File(dir, "${dir.name}.m3u").takeIf { it.exists() && !isIgnored(it) }?.let { return DirLaunch(it, null) }
        File(dir, "${dir.name}.cue").takeIf { it.exists() && !isIgnored(it) }?.let { return DirLaunch(it, null) }
        dir.listFiles()?.firstOrNull { it.extension.equals("cue", ignoreCase = true) && !isIgnored(it) }?.let { return DirLaunch(it, null) }
        val children = dir.listFiles()?.filter { it.isFile && !isIgnored(it) } ?: return null
        val discs = children.filter { discRegex.containsMatchIn(it.nameWithoutExtension) }
        if (discs.size > 1) {
            val sorted = discs.sortedBy { it.name }
            return DirLaunch(sorted.first(), sorted)
        }
        return null
    }

    private fun organizeDir(
        dir: File,
        relPrefix: String,
        tag: String,
        moves: MutableList<RekeyMove>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) return
        val entries = dir.listFiles()?.filter { !it.name.startsWith(".") && !isIgnored(it) } ?: return
        val (subdirs, files) = entries.partition { it.isDirectory }

        for (subdir in subdirs) {
            organizeDir(subdir, "$relPrefix${subdir.name}${File.separator}", tag, moves, depth + 1)
        }

        val romFiles = files.filterNot { isIgnoredExtension(it) }
        val m3uByBase = romFiles.filter { it.extension.equals("m3u", ignoreCase = true) }
            .associateBy { it.nameWithoutExtension }

        val processed = mutableSetOf<File>()
        val discCandidates = romFiles.filter { discRegex.containsMatchIn(it.nameWithoutExtension) }
        val discGroups = discCandidates.groupBy { discRegex.replace(it.nameWithoutExtension, "").trim() }
        for ((baseName, groupFiles) in discGroups) {
            val byStem = groupFiles.groupBy { it.nameWithoutExtension }
            if (byStem.size <= 1) continue
            if (m3uByBase[baseName] != null) continue
            if (organizeMultiDisc(dir, baseName, byStem, romFiles, relPrefix, tag, moves)) {
                processed.addAll(groupFiles)
            }
        }

        val remainingSiblings = romFiles.filter { it !in processed }
        val looseCues = remainingSiblings.filter {
            it.extension.equals("cue", ignoreCase = true) &&
                !discRegex.containsMatchIn(it.nameWithoutExtension)
        }
        for (cue in looseCues) {
            organizeSingleCue(dir, cue, remainingSiblings, relPrefix, tag, moves)
        }
    }

    private fun organizeMultiDisc(
        parent: File,
        baseName: String,
        discsByStem: Map<String, List<File>>,
        siblings: List<File>,
        relPrefix: String,
        tag: String,
        moves: MutableList<RekeyMove>,
    ): Boolean {
        if (parent.name == baseName) return false
        val subdir = File(parent, baseName)
        if (!createSubdir(subdir, tag, baseName)) return false

        val primaries = discsByStem.values.map { pickPrimary(it) }.sortedBy { it.name }
        val allDiscFiles = discsByStem.values.flatten()
        val toMove = linkedSetOf<File>().apply {
            addAll(allDiscFiles)
            for (file in allDiscFiles) {
                addAll(stemSiblings(file, siblings))
                if (file.extension.equals("cue", ignoreCase = true)) {
                    addAll(parseCueReferencedFiles(file))
                }
            }
        }

        val moved = mutableListOf<Pair<File, File>>()
        if (!moveAll(toMove, subdir, moved, tag, baseName)) return false

        val m3uFile = File(subdir, "$baseName.m3u")
        try {
            m3uFile.writeText(primaries.joinToString("\n") { it.name } + "\n")
        } catch (e: Throwable) {
            ScanLog.write("organize $tag: failed to write $baseName.m3u: ${e.message}")
            rollback(moved, subdir)
            return false
        }

        val sortedAll = allDiscFiles.sortedBy { it.name }
        val firstStem = sortedAll.first().nameWithoutExtension
        if (firstStem != baseName) migrateSidecarFiles(tag, firstStem, baseName)

        val oldRel = "$relPrefix${sortedAll.first().name}"
        val newRel = "$relPrefix$baseName${File.separator}${m3uFile.name}"
        moves.add(RekeyMove(oldRel, newRel))
        ScanLog.write("organize $tag: bundled $baseName (${primaries.size} discs, ${toMove.size - primaries.size} companions)")
        return true
    }

    private fun organizeSingleCue(
        parent: File,
        cue: File,
        siblings: List<File>,
        relPrefix: String,
        tag: String,
        moves: MutableList<RekeyMove>,
    ) {
        val baseName = cue.nameWithoutExtension
        if (parent.name == baseName) return
        val subdir = File(parent, baseName)
        if (!createSubdir(subdir, tag, baseName)) return

        val toMove = linkedSetOf<File>().apply {
            add(cue)
            addAll(stemSiblings(cue, siblings))
            addAll(parseCueReferencedFiles(cue))
        }
        val moved = mutableListOf<Pair<File, File>>()
        if (!moveAll(toMove, subdir, moved, tag, baseName)) return

        val oldRel = "$relPrefix${cue.name}"
        val newRel = "$relPrefix$baseName${File.separator}${cue.name}"
        moves.add(RekeyMove(oldRel, newRel))
        ScanLog.write("organize $tag: bundled single-disc $baseName (${toMove.size - 1} companions)")
    }

    private fun createSubdir(subdir: File, tag: String, baseName: String): Boolean {
        if (subdir.exists()) {
            ScanLog.write("organize $tag: skip $baseName (target subfolder already exists)")
            return false
        }
        if (!subdir.mkdir()) {
            ScanLog.write("organize $tag: failed to mkdir $baseName")
            return false
        }
        return true
    }

    private fun moveAll(
        toMove: Collection<File>,
        subdir: File,
        moved: MutableList<Pair<File, File>>,
        tag: String,
        baseName: String,
    ): Boolean {
        for (file in toMove) {
            val target = File(subdir, file.name)
            if (file.renameTo(target)) {
                moved.add(file to target)
            } else {
                ScanLog.write("organize $tag: failed to move ${file.name} into $baseName/")
                rollback(moved, subdir)
                return false
            }
        }
        return true
    }

    private fun rollback(moved: List<Pair<File, File>>, subdir: File) {
        for ((src, dst) in moved) dst.renameTo(src)
        subdir.delete()
    }

    private fun pickPrimary(files: List<File>): File {
        return files.minByOrNull { f ->
            val idx = PRIMARY_DISC_EXTENSIONS.indexOf(f.extension.lowercase())
            if (idx >= 0) idx else Int.MAX_VALUE
        } ?: files.first()
    }

    private fun stemSiblings(disc: File, siblings: List<File>): List<File> {
        val stem = disc.nameWithoutExtension
        return siblings.filter { other ->
            if (other == disc || other.isDirectory) return@filter false
            val otherStem = other.nameWithoutExtension
            otherStem == stem ||
                otherStem.startsWith("$stem ") ||
                otherStem.startsWith("$stem.") ||
                other.name.startsWith("$stem.")
        }
    }

    private fun parseCueReferencedFiles(cue: File): List<File> {
        val parent = cue.parentFile ?: return emptyList()
        return try {
            cue.useLines { lines ->
                lines.mapNotNull { line ->
                    val match = cueFileLineRegex.find(line) ?: return@mapNotNull null
                    val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
                    File(parent, name).takeIf { it.isFile }
                }.toList()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun migrateSidecarFiles(tag: String, fromStem: String, toStem: String) {
        val paths = CannoliPaths(cannoliRoot)
        renameStemMatchedFiles(paths.savesFor(tag), fromStem, toStem)
        val statesTagDir = paths.saveStatesFor(tag)
        renameStemMatchedFiles(statesTagDir, fromStem, toStem)
        val stateSub = File(statesTagDir, fromStem)
        val stateSubTarget = File(statesTagDir, toStem)
        if (stateSub.isDirectory && !stateSubTarget.exists() && stateSub.renameTo(stateSubTarget)) {
            renameStemMatchedFiles(stateSubTarget, fromStem, toStem)
        }
    }

    private fun renameStemMatchedFiles(dir: File, fromStem: String, toStem: String) {
        if (!dir.isDirectory) return
        val matches = dir.listFiles()?.filter { f ->
            if (!f.isFile) return@filter false
            val n = f.nameWithoutExtension
            n == fromStem || n.startsWith("$fromStem.")
        } ?: return
        for (f in matches) {
            val newName = toStem + f.name.substring(fromStem.length)
            val target = File(dir, newName)
            if (!target.exists()) f.renameTo(target)
        }
    }

    private fun isIgnored(file: File): Boolean =
        file.name.lowercase() in ignoredFiles ||
            (file.isFile && file.extension.lowercase() in ignoredExtensions)

    private fun isIgnoredExtension(file: File): Boolean =
        file.extension.lowercase() in ignoredExtensions

    private fun seedFromAsset(assets: AssetManager, name: String, target: File) {
        if (target.exists()) return
        try {
            target.parentFile?.mkdirs()
            assets.open(name).use { input -> target.outputStream().use { input.copyTo(it) } }
        } catch (_: Throwable) { }
    }

    private fun readSetLowercase(file: File, transform: (String) -> String): Set<String> {
        if (!file.exists()) return emptySet()
        return try {
            file.readLines().map { transform(it.trim().lowercase()) }.filter { it.isNotEmpty() }.toSet()
        } catch (_: Throwable) { emptySet() }
    }

    private companion object {
        const val MAX_DEPTH = 16
        const val MAX_DETECTION_CACHE = 20_000
        val PRIMARY_DISC_EXTENSIONS = listOf("cue", "chd", "gdi", "toc", "ccd", "iso", "img", "pbp", "bin")
        val extensionPlatformMap = mapOf(
            "gb" to "GB",
            "sgb" to "GB",
            "gbc" to "GBC",
            "gba" to "GBA",
            "nes" to "NES",
            "fds" to "FDS",
            "sfc" to "SNES",
            "smc" to "SNES",
            "swc" to "SNES",
            "fig" to "SNES",
            "n64" to "N64",
            "z64" to "N64",
            "v64" to "N64",
            "nds" to "NDS",
            "gg" to "GG",
            "sms" to "SMS",
            "md" to "MD",
            "gen" to "MD",
            "smd" to "MD",
            "32x" to "32X",
            "sg" to "SG1000",
            "pce" to "PCE",
            "ngp" to "NGP",
            "ngc" to "NGPC",
            "ws" to "WS",
            "wsc" to "WSC",
            "vb" to "VIRTUALBOY",
            "min" to "POKEMINI",
            "col" to "COLECOVISION",
            "vec" to "VECTREX",
            "int" to "INTELLIVISION",
            "adf" to "AMIGA",
            "ipf" to "AMIGA",
            "uae" to "AMIGA",
            "scummvm" to "SCUMMVM",
        )
        val detectablePlatformTags = extensionPlatformMap.values.toSet()
    }
}
