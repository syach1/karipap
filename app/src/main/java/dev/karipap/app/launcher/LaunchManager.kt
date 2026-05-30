package dev.karipap.app.launcher

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.karipap.app.config.CannoliPaths
import dev.karipap.app.config.CoreInfoRepository
import dev.karipap.app.config.PlatformConfig
import dev.karipap.app.libretro.LibretroActivity
import dev.karipap.app.libretro.SaveSlotManager
import dev.karipap.app.model.App
import dev.karipap.app.model.LaunchTarget
import dev.karipap.app.model.Rom
import dev.karipap.app.settings.SettingsRepository
import dev.karipap.app.ui.screens.DialogState
import dev.karipap.app.util.ArchiveExtractor
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.Normalizer

class LaunchManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val platformConfig: PlatformConfig,
    private val retroArchLauncher: RetroArchLauncher,
    private val emuLauncher: EmuLauncher,
    private val apkLauncher: ApkLauncher,
    private val installedCoreService: InstalledCoreService? = null,
    private val coreInfo: CoreInfoRepository? = null,
) {
    private var raConfigPath: String? = null
    @Volatile var launching = false

    init {
        apkLauncher.debugLog = ::debugLog
    }

    fun syncRetroArchAssets(root: File) {
        val fontDest = CannoliPaths(root).cannoliFont
        fontDest.parentFile?.mkdirs()
        try {
            context.assets.open("fonts/JetBrainsMonoNerdFont-Bold.ttf").use { input ->
                if (fontDest.exists() && fontDest.length() == input.available().toLong()) return
                fontDest.outputStream().use { input.copyTo(it) }
            }
        } catch (_: IOException) {}
    }

    fun syncRetroArchConfig(root: File) {
        val raDir = CannoliPaths(root).configRetroArch
        raDir.mkdirs()
        val localConfig = File(raDir, "retroarch.cfg")
        val hashFile = File(raDir, ".ra_config_hash")

        val raPackage = settings.retroArchPackage
        val sourceConfig = File("/storage/emulated/0/Android/data/$raPackage/files/retroarch.cfg")

        if (!sourceConfig.exists()) {
            if (!localConfig.exists()) {
                localConfig.writeText(buildMinimalConfig(root.absolutePath))
            }
            raConfigPath = localConfig.absolutePath
            return
        }

        val sourceBytes = try { sourceConfig.readBytes() } catch (_: IOException) {
            if (!localConfig.exists()) localConfig.writeText(buildMinimalConfig(root.absolutePath))
            raConfigPath = localConfig.absolutePath
            return
        }
        val sourceHash = sha256(sourceBytes, "$CONFIG_VERSION:${settings.raUsername}:${settings.raToken}".toByteArray())
        val storedHash = if (hashFile.exists()) try { hashFile.readText().trim() } catch (_: IOException) { "" } else ""

        if (sourceHash != storedHash || !localConfig.exists()) {
            val patched = patchRetroArchConfig(String(sourceBytes), root.absolutePath)
            localConfig.writeText(patched)
            hashFile.writeText(sourceHash)
        }

        raConfigPath = localConfig.absolutePath
    }

    private fun buildGameConfig(rom: Rom, resume: Boolean = false, slot: Int = 0): String? {
        val base = raConfigPath ?: return null
        val baseConfig = try { File(base).readText() } catch (_: IOException) { return null }
        val paths = CannoliPaths(settings.sdCardRoot)
        val romName = normalizedRomName(rom)
        val stateDir = paths.saveStateDir(rom.platformTag, romName)
        stateDir.mkdirs()
        val biosDir = paths.biosRoot(settings.biosDirectory)
        val raSlot = if (slot > 0) slot - 1 else 0
        val gameOverrides = buildMap {
            put("system_directory", biosDir.absolutePath)
            put("savestate_directory", stateDir.absolutePath)
            put("sort_savestates_by_content_enable", "false")
            put("state_slot", raSlot.toString())
            if (resume) {
                put("savestate_auto_load", "true")
            }
        }
        val patched = applyOverrides(baseConfig, gameOverrides)
        val launchConfig = paths.raLaunchCfg
        launchConfig.writeText(patched)
        return launchConfig.absolutePath
    }

    private fun prepareLibretroSystemDir(paths: CannoliPaths, coreId: String): File {
        val sourceDir = paths.biosRoot(settings.biosDirectory)
        if (!sourceDir.isDirectory) return sourceDir

        val firmware = coreInfo?.getFirmwareFor(coreId).orEmpty()
        if (firmware.isEmpty()) return sourceDir

        val wantedNames = firmware
            .map { File(it.path).name.lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (wantedNames.isEmpty()) return sourceDir

        val byName = linkedMapOf<String, File>()
        try {
            for (file in sourceDir.walkTopDown()) {
                if (!file.isFile || file.name.startsWith(".")) continue
                val name = file.name.lowercase()
                if (name !in wantedNames) continue
                byName.putIfAbsent(name, file)
                if (byName.keys.containsAll(wantedNames)) break
            }
        } catch (_: Throwable) {
        }
        if (byName.isEmpty()) return sourceDir

        val stagingDir = File(context.cacheDir, "libretro_system/$coreId")
        stagingDir.mkdirs()

        firmware.forEach { firmware ->
            val expectedName = File(firmware.path).name.lowercase()
            val source = byName[expectedName] ?: return@forEach
            copyFirmware(source, File(stagingDir, firmware.path))
            copyFirmware(source, File(stagingDir, source.name))
        }

        return stagingDir
    }

    private fun copyFirmware(source: File, target: File) {
        try {
            target.parentFile?.mkdirs()
            if (!target.exists() || target.length() != source.length() || target.lastModified() < source.lastModified()) {
                source.copyTo(target, overwrite = true)
                target.setLastModified(source.lastModified())
            }
        } catch (_: Throwable) {
        }
    }

    private fun buildMinimalConfig(rootPath: String) = buildString {
        appendLine("savefile_directory = \"$rootPath/Saves\"")
        appendLine("savestate_directory = \"$rootPath/Save States\"")
        appendLine("sort_savefiles_by_content_enable = \"true\"")
        appendLine("savestate_file_compression = \"false\"")
        appendLine("config_save_on_exit = \"false\"")
        appendLine("video_font_enable = \"false\"")
        appendLine("assets_directory = \"$rootPath/Config/Assets\"")
    }

    private fun patchRetroArchConfig(source: String, rootPath: String): String {
        val raUser = settings.raUsername
        val raToken = settings.raToken
        val overrides = buildMap {
            put("savefile_directory", "$rootPath/Saves")
            put("savestate_directory", "$rootPath/Save States")
            put("screenshot_directory", "$rootPath/Media/Screenshots")
            put("recording_output_directory", "$rootPath/Media/Recordings")
            put("sort_savefiles_by_content_enable", "true")
            put("savestate_file_compression", "false")
            put("savestate_block_format", "false")
            put("savestate_thumbnail_enable", "true")
            put("savestate_auto_save", "true")
            put("config_save_on_exit", "false")
            put("video_font_enable", "false")

            // TODO come back to this at a later date
//            put("assets_directory", "$rootPath/Config/Assets")

            if (raUser.isNotEmpty() && raToken.isNotEmpty()) {
                put("cheevos_enable", "true")
                put("cheevos_username", raUser)
                put("cheevos_token", raToken)
            }
        }
        return applyOverrides(source, overrides)
    }

    private fun applyOverrides(source: String, overrides: Map<String, String>): String {
        val applied = mutableSetOf<String>()
        val lines = source.lines().map { line ->
            val trimmed = line.trimStart()
            val key = trimmed.substringBefore('=').trim().removePrefix("# ")
            if (key in overrides) {
                applied.add(key)
                "$key = \"${overrides[key]}\""
            } else line
        }.toMutableList()
        for ((key, value) in overrides) {
            if (key !in applied) lines.add("$key = \"$value\"")
        }
        return lines.joinToString("\n")
    }

    private fun sha256(vararg parts: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) digest.update(part)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Recovery path: only reached if the walker organizer was unable to move the discs into a
     *  subfolder. Writes the m3u next to the discs (libretro cores resolve disc paths relative to
     *  the m3u's directory) so the next scan picks it up via the m3u-by-base-name branch. */
    fun createFallbackM3u(rom: Rom): File {
        val discs = checkNotNull(rom.discFiles)
        val first = discs.first()
        val parent = first.parentFile ?: throw IOException("Cannot resolve disc directory")
        val base = DISC_REGEX.replace(first.nameWithoutExtension, "").trim()
        val m3uFile = File(parent, "$base.m3u")
        m3uFile.writeText(discs.joinToString("\n") { it.name } + "\n")
        return m3uFile
    }

    fun resolveLaunchFile(rom: Rom, extractArchives: Boolean = true): File? {
        if (rom.discFiles != null) return createFallbackM3u(rom)
        if (extractArchives && ArchiveExtractor.isArchive(rom.path) && !platformConfig.isArcade(rom.platformTag)) {
            return ArchiveExtractor.extract(rom.path, context.cacheDir)
        }
        return rom.path
    }

    fun findEmbeddedCore(coreName: String): String? {
        val soName = "${coreName}_android.so"
        val coreFile = File(context.filesDir, "cores/$soName")
        return if (coreFile.exists()) coreFile.absolutePath else null
    }

    fun getEmbeddedCorePath(rom: Rom): String? {
        val gameOverride = platformConfig.getGameOverride(rom.path.absolutePath)
        if (gameOverride?.appPackage != null) return null
        val target = rom.launchTarget
        if (target is LaunchTarget.Embedded) return target.corePath
        if (target !is LaunchTarget.RetroArch) return null
        val core = gameOverride?.coreId ?: platformConfig.getCoreName(rom.platformTag) ?: return null
        val mappedRaPackage = gameOverride?.raPackage ?: platformConfig.getPackage(rom.platformTag)
        val runnerPref = PlatformConfig.normalizeRunnerLabel(gameOverride?.runner ?: platformConfig.getRunnerPreference(rom.platformTag))
            ?: mappedRaPackage?.let { PlatformConfig.normalizeRunnerLabel(InstalledCoreService.getPackageLabel(it)) }
        if (PlatformConfig.isRetroArchRunner(runnerPref)) return null
        return findEmbeddedCore(core)
    }

    fun findMostRecentSlot(rom: Rom): Int? {
        val romName = normalizedRomName(rom)
        val stateBase = CannoliPaths(settings.sdCardRoot).saveStateBase(rom.platformTag, romName)
        val slotManager = SaveSlotManager(stateBase.absolutePath)
        return slotManager.slots
            .filter { File(slotManager.statePath(it)).exists() }
            .maxByOrNull { File(slotManager.statePath(it)).lastModified() }
            ?.index
    }

    private fun hasSaveState(rom: Rom): Boolean {
        val romName = normalizedRomName(rom)
        val stateDir = CannoliPaths(settings.sdCardRoot).saveStateDir(rom.platformTag, romName)
        if (!stateDir.exists()) return false
        return stateDir.listFiles()?.any {
            it.name.startsWith("$romName.state") && !it.name.endsWith(".png")
        } ?: false
    }

    fun findResumableRoms(roms: List<Rom>): Set<String> {
        val result = mutableSetOf<String>()
        for (rom in roms) {
            if (!hasSaveState(rom)) continue
            val target = rom.launchTarget
            val embedded = target is LaunchTarget.Embedded || getEmbeddedCorePath(rom) != null
            if (embedded || (target is LaunchTarget.RetroArch && !settings.retroArchDiyMode)) {
                result.add(rom.path.absolutePath)
            }
        }
        return result
    }

    fun launchRom(rom: Rom): DialogState? {
        debugLog("launchRom entered: ${rom.platformTag} / ${rom.path.name} target=${rom.launchTarget::class.simpleName}")
        if (launching) return null
        launching = true

        val gameOverride = platformConfig.getGameOverride(rom.path.absolutePath)
        if (gameOverride?.appPackage != null) {
            val launchFile = resolveLaunchFile(rom, extractArchives = true)
                ?: return errorAndReset(DialogState.LaunchError("Failed to extract archive"))
            val cfg = platformConfig.getAppConfig(rom.platformTag, gameOverride.appPackage)
            return launchResultDialog(apkLauncher.launchWithRom(gameOverride.appPackage, launchFile, cfg))
        }

        val result = when (val target = rom.launchTarget) {
            is LaunchTarget.RetroArch -> {
                val mappedRaPackage = gameOverride?.raPackage ?: platformConfig.getPackage(rom.platformTag)
                var runnerPref = PlatformConfig.normalizeRunnerLabel(gameOverride?.runner ?: platformConfig.getRunnerPreference(rom.platformTag))
                    ?: mappedRaPackage?.let { PlatformConfig.normalizeRunnerLabel(InstalledCoreService.getPackageLabel(it)) }
                if (runnerPref == null) {
                    val core = gameOverride?.coreId ?: platformConfig.getCoreName(rom.platformTag)
                    val embeddedAvailable = core?.let { findEmbeddedCore(it) != null } ?: false
                    val raAvailable = core != null && installedCoreService?.installedCores?.any { it.value.contains(core) } == true
                    if (!embeddedAvailable && !raAvailable
                        && platformConfig.getFirstInstalledApp(rom.platformTag, context.packageManager) != null) {
                        runnerPref = "Standalone"
                    }
                }
                if (runnerPref == "Standalone") {
                    val launchFile = resolveLaunchFile(rom, extractArchives = true)
                        ?: return errorAndReset(DialogState.LaunchError("Failed to extract archive"))
                    val cfg = platformConfig.getFirstInstalledApp(rom.platformTag, context.packageManager)
                        ?: platformConfig.getAppPackage(rom.platformTag)?.let { platformConfig.getAppConfig(rom.platformTag, it) }
                    if (cfg != null) {
                        apkLauncher.launchWithRom(cfg.packageName, launchFile, cfg)
                    } else {
                        LaunchResult.CoreNotInstalled("unknown")
                    }
                } else {
                    val core = gameOverride?.coreId ?: platformConfig.getCoreName(rom.platformTag)
                    if (core != null) {
                        if (!PlatformConfig.isRetroArchRunner(runnerPref)) {
                            val embeddedCorePath = findEmbeddedCore(core)
                            debugLog("RetroArch target: core=$core runnerPref=$runnerPref embeddedCorePath=$embeddedCorePath")
                            if (embeddedCorePath != null) {
                                val launchFile = resolveLaunchFile(rom, extractArchives = true)
                                    ?: return errorAndReset(DialogState.LaunchError("Failed to extract archive"))
                                return launchEmbedded(rom.copy(path = launchFile), embeddedCorePath, originalRomPath = rom.path.absolutePath)
                            }
                        }
                        val raPackage = mappedRaPackage ?: settings.retroArchPackage
                        if (installedCoreService != null) {
                            if (!context.isPackageInstalled(raPackage)) {
                                val appName = try {
                                    val info = context.packageManager.getApplicationInfo(raPackage, 0)
                                    context.packageManager.getApplicationLabel(info).toString()
                                } catch (_: PackageManager.NameNotFoundException) { raPackage }
                                return errorAndReset(DialogState.MissingApp(appName, raPackage))
                            }
                            if (installedCoreService.cacheReady
                                && raPackage !in installedCoreService.unresponsivePackages
                                && !installedCoreService.hasCoreInPackage(core, raPackage)) {
                                val label = InstalledCoreService.getPackageLabel(raPackage)
                                return errorAndReset(DialogState.MissingCore("$core not found in $label"))
                            }
                        }
                        val launchFile = resolveLaunchFile(rom, extractArchives = false)
                            ?: return errorAndReset(DialogState.LaunchError("Failed to prepare ROM"))
                        if (settings.retroArchDiyMode) {
                            val raConfig = "/storage/emulated/0/Android/data/$raPackage/files/retroarch.cfg"
                            retroArchLauncher.launch(launchFile, core, raConfig, raPackage, buildIGMExtras(rom))
                        } else {
                            syncRetroArchConfig(File(settings.sdCardRoot))
                            val launchConfig = buildGameConfig(rom) ?: raConfigPath
                            retroArchLauncher.launch(launchFile, core, launchConfig, raPackage, buildIGMExtras(rom))
                        }
                    } else {
                        LaunchResult.CoreNotInstalled("unknown")
                    }
                }
            }
            is LaunchTarget.EmuLaunch -> {
                val launchFile = resolveLaunchFile(rom, extractArchives = true)
                    ?: return errorAndReset(DialogState.LaunchError("Failed to extract archive"))
                emuLauncher.launch(launchFile, target.packageName, target.activityName, target.action)
            }
            is LaunchTarget.ApkLaunch -> {
                val launchFile = resolveLaunchFile(rom, extractArchives = true)
                    ?: return errorAndReset(DialogState.LaunchError("Failed to extract archive"))
                val pkg = if (context.isPackageInstalled(target.packageName)) {
                    target.packageName
                } else {
                    platformConfig.getFirstInstalledApp(rom.platformTag, context.packageManager)?.packageName
                        ?: target.packageName
                }
                if (launchFile.extension != "apk_launch" && launchFile.exists()) {
                    val cfg = platformConfig.getAppConfig(rom.platformTag, pkg)
                    apkLauncher.launchWithRom(pkg, launchFile, cfg)
                } else {
                    apkLauncher.launch(pkg)
                }
            }
            is LaunchTarget.Embedded -> {
                val launchFile = resolveLaunchFile(rom, extractArchives = true)
                    ?: return errorAndReset(DialogState.LaunchError("Failed to extract archive"))
                return launchEmbedded(rom.copy(path = launchFile), target.corePath, originalRomPath = rom.path.absolutePath)
            }
        }

        return launchResultDialog(result)
    }

    fun launchApp(app: App): DialogState? {
        debugLog("launchApp entered: ${app.type} / ${app.packageName}")
        if (launching) return null
        launching = true
        return launchResultDialog(apkLauncher.launch(app.packageName))
    }

    fun resumeRom(rom: Rom): DialogState? {
        debugLog("resumeRom entered: ${rom.platformTag} / ${rom.path.name}")
        if (launching) return null
        launching = true
        val resumeSlot = findMostRecentSlot(rom) ?: 0
        val embeddedCorePath = getEmbeddedCorePath(rom)
        if (embeddedCorePath != null) {
            val launchFile = resolveLaunchFile(rom, extractArchives = true) ?: run { launching = false; return null }
            return launchEmbedded(rom.copy(path = launchFile), embeddedCorePath, resumeSlot, originalRomPath = rom.path.absolutePath)
        }
        val gameOverride = platformConfig.getGameOverride(rom.path.absolutePath)
        val core = gameOverride?.coreId ?: platformConfig.getCoreName(rom.platformTag) ?: run { launching = false; return null }
        val raPackage = gameOverride?.raPackage ?: platformConfig.getPackage(rom.platformTag) ?: settings.retroArchPackage
        val launchFile = resolveLaunchFile(rom, extractArchives = false) ?: run { launching = false; return null }
        if (settings.retroArchDiyMode) {
            val raConfig = "/storage/emulated/0/Android/data/$raPackage/files/retroarch.cfg"
            retroArchLauncher.launch(launchFile, core, raConfig, raPackage)
        } else {
            syncRetroArchConfig(File(settings.sdCardRoot))
            val launchConfig = buildGameConfig(rom, resume = true, slot = resumeSlot) ?: raConfigPath
            retroArchLauncher.launch(launchFile, core, launchConfig, raPackage)
        }
        return null
    }

    private fun errorAndReset(dialog: DialogState): DialogState {
        launching = false
        return dialog
    }

    private fun launchResultDialog(result: LaunchResult): DialogState? {
        val dialog = toLaunchDialog(result)
        if (dialog != null) launching = false
        return dialog
    }

    private fun toLaunchDialog(result: LaunchResult): DialogState? {
        return when (result) {
            is LaunchResult.CoreNotInstalled -> DialogState.MissingCore(result.coreName)
            is LaunchResult.AppNotInstalled -> {
                val appName = try {
                    val info = context.packageManager.getApplicationInfo(result.packageName, 0)
                    context.packageManager.getApplicationLabel(info).toString()
                } catch (_: PackageManager.NameNotFoundException) {
                    result.packageName
                }
                DialogState.MissingApp(appName, result.packageName)
            }
            is LaunchResult.Error -> DialogState.LaunchError(result.message)
            LaunchResult.Success -> null
        }
    }

    private fun debugLog(message: String) {
        if (!dev.karipap.app.util.LoggingPrefs.session) return
        try {
            val dir = CannoliPaths(settings.sdCardRoot).logsDir
            dir.mkdirs()
            val f = File(dir, "launch_debug.log")
            f.appendText("${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())} $message\n")
        } catch (_: Exception) {}
    }

    fun launchEmbedded(rom: Rom, corePath: String, resumeSlot: Int = -1, originalRomPath: String? = null): DialogState? {
        val paths = CannoliPaths(settings.sdCardRoot)
        val romName = normalizedRomName(rom)
        val saveDir = paths.savesFor(rom.platformTag)
        saveDir.mkdirs()
        val stateDir = paths.saveStateDir(rom.platformTag, romName)
        stateDir.mkdirs()
        val coreId = File(corePath).name.removeSuffix("_android.so")
        val systemDir = prepareLibretroSystemDir(paths, coreId)
        validateEmbeddedFirmware(rom, coreId, systemDir)?.let { return errorAndReset(it) }

        val args = LaunchArgs(
            gameTitle = rom.displayName,
            corePath = corePath,
            romPath = rom.path.absolutePath,
            originalRomPath = originalRomPath?.takeIf { it != rom.path.absolutePath },
            sramPath = File(saveDir, "$romName.srm").absolutePath,
            statePath = File(stateDir, "$romName.state").absolutePath,
            systemDir = systemDir.absolutePath,
            saveDir = saveDir.absolutePath,
            platformTag = rom.platformTag,
            platformName = platformConfig.getDisplayName(rom.platformTag),
            cannoliRoot = paths.root.absolutePath,
            colorHighlight = settings.colorHighlight,
            colorText = settings.colorText,
            colorHighlightText = settings.colorHighlightText,
            colorAccent = settings.colorAccent,
            colorTitle = settings.colorTitle,
            colorBackground = settings.colorBackground,
            colorStatusBar = settings.colorStatusBar,
            font = settings.font,
            debugLogging = settings.loggingSession,
            raUsername = settings.raUsername,
            raToken = settings.raToken,
            raPassword = settings.raPassword,
            raGameId = rom.raGameId,
            resumeSlot = resumeSlot,
        )
        val intent = args.writeTo(Intent(context, LibretroActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opts = ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle()
        context.startActivity(intent, opts)
        return null
    }

    private fun validateEmbeddedFirmware(rom: Rom, coreId: String, systemDir: File): DialogState? {
        if (rom.platformTag.uppercase() != "SEGACD") return null
        if (coreId != "genesis_plus_gx_libretro" && coreId != "picodrive_libretro") return null

        val biosName = segaCdBiosNameFor(rom.path.name)
        val biosFile = File(systemDir, biosName)
        if (!biosFile.isFile) {
            return DialogState.LaunchError("Missing Sega CD BIOS: $biosName. Put a valid BIOS in the BIOS Directory and refresh/launch again.")
        }

        val hash = md5Hex(biosFile) ?: return DialogState.LaunchError("Could not read Sega CD BIOS: $biosName")
        val allowed = SEGACD_BIOS_MD5[biosName.lowercase()].orEmpty()
        if (allowed.isNotEmpty() && hash !in allowed) {
            return DialogState.LaunchError(
                "Invalid Sega CD BIOS: $biosName\nFound MD5: $hash\nExpected MD5: ${allowed.joinToString(" or ")}"
            )
        }
        return null
    }

    private fun segaCdBiosNameFor(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            "(japan" in lower || "(jp" in lower || "ntsc-j" in lower -> "bios_CD_J.bin"
            "(europe" in lower || "(eu" in lower || "(pal" in lower -> "bios_CD_E.bin"
            else -> "bios_CD_U.bin"
        }
    }

    private fun md5Hex(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun normalizedRomName(rom: Rom): String {
        val raw = Normalizer.normalize(rom.path.nameWithoutExtension, Normalizer.Form.NFC)
        return if (rom.discFiles != null) DISC_REGEX.replace(raw, "").trim() else raw
    }

    private fun buildIGMExtras(rom: Rom): IGMExtras {
        val paths = CannoliPaths(settings.sdCardRoot)
        val romName = normalizedRomName(rom)
        val stateBase = paths.saveStateBase(rom.platformTag, romName)
        return IGMExtras(
            gameTitle = rom.displayName,
            stateBasePath = stateBase.absolutePath,
            cannoliRoot = paths.root.absolutePath,
            platformTag = rom.platformTag,
            colorHighlight = settings.colorHighlight,
            colorText = settings.colorText,
            colorHighlightText = settings.colorHighlightText,
            colorAccent = settings.colorAccent,
            colorTitle = settings.colorTitle
        )
    }

    companion object {
        private const val CONFIG_VERSION = 5
        private val DISC_REGEX = Regex("""\s*\((Disc|Disk)\s*\d+\)|\s*\(CD\d+\)""", RegexOption.IGNORE_CASE)
        private val SEGACD_BIOS_MD5 = mapOf(
            "bios_cd_e.bin" to setOf("e66fa1dc5820d254611fdcdba0662372"),
            "bios_cd_u.bin" to setOf("854b9150240a198070150e4566ae1290", "2efd74e3232ff260e371b99f84024f7f"),
            "bios_cd_j.bin" to setOf("278a9397d192149e84e820ac621a8edd"),
        )

        fun extractBundledCores(context: Context): String {
            val coresDir = File(context.filesDir, "cores")
            coresDir.mkdirs()
            val versionFile = File(coresDir, ".version")
            val currentVersion = File(context.applicationInfo.sourceDir).lastModified().toString()
            if (versionFile.exists() && versionFile.readText() == currentVersion) return coresDir.absolutePath
            val extracted = mutableSetOf<String>()
            java.util.zip.ZipFile(context.applicationInfo.sourceDir).use { apkZip ->
                val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
                val prefix = "lib/$abi/"
                for (entry in apkZip.entries()) {
                    if (!entry.name.startsWith(prefix) || !entry.name.endsWith("_libretro_android.so")) continue
                    val name = entry.name.removePrefix(prefix)
                    val dst = File(coresDir, name)
                    apkZip.getInputStream(entry).use { inp -> dst.outputStream().use { inp.copyTo(it) } }
                    extracted.add(name)
                }
            }
            coresDir.listFiles()?.forEach { f ->
                if (f.name.endsWith("_libretro_android.so") && f.name !in extracted) f.delete()
            }
            versionFile.writeText(currentVersion)
            return coresDir.absolutePath
        }
    }
}
