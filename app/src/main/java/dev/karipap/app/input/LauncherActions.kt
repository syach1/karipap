package dev.karipap.app.input

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.karipap.app.artwork.ArtworkScraper
import dev.karipap.app.artwork.ArtworkScraperPlatform
import dev.karipap.app.artwork.ArtworkScraperSource
import dev.karipap.app.config.PlatformConfig
import dev.karipap.app.db.AppsRepository
import dev.karipap.app.db.CollectionsRepository
import dev.karipap.app.db.LibraryRef
import dev.karipap.app.db.RecentlyPlayedRepository
import dev.karipap.app.db.RomsRepository
import dev.karipap.app.di.IoScope
import dev.karipap.app.launcher.LaunchManager
import dev.karipap.app.model.AppType
import dev.karipap.app.model.CollectionType
import dev.karipap.app.model.ListItem
import dev.karipap.app.navigation.NavigationController
import dev.karipap.app.server.KitchenManager
import dev.karipap.app.settings.ContentMode
import dev.karipap.app.settings.SettingsRepository
import dev.karipap.app.ui.screens.DialogState
import dev.karipap.app.ui.viewmodel.GameListViewModel
import dev.karipap.app.ui.viewmodel.SettingsViewModel
import dev.karipap.app.ui.viewmodel.SystemListViewModel
import dev.karipap.app.util.ArcadeTitleLookup
import dev.karipap.app.util.ArtworkLookup
import dev.cannoli.ui.components.COLOR_GRID_COLS
import dev.cannoli.ui.theme.COLOR_PRESETS
import dev.cannoli.ui.theme.colorToArgbLong
import dev.cannoli.ui.theme.hexToColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@ActivityScoped
class LauncherActions @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoScope private val ioScope: CoroutineScope,
    private val settings: SettingsRepository,
    private val collectionsRepository: CollectionsRepository,
    private val recentlyPlayedRepository: RecentlyPlayedRepository,
    private val romsRepository: RomsRepository,
    private val appsRepository: AppsRepository,
    private val launchManager: LaunchManager,
    private val platformConfig: PlatformConfig,
    private val artworkLookup: ArtworkLookup,
    private val artworkScraper: ArtworkScraper,
    private val arcadeTitleLookup: ArcadeTitleLookup,
    private val nav: NavigationController,
    private val systemListViewModel: SystemListViewModel,
    private val gameListViewModel: GameListViewModel,
    private val settingsViewModel: SettingsViewModel,
) {

    fun rescanSystemList(
        scanDisk: Boolean = true,
        onProgress: ((String, Int, Int) -> Unit)? = null,
        onComplete: (() -> Unit)? = null,
    ) {
        val fghId = validateFghCollection()
        gameListViewModel.showFavoriteStars = settings.contentMode != ContentMode.FIVE_GAME_HANDHELD
        systemListViewModel.scan(
            showRecentlyPlayed = settings.showRecentlyPlayed,
            contentMode = settings.contentMode,
            fghCollectionId = fghId,
            toolsName = settings.toolsName,
            portsName = settings.portsName,
            scanDisk = scanDisk,
            onProgress = onProgress,
            onReady = {
                onComplete?.invoke()
                if (fghId != null) scanResumableGames()
            }
        )
    }

    fun validateFghCollection(): Long? {
        if (settings.contentMode != ContentMode.FIVE_GAME_HANDHELD) return null
        val all = collectionsRepository.all().filter { it.type == CollectionType.STANDARD }
        val current = settings.fghCollectionId
        if (current != null && all.any { it.id == current }) return current
        val fallback = all.firstOrNull()?.id
        settings.fghCollectionId = fallback
        return fallback
    }

    fun scanResumableGames() {
        val gameListRoms = gameListViewModel.state.value.items
            .filterIsInstance<ListItem.RomItem>()
            .map { it.rom }
        val systemListRoms = systemListViewModel.state.value.items
            .filterIsInstance<SystemListViewModel.ListItem.GameItem>()
            .mapNotNull { (it.item as? ListItem.RomItem)?.rom }
        val roms = (gameListRoms + systemListRoms).distinctBy { it.path.absolutePath }
        ioScope.launch {
            val result = launchManager.findResumableRoms(roms)
            withContext(Dispatchers.Main) { nav.resumableGames = result }
        }
    }

    fun invalidateAllLibraryCaches() {
        artworkLookup.invalidateAll()
        arcadeTitleLookup.invalidateAll()
        romsRepository.invalidateGamelistCache("*")
    }

    fun openArtworkScraper() {
        nav.push(dev.karipap.app.navigation.LauncherScreen.ArtworkScraperPlatforms(loading = true))
        rescanSystemList(scanDisk = true, onComplete = { refreshArtworkScraperPlatforms() })
    }

    fun runArtworkScraper(platform: ArtworkScraperPlatform, source: ArtworkScraperSource) {
        nav.dialogState.value = DialogState.RALoggingIn(message = "Scraping ${platform.name}...")
        ioScope.launch {
            val result = withContext(Dispatchers.IO) {
                artworkScraper.scrape(platform, source) { done, total ->
                    withContext(Dispatchers.Main) {
                        nav.dialogState.value = DialogState.RALoggingIn(
                            message = "Scraping ${platform.name} $done/$total..."
                        )
                    }
                }
            }
            withContext(Dispatchers.Main) {
                invalidateAllLibraryCaches()
                rescanSystemList(scanDisk = false)
                refreshArtworkScraperPlatforms()
                nav.dialogState.value = DialogState.IntentAuditResult(result.message)
            }
        }
    }

    private fun refreshArtworkScraperPlatforms() {
        ioScope.launch {
            val platforms = withContext(Dispatchers.IO) { artworkScraper.platformsWithRoms() }
            withContext(Dispatchers.Main) {
                val screen = nav.currentScreen as? dev.karipap.app.navigation.LauncherScreen.ArtworkScraperPlatforms
                    ?: (nav.screenStack.lastOrNull {
                        it is dev.karipap.app.navigation.LauncherScreen.ArtworkScraperPlatforms
                    } as? dev.karipap.app.navigation.LauncherScreen.ArtworkScraperPlatforms)
                    ?: return@withContext
                val idx = screen.selectedIndex.coerceIn(0, (platforms.size - 1).coerceAtLeast(0))
                val updated = screen.copy(platforms = platforms, loading = false, selectedIndex = idx, scrollTarget = idx)
                val stackIndex = nav.screenStack.indexOfLast {
                    it is dev.karipap.app.navigation.LauncherScreen.ArtworkScraperPlatforms
                }
                if (stackIndex >= 0) nav.screenStack[stackIndex] = updated
            }
        }
    }

    fun launchSelected(item: ListItem, resume: Boolean): DialogState? = when (item) {
        is ListItem.RomItem ->
            if (resume) launchManager.resumeRom(item.rom) else launchManager.launchRom(item.rom)
        is ListItem.AppItem -> launchManager.launchApp(item.app)
        else -> null
    }

    fun recordRecentlyPlayedByPath(path: String) {
        ioScope.launch {
            resolvePathToRef(path)?.let { recentlyPlayedRepository.record(it) }
        }
    }

    fun openColorPicker(settingKey: String) {
        val hex = settingsViewModel.getColorHex(settingKey)
        val color = hexToColor(hex) ?: androidx.compose.ui.graphics.Color.White
        val argb = colorToArgbLong(color)
        val idx = COLOR_PRESETS.indexOfFirst { it.color == argb }
        val row = if (idx >= 0) idx / COLOR_GRID_COLS else 0
        val col = if (idx >= 0) idx % COLOR_GRID_COLS else 0
        nav.dialogState.value = DialogState.ColorPicker(
            settingKey = settingKey,
            title = colorSettingTitle(settingKey),
            currentColor = argb,
            selectedRow = row,
            selectedCol = col
        )
    }

    fun openKitchen() {
        val km = KitchenManager
        val sdRoot = File(settings.sdCardRoot)
        val romsRootProvider = {
            settings.romDirectory.takeIf { it.isNotEmpty() }?.let { File(it) } ?: File(sdRoot, "Roms")
        }
        val biosRootProvider = {
            settings.biosDirectory.takeIf { it.isNotEmpty() }?.let { File(it) } ?: File(sdRoot, "BIOS")
        }
        if (!km.isRunning) km.toggle(sdRoot, context.assets, settings.kitchenCodeBypass, romsRootProvider, biosRootProvider)
        else km.setCodeBypass(settings.kitchenCodeBypass)
        nav.dialogState.value = DialogState.Kitchen(
            urls = km.getUrls(hasActiveVpn()),
            pin = km.pin,
            requirePin = !settings.kitchenCodeBypass
        )
    }

    fun handleSystemListRename(state: DialogState.RenameInput) {
        val newName = state.currentName.trim()
        if (newName.isEmpty() || newName == state.gameName) {
            nav.dialogState.value = DialogState.None
            return
        }
        val item = systemListViewModel.getSelectedItem()
        when (item) {
            is SystemListViewModel.ListItem.PlatformItem -> {
                ioScope.launch {
                    platformConfig.setDisplayName(item.platform.tag, newName)
                    rescanSystemList()
                }
            }
            is SystemListViewModel.ListItem.ToolsFolder -> {
                settings.toolsName = newName
                rescanSystemList()
            }
            is SystemListViewModel.ListItem.PortsFolder -> {
                settings.portsName = newName
                rescanSystemList()
            }
            is SystemListViewModel.ListItem.CollectionItem -> {
                ioScope.launch {
                    val id = collectionsRepository.all().firstOrNull { it.displayName == item.name }?.id
                    if (id != null) collectionsRepository.rename(id, newName)
                    rescanSystemList()
                }
            }
            else -> {}
        }
        nav.dialogState.value = DialogState.None
    }

    private fun resolvePathToRef(path: String): LibraryRef? {
        return if (path.startsWith("/apps/")) {
            val parts = path.removePrefix("/apps/").split("/", limit = 2)
            if (parts.size == 2) {
                val type = runCatching { AppType.valueOf(parts[0]) }.getOrNull()
                type?.let { appsRepository.byPackage(it, parts[1]) }?.let { LibraryRef.App(it.id) }
            } else null
        } else {
            romsRepository.gameByPath(path)?.let { LibraryRef.Rom(it.id) }
        }
    }

    private fun hasActiveVpn(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun colorSettingTitle(settingKey: String): String {
        val labelRes = when (settingKey) {
            "color_accent" -> dev.karipap.app.R.string.setting_color_accent
            "color_highlight" -> dev.karipap.app.R.string.setting_color_highlight
            "color_highlight_text" -> dev.karipap.app.R.string.setting_color_highlight_text
            "color_text" -> dev.karipap.app.R.string.setting_color_text
            "color_title" -> dev.karipap.app.R.string.setting_color_title
            else -> return ""
        }
        return context.getString(labelRes)
    }
}
