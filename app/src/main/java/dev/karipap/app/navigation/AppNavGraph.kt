package dev.karipap.app.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.igm.ShortcutAction
import dev.karipap.app.R
import dev.karipap.app.artwork.ArtworkScraperPlatform
import dev.karipap.app.artwork.ArtworkScraperSource
import dev.karipap.app.input.runtime.confirmButton
import dev.karipap.app.input.runtime.labelSet
import dev.karipap.app.ui.LocalPortraitMargin
import dev.karipap.app.util.keyCodeName
import dev.karipap.app.ui.PortraitMarginState
import dev.karipap.app.ui.components.CREDITS
import dev.karipap.app.ui.components.CreditsOverlay
import dev.karipap.app.ui.components.DialogOverlay
import dev.karipap.app.ui.components.ListDialogScreen
import dev.karipap.app.ui.effectivePortraitMarginDp
import dev.karipap.app.ui.screens.ColorEntry
import dev.karipap.app.ui.screens.ControllerDetailScreen
import dev.karipap.app.ui.screens.ControllersScreen
import dev.karipap.app.ui.screens.EditButtonsScreen
import dev.karipap.app.ui.screens.CoreMappingEntry
import dev.karipap.app.ui.screens.CorePickerOption
import dev.karipap.app.ui.screens.DialogState
import dev.karipap.app.ui.screens.DirectoryBrowserScreen
import dev.karipap.app.ui.screens.GameListScreen
import dev.karipap.app.ui.screens.InputTesterScreen
import dev.karipap.app.ui.screens.LoggingSettingsScreen
import dev.karipap.app.ui.screens.KeyboardInputState
import dev.karipap.app.ui.screens.PortraitMarginOverlay
import dev.karipap.app.ui.screens.SettingsScreen
import dev.karipap.app.ui.screens.SystemListScreen
import dev.karipap.app.ui.screens.isFullScreen
import dev.karipap.app.ui.viewmodel.ControllersViewModel
import dev.karipap.app.ui.viewmodel.GameListViewModel
import dev.karipap.app.ui.viewmodel.InputTesterViewModel
import dev.karipap.app.ui.viewmodel.SettingsViewModel
import dev.karipap.app.ui.viewmodel.SystemListViewModel
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.LocalStatusBarLeftEdge
import dev.cannoli.ui.components.MessageOverlay
import dev.cannoli.ui.components.OsdHost
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.components.PillRowText
import dev.cannoli.ui.components.StatusBar
import dev.cannoli.ui.components.pillItemHeight
import dev.cannoli.ui.components.screenPadding
import dev.cannoli.ui.theme.CannoliColors
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliFont
import dev.cannoli.ui.theme.LocalCannoliTypography
import dev.cannoli.ui.theme.LocalScaleFactor
import dev.cannoli.ui.theme.Radius
import dev.cannoli.ui.theme.Spacing
import dev.cannoli.ui.theme.buildCannoliTypography
import kotlinx.coroutines.flow.StateFlow

enum class BrowsePurpose { SD_ROOT, ROM_DIRECTORY, BIOS_DIRECTORY, SETUP, SETUP_ROM_DIRECTORY, SETUP_BIOS_DIRECTORY }

enum class OnboardingPermission { STORAGE }

sealed class LauncherScreen {
    interface ScrollableScreen {
        val selectedIndex: Int
        val scrollTarget: Int
        val itemCount: Int
        fun withScroll(selectedIndex: Int, scrollTarget: Int): LauncherScreen
    }

    data object SystemList : LauncherScreen()
    data object GameList : LauncherScreen()
    data object Settings : LauncherScreen()
    data object InputTester : LauncherScreen()
    data class CoreMapping(val mappings: List<CoreMappingEntry>, val allMappings: List<CoreMappingEntry> = mappings, override val selectedIndex: Int = 0, override val scrollTarget: Int = 0, val filter: Int = 0) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = mappings.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class CorePicker(val tag: String, val platformName: String, val cores: List<CorePickerOption>, override val selectedIndex: Int = 0, val gamePath: String? = null, override val scrollTarget: Int = 0, val activeIndex: Int = 0) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = cores.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class ColorList(val colors: List<ColorEntry>, override val selectedIndex: Int = 0, override val scrollTarget: Int = 0) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = colors.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class CollectionPicker(val gamePaths: List<String>, val title: String, val collectionIds: List<Long>, val displayNames: List<String> = emptyList(), override val selectedIndex: Int = 0, val checkedIndices: Set<Int> = emptySet(), val initialChecked: Set<Int> = emptySet(), override val scrollTarget: Int = 0) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = collectionIds.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class AppPicker(val type: String, val title: String, val apps: List<String>, val packages: List<String>, override val selectedIndex: Int = 0, val checkedIndices: Set<Int> = emptySet(), val initialChecked: Set<Int> = emptySet(), override val scrollTarget: Int = 0) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = apps.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class ChildPicker(val parentId: Long, val collectionIds: List<Long>, val displayNames: List<String> = emptyList(), override val selectedIndex: Int = 0, val checkedIndices: Set<Int> = emptySet(), val initialChecked: Set<Int> = emptySet(), override val scrollTarget: Int = 0) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = collectionIds.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class Controllers(
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = 0
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class ControllerDetail(
        val mappingId: String,
        val androidDeviceId: Int? = null,
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = 5
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class EditButtons(
        val mappingId: String,
        val listeningCanonical: dev.karipap.app.input.CanonicalButton? = null,
        val countdownMs: Int = 0,
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = dev.karipap.app.input.CanonicalButton.entries.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class LoggingSettings(
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = dev.karipap.app.util.LoggingPrefs.Category.entries.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class ShortcutBinding(override val selectedIndex: Int = 0, override val scrollTarget: Int = 0, val shortcuts: Map<ShortcutAction, Set<Int>> = emptyMap(), val listening: Boolean = false, val heldKeys: Set<Int> = emptySet(), val countdownMs: Int = 0) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = ShortcutAction.entries.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class Credits(override val selectedIndex: Int = 0, override val scrollTarget: Int = 0) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = CREDITS.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class InstalledCores(val cores: List<String> = emptyList(), val loading: Boolean = true, override val selectedIndex: Int = 0, override val scrollTarget: Int = 0, val title: String? = null) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = cores.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class ArtworkScraperPlatforms(
        val platforms: List<ArtworkScraperPlatform> = emptyList(),
        val loading: Boolean = true,
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = platforms.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class ArtworkScraperSources(
        val platform: ArtworkScraperPlatform,
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = ArtworkScraperSource.entries.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class DirectoryBrowser(
        val purpose: BrowsePurpose,
        val currentPath: String,
        val entries: List<String> = emptyList(),
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = entries.size + if (currentPath != "/storage/") 1 else 0
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class OnboardingPermissions(
        val permissions: List<OnboardingPermission>,
        val granted: Set<OnboardingPermission>,
        val volumes: List<Pair<String, String>> = emptyList(),
        val volumeIndex: Int = 0,
        val customPath: String? = null,
        val romDirectory: String? = null,
        val biosDirectory: String? = null,
        val selectedIndex: Int = 0,
    ) : LauncherScreen() {
        val allGranted: Boolean get() = granted.containsAll(permissions)
        val storageRowIndex: Int get() = permissions.size
        val romRowIndex: Int get() = storageRowIndex + 1
        val biosRowIndex: Int get() = storageRowIndex + 2
        val focusableCount: Int get() = permissions.size + if (allGranted) 3 else 0
        val isStorageRowFocused: Boolean get() = allGranted && selectedIndex == storageRowIndex
        val isRomRowFocused: Boolean get() = allGranted && selectedIndex == romRowIndex
        val isBiosRowFocused: Boolean get() = allGranted && selectedIndex == biosRowIndex
        val focusedPermission: OnboardingPermission?
            get() = if (selectedIndex in permissions.indices) permissions[selectedIndex] else null
        val isFocusedGranted: Boolean get() = focusedPermission?.let { it in granted } ?: false
        val selectedVolume: Pair<String, String>? get() = volumes.getOrNull(volumeIndex)
        val isCustomVolume: Boolean get() = selectedVolume?.first == "Custom"
        val continueEnabled: Boolean
            get() = allGranted && volumes.isNotEmpty() && (!isCustomVolume || customPath != null)
        val targetPath: String?
            get() {
                if (!continueEnabled) return null
                return if (isCustomVolume) customPath else selectedVolume!!.second + "Karipap/"
            }
        val effectiveRomDirectory: String?
            get() = romDirectory ?: targetPath?.let { it.trimEnd('/') + "/Roms" }
        val effectiveBiosDirectory: String?
            get() = biosDirectory ?: targetPath?.let { it.trimEnd('/') + "/BIOS" }
        fun moved(delta: Int) = copy(
            selectedIndex = (selectedIndex + delta).coerceIn(0, (focusableCount - 1).coerceAtLeast(0))
        )
        fun cycledVolume(delta: Int): OnboardingPermissions {
            if (volumes.size <= 1) return this
            val next = ((volumeIndex + delta) % volumes.size + volumes.size) % volumes.size
            return copy(volumeIndex = next, customPath = null)
        }
    }
}

@Composable
fun AppNavGraph(
    currentScreen: LauncherScreen,
    systemListViewModel: SystemListViewModel? = null,
    gameListViewModel: GameListViewModel? = null,
    inputTesterViewModel: InputTesterViewModel,
    onExitInputTester: () -> Unit = {},
    settingsViewModel: SettingsViewModel,
    controllersViewModel: ControllersViewModel,
    dialogState: StateFlow<DialogState>,
    onListStateChanged: ((androidx.compose.foundation.lazy.LazyListState?) -> Unit)? = null,
    resumableGames: Set<String> = emptySet(),
    updateAvailable: Boolean = false,
    downloadProgress: Float = 0f,
    downloadError: String? = null,
    osdController: dev.cannoli.ui.components.OsdController,
    activeMapping: dev.karipap.app.input.DeviceMapping? = null,
    mappingRepository: dev.karipap.app.input.repo.MappingRepository? = null,
    editButtonsController: dev.karipap.app.input.EditButtonsController? = null,
    nav: dev.karipap.app.navigation.NavigationController? = null,
    inputRouter: dev.karipap.app.input.InputRouter? = null,
) {
    val dialog by dialogState.collectAsState()
    val appSettings by settingsViewModel.appSettings.collectAsState()

    val listFontSize = appSettings.textSize.sp.sp
    val listLineHeight = (appSettings.textSize.sp + 10).sp
    val listVerticalPadding = 6.dp

    val labels = dev.cannoli.ui.ButtonStyle(
        activeMapping.labelSet(dev.cannoli.ui.ButtonLabelSet.PLUMBER),
        activeMapping.confirmButton(),
    )

    val cannoliColors = CannoliColors(
        highlight = appSettings.colorHighlight,
        text = appSettings.colorText,
        highlightText = appSettings.colorHighlightText,
        accent = appSettings.colorAccent,
        title = appSettings.colorTitle,
        background = appSettings.colorBackground,
        statusBar = appSettings.colorStatusBar
    )

    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)
    val statusBarLeftEdge = remember { mutableIntStateOf(Int.MAX_VALUE) }

    val scaleFactor = appSettings.textSize.sp / 22f
    val cannoliTypography = buildCannoliTypography(baseSizeSp = appSettings.textSize.sp, fontFamily = LocalCannoliFont.current)

    val portraitMarginState = PortraitMarginState(marginPx = appSettings.portraitMarginPx)
    CompositionLocalProvider(
        LocalCannoliColors provides cannoliColors,
        LocalStatusBarLeftEdge provides statusBarLeftEdge,
        LocalScaleFactor provides scaleFactor,
        LocalCannoliTypography provides cannoliTypography,
        LocalPortraitMargin provides portraitMarginState
    ) {
    Box(modifier = Modifier.fillMaxSize().displayCutoutPadding()) {
    Box(modifier = Modifier.fillMaxSize().padding(bottom = effectivePortraitMarginDp())) {
        when (currentScreen) {
            is LauncherScreen.SystemList -> {
                if (systemListViewModel == null) return@Box
                inputRouter?.let { dev.karipap.app.input.screen.compose.ScreenInput(it.systemListHandler) }
                SystemListScreen(
                    viewModel = systemListViewModel,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    dialogState = dialog,
                    onListStateChanged = onListStateChanged,
                    kitchenRunning = dev.karipap.app.server.KitchenManager.isRunning,
                    title = appSettings.title,
                    mainMenuQuit = appSettings.mainMenuQuit,
                    artWidth = appSettings.artWidth,
                    artScale = appSettings.artScale,
                    resumableGames = resumableGames,
                    swapPlayResume = appSettings.swapPlayResume,
                    fiveGameHandheld = appSettings.contentMode == dev.karipap.app.settings.ContentMode.FIVE_GAME_HANDHELD,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.GameList -> {
                if (gameListViewModel == null) return@Box
                inputRouter?.let { dev.karipap.app.input.screen.compose.ScreenInput(it.gameListHandler) }
                GameListScreen(
                    viewModel = gameListViewModel,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    dialogState = dialog,
                    onListStateChanged = onListStateChanged,
                    resumableGames = resumableGames,
                    swapPlayResume = appSettings.swapPlayResume,
                    artWidth = appSettings.artWidth,
                    artScale = appSettings.artScale,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.InputTester -> {
                inputRouter?.let { dev.karipap.app.input.screen.compose.ScreenInput(it.inputTesterHandler) }
                InputTesterScreen(
                    viewModel = inputTesterViewModel,
                    buttonStyle = labels,
                    onExit = onExitInputTester,
                )
            }
            is LauncherScreen.Settings -> {
                inputRouter?.let { dev.karipap.app.input.screen.compose.ScreenInput(it.settingsHandler) }
                SettingsScreen(
                viewModel = settingsViewModel,
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                listVerticalPadding = listVerticalPadding,
                dialogState = dialog,
                downloadProgress = downloadProgress,
                downloadError = downloadError,
                updateAvailable = updateAvailable,
                onListStateChanged = onListStateChanged,
                buttonStyle = labels,
            )
            }
            is LauncherScreen.CoreMapping -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.karipap.app.input.screen.compose.ScreenInput(handler)
                }
                val filterLabel = when (currentScreen.filter) {
                    1 -> "MISSING"
                    2 -> "INTERNAL"
                    3 -> "EXTERNAL"
                    else -> "ALL"
                }
                val selected = currentScreen.mappings.getOrNull(currentScreen.selectedIndex)
                val canSelect = selected != null
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = stringResource(R.string.setting_core_mapping),
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = buildList {
                        if (canSelect) add(labels.confirm to stringResource(R.string.label_select))
                        add(labels.west to filterLabel)
                    },
                    buttonStyle = labels
                ) {
                    List(
                        items = currentScreen.mappings,
                        selectedIndex = currentScreen.selectedIndex,
                        itemHeight = itemHeight,
                        scrollTarget = currentScreen.scrollTarget,
                        onListStateChanged = onListStateChanged
                    ) { _, entry, isSelected ->
                        val value = if (currentScreen.filter == 0 && entry.runnerLabel.isNotEmpty())
                            "${entry.coreDisplayName} (${entry.runnerLabel})"
                        else entry.coreDisplayName
                        PillRowKeyValue(
                            label = entry.platformName,
                            value = value,
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                    }
                }
            }
            is LauncherScreen.CorePicker -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.karipap.app.input.screen.compose.ScreenInput(handler)
                }
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = currentScreen.platformName,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = listOf(labels.confirm to stringResource(R.string.label_select)),
                    buttonStyle = labels
                ) {
                    if (currentScreen.cores.isEmpty()) {
                        Text(
                            text = stringResource(R.string.no_compatible_cores),
                            style = MaterialTheme.typography.bodyLarge,
                            color = cannoliColors.text.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 14.dp)
                        )
                    } else {
                        List(
                            items = currentScreen.cores,
                            selectedIndex = currentScreen.selectedIndex,
                            itemHeight = itemHeight,
                            scrollTarget = currentScreen.scrollTarget,
                            onListStateChanged = onListStateChanged
                        ) { index, option, isSelected ->
                            val label = if (option.runnerLabel.isEmpty()) option.displayName
                                else "${option.displayName} (${option.runnerLabel})"
                            if (index == currentScreen.activeIndex) {
                                PillRowKeyValue(
                                    label = label,
                                    value = stringResource(R.string.value_current),
                                    isSelected = isSelected,
                                    fontSize = listFontSize,
                                    lineHeight = listLineHeight,
                                    verticalPadding = listVerticalPadding
                                )
                            } else {
                                PillRowText(
                                    label = label,
                                    isSelected = isSelected,
                                    fontSize = listFontSize,
                                    lineHeight = listLineHeight,
                                    verticalPadding = listVerticalPadding
                                )
                            }
                        }
                    }
                }
            }
            is LauncherScreen.ColorList -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.karipap.app.input.screen.compose.ScreenInput(handler)
                }
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = stringResource(R.string.setting_colors),
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = listOf(labels.confirm to stringResource(R.string.label_select)),
                    buttonStyle = labels
                ) {
                    List(
                        items = currentScreen.colors,
                        selectedIndex = currentScreen.selectedIndex,
                        itemHeight = itemHeight,
                        scrollTarget = currentScreen.scrollTarget,
                        onListStateChanged = onListStateChanged
                    ) { _, entry, isSelected ->
                        PillRowKeyValue(
                            label = stringResource(entry.labelRes),
                            value = entry.hex.uppercase(),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                            swatchColor = Color(entry.color.toInt())
                        )
                    }
                }
                if (dialog.isFullScreen) {
                    DialogOverlay(
                        dialogState = dialog,
                        backgroundImagePath = appSettings.backgroundImagePath,
                        backgroundTint = appSettings.backgroundTint,
                        listFontSize = listFontSize,
                        listLineHeight = listLineHeight,
                        listVerticalPadding = listVerticalPadding,
                        buttonStyle = labels
                    )
                }
            }
            is LauncherScreen.CollectionPicker -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.karipap.app.input.screen.compose.ScreenInput(handler)
                }
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = currentScreen.title,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    leftBottomItems = listOf(
                        labels.west to stringResource(R.string.label_new)
                    ),
                    rightBottomItems = emptyList(),
                    buttonStyle = labels
                ) {
                    if (currentScreen.collectionIds.isEmpty()) {
                        Text(
                            text = stringResource(R.string.no_collections),
                            style = MaterialTheme.typography.bodyLarge,
                            color = cannoliColors.text.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 14.dp)
                        )
                    } else {
                        List(
                            items = currentScreen.collectionIds,
                            selectedIndex = currentScreen.selectedIndex,
                            itemHeight = itemHeight,
                            scrollTarget = currentScreen.scrollTarget,
                            onListStateChanged = onListStateChanged
                        ) { index, _, isSelected ->
                            PillRowText(
                                label = currentScreen.displayNames.getOrElse(index) { "" },
                                isSelected = isSelected,
                                fontSize = listFontSize,
                                lineHeight = listLineHeight,
                                verticalPadding = listVerticalPadding,
                                checkState = index in currentScreen.checkedIndices
                            )
                        }
                    }
                }
                if (dialog.isFullScreen) {
                    DialogOverlay(
                        dialogState = dialog,
                        backgroundImagePath = appSettings.backgroundImagePath,
                        backgroundTint = appSettings.backgroundTint,
                        listFontSize = listFontSize,
                        listLineHeight = listLineHeight,
                        listVerticalPadding = listVerticalPadding,
                        buttonStyle = labels
                    )
                } else {
                    val d = dialog
                    if (d is DialogState.CollectionCreated) {
                        MessageOverlay(message = stringResource(R.string.collection_created, d.collectionName))
                    }
                }
            }
            is LauncherScreen.ChildPicker -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.karipap.app.input.screen.compose.ScreenInput(handler)
                }
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = stringResource(R.string.title_child_collections),
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    rightBottomItems = emptyList(),
                    buttonStyle = labels
                ) {
                    if (currentScreen.collectionIds.isEmpty()) {
                        Text(
                            text = stringResource(R.string.no_collections),
                            style = MaterialTheme.typography.bodyLarge,
                            color = cannoliColors.text.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 14.dp)
                        )
                    } else {
                        List(
                            items = currentScreen.collectionIds,
                            selectedIndex = currentScreen.selectedIndex,
                            itemHeight = itemHeight,
                            scrollTarget = currentScreen.scrollTarget,
                            onListStateChanged = onListStateChanged
                        ) { index, _, isSelected ->
                            PillRowText(
                                label = currentScreen.displayNames.getOrElse(index) { "" },
                                isSelected = isSelected,
                                fontSize = listFontSize,
                                lineHeight = listLineHeight,
                                verticalPadding = listVerticalPadding,
                                checkState = index in currentScreen.checkedIndices
                            )
                        }
                    }
                }
            }
            is LauncherScreen.AppPicker -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.karipap.app.input.screen.compose.ScreenInput(handler)
                }
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = currentScreen.title,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = emptyList(),
                    buttonStyle = labels
                ) {
                    List(
                        items = currentScreen.apps,
                        selectedIndex = currentScreen.selectedIndex,
                        itemHeight = itemHeight,
                        scrollTarget = currentScreen.scrollTarget,
                        onListStateChanged = onListStateChanged
                    ) { index, app, isSelected ->
                        PillRowText(
                            label = app,
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                            checkState = index in currentScreen.checkedIndices
                        )
                    }
                }
            }
            is LauncherScreen.ShortcutBinding -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.karipap.app.input.screen.compose.ScreenInput(handler)
                }
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = stringResource(R.string.title_shortcuts),

                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = if (currentScreen.listening) listOf("" to stringResource(R.string.label_hold_buttons))
                        else listOf(labels.north to stringResource(R.string.label_clear), labels.confirm to stringResource(R.string.label_set)),
                    buttonStyle = labels
                ) {
                    List(
                        items = ShortcutAction.entries.toList(),
                        selectedIndex = currentScreen.selectedIndex,
                        itemHeight = itemHeight,
                        scrollTarget = currentScreen.scrollTarget,
                        onListStateChanged = onListStateChanged
                    ) { _, action, isSelected ->
                        val chord = currentScreen.shortcuts[action]
                        val value = if (chord.isNullOrEmpty()) stringResource(R.string.value_none)
                        else chord.joinToString(" + ") { keyCodeName(it) }
                        PillRowKeyValue(
                            label = stringResource(action.labelRes),
                            value = value,
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                    }
                }
                if (currentScreen.listening) {
                    val colors = LocalCannoliColors.current
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.92f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()
                        ) {
                            val actionName = ShortcutAction.entries.getOrNull(currentScreen.selectedIndex)
                                ?.let { stringResource(it.labelRes) } ?: ""
                            Text(
                                text = actionName,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = 24.sp,
                                    color = colors.text
                                )
                            )
                            Spacer(modifier = Modifier.height(Spacing.Sm))
                            Text(
                                text = if (currentScreen.heldKeys.isEmpty()) stringResource(R.string.shortcut_hold_prompt)
                                else currentScreen.heldKeys.joinToString(" + ") { keyCodeName(it) },
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 16.sp,
                                    color = colors.text.copy(alpha = 0.6f)
                                )
                            )
                            Spacer(modifier = Modifier.height(Spacing.Lg))
                            if (currentScreen.heldKeys.isNotEmpty()) {
                                val progress = (currentScreen.countdownMs / 1500f).coerceIn(0f, 1f)
                                Box(
                                    modifier = Modifier
                                        .widthIn(max = 280.dp).fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(Radius.Sm))
                                        .background(colors.text.copy(alpha = 0.2f))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(progress)
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(Radius.Sm))
                                            .background(colors.highlight)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            is LauncherScreen.InstalledCores -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.karipap.app.input.screen.compose.ScreenInput(handler)
                }
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = currentScreen.title ?: stringResource(R.string.title_installed_cores),
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = emptyList(),
                    buttonStyle = labels
                ) {
                    if (currentScreen.loading) {
                        // wait for broadcast response
                    } else if (currentScreen.cores.isEmpty()) {
                        Text(
                            text = stringResource(R.string.installed_cores_none),
                            style = MaterialTheme.typography.bodyLarge,
                            color = cannoliColors.text.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 14.dp)
                        )
                    } else {
                        List(
                            items = currentScreen.cores,
                            selectedIndex = currentScreen.selectedIndex,
                            itemHeight = itemHeight,
                            scrollTarget = currentScreen.scrollTarget,
                            onListStateChanged = onListStateChanged
                        ) { _, core, isSelected ->
                            PillRowText(
                                label = core,
                                isSelected = isSelected,
                                fontSize = listFontSize,
                                lineHeight = listLineHeight,
                                verticalPadding = listVerticalPadding
                            )
                        }
                    }
                }
            }
            is LauncherScreen.ArtworkScraperPlatforms -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.karipap.app.input.screen.compose.ScreenInput(handler)
                }
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = stringResource(R.string.title_artwork_scraper),
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = if (!currentScreen.loading && currentScreen.platforms.isNotEmpty())
                        listOf(labels.confirm to stringResource(R.string.label_open)) else emptyList(),
                    buttonStyle = labels
                ) {
                    when {
                        currentScreen.loading -> Text(
                            text = stringResource(R.string.artwork_scraper_scanning),
                            style = MaterialTheme.typography.bodyLarge,
                            color = cannoliColors.text.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 14.dp)
                        )
                        currentScreen.platforms.isEmpty() -> Text(
                            text = stringResource(R.string.artwork_scraper_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = cannoliColors.text.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 14.dp)
                        )
                        else -> List(
                            items = currentScreen.platforms,
                            selectedIndex = currentScreen.selectedIndex,
                            itemHeight = itemHeight,
                            scrollTarget = currentScreen.scrollTarget,
                            onListStateChanged = onListStateChanged
                        ) { _, platform, isSelected ->
                            PillRowKeyValue(
                                label = platform.name,
                                value = platform.romCount.toString(),
                                isSelected = isSelected,
                                fontSize = listFontSize,
                                lineHeight = listLineHeight,
                                verticalPadding = listVerticalPadding
                            )
                        }
                    }
                }
                if (dialog.isFullScreen) {
                    DialogOverlay(
                        dialogState = dialog,
                        backgroundImagePath = appSettings.backgroundImagePath,
                        backgroundTint = appSettings.backgroundTint,
                        listFontSize = listFontSize,
                        listLineHeight = listLineHeight,
                        listVerticalPadding = listVerticalPadding,
                        buttonStyle = labels
                    )
                }
            }
            is LauncherScreen.ArtworkScraperSources -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.karipap.app.input.screen.compose.ScreenInput(handler)
                }
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = currentScreen.platform.name,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = listOf(labels.confirm to stringResource(R.string.label_select)),
                    buttonStyle = labels
                ) {
                    List(
                        items = ArtworkScraperSource.entries.toList(),
                        selectedIndex = currentScreen.selectedIndex,
                        itemHeight = itemHeight,
                        scrollTarget = currentScreen.scrollTarget,
                        onListStateChanged = onListStateChanged
                    ) { _, source, isSelected ->
                        val value = source.cacheNoteRes?.let { stringResource(it) } ?: ""
                        if (value.isEmpty()) {
                            PillRowText(
                                label = stringResource(source.labelRes),
                                isSelected = isSelected,
                                fontSize = listFontSize,
                                lineHeight = listLineHeight,
                                verticalPadding = listVerticalPadding
                            )
                        } else {
                            PillRowKeyValue(
                                label = stringResource(source.labelRes),
                                value = value,
                                isSelected = isSelected,
                                fontSize = listFontSize,
                                lineHeight = listLineHeight,
                                verticalPadding = listVerticalPadding
                            )
                        }
                    }
                }
                if (dialog.isFullScreen) {
                    DialogOverlay(
                        dialogState = dialog,
                        backgroundImagePath = appSettings.backgroundImagePath,
                        backgroundTint = appSettings.backgroundTint,
                        listFontSize = listFontSize,
                        listLineHeight = listLineHeight,
                        listVerticalPadding = listVerticalPadding,
                        buttonStyle = labels
                    )
                }
            }
            is LauncherScreen.DirectoryBrowser -> {
                inputRouter?.let { dev.karipap.app.input.screen.compose.ScreenInput(it.onboardingHandler) }
                DirectoryBrowserScreen(
                    currentPath = currentScreen.currentPath,
                    entries = currentScreen.entries,
                    selectedIndex = currentScreen.selectedIndex,
                    scrollTarget = currentScreen.scrollTarget,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    itemHeight = itemHeight,
                    isSelectRow = currentScreen.selectedIndex == 0,
                    showSelectOption = currentScreen.currentPath != "/storage/",
                    onListStateChanged = onListStateChanged,
                    buttonStyle = labels
                )
                if (dialog.isFullScreen) {
                    DialogOverlay(
                        dialogState = dialog,
                        backgroundImagePath = appSettings.backgroundImagePath,
                        backgroundTint = appSettings.backgroundTint,
                        listFontSize = listFontSize,
                        listLineHeight = listLineHeight,
                        listVerticalPadding = listVerticalPadding,
                        buttonStyle = labels
                    )
                }
            }
            is LauncherScreen.Credits -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.karipap.app.input.screen.compose.ScreenInput(handler)
                }
                CreditsOverlay(
                    selectedIndex = currentScreen.selectedIndex,
                    scrollTarget = currentScreen.scrollTarget,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    onListStateChanged = onListStateChanged
                )
            }
            is LauncherScreen.Controllers -> {
                inputRouter?.let { dev.karipap.app.input.screen.compose.ScreenInput(it.controllersHandler) }
                ControllersScreen(
                screen = currentScreen,
                viewModel = controllersViewModel,
                modifier = Modifier.fillMaxSize(),
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                listVerticalPadding = listVerticalPadding,
                buttonStyle = labels,
            )
            }
            is LauncherScreen.ControllerDetail -> {
                inputRouter?.let { dev.karipap.app.input.screen.compose.ScreenInput(it.controllerDetailHandler) }
                val controllersState by controllersViewModel.state.collectAsState()
                val mapping = controllersState.connected.firstOrNull { it.mapping.id == currentScreen.mappingId }?.mapping
                    ?: controllersState.savedMappings.firstOrNull { it.id == currentScreen.mappingId }
                ControllerDetailScreen(
                    screen = currentScreen,
                    mapping = mapping,
                    modifier = Modifier.fillMaxSize(),
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    buttonStyle = labels,
                )
                if (dialog.isFullScreen) {
                    DialogOverlay(
                        dialogState = dialog,
                        backgroundImagePath = appSettings.backgroundImagePath,
                        backgroundTint = appSettings.backgroundTint,
                        listFontSize = listFontSize,
                        listLineHeight = listLineHeight,
                        listVerticalPadding = listVerticalPadding,
                        buttonStyle = labels
                    )
                }
            }
            is LauncherScreen.EditButtons -> {
                inputRouter?.let { dev.karipap.app.input.screen.compose.ScreenInput(it.editButtonsHandler) }
                val editState by controllersViewModel.state.collectAsState()
                val mapping = editState.connected.firstOrNull { it.mapping.id == currentScreen.mappingId }?.mapping
                    ?: editState.savedMappings.firstOrNull { it.id == currentScreen.mappingId }
                    ?: mappingRepository?.findById(currentScreen.mappingId)
                if (editButtonsController != null && nav != null) {
                    androidx.compose.runtime.LaunchedEffect(currentScreen.listeningCanonical) {
                        if (currentScreen.listeningCanonical != null) {
                            val startedAt = System.currentTimeMillis()
                            while (currentScreen.listeningCanonical != null) {
                                kotlinx.coroutines.delay(50)
                                val finalized = editButtonsController.tickAndMaybeFinalize()
                                if (finalized != null || !editButtonsController.isListening) {
                                    val cs = nav.currentScreen
                                    if (cs is LauncherScreen.EditButtons) {
                                        nav.replaceTop(cs.copy(listeningCanonical = null, countdownMs = 0))
                                    }
                                    break
                                }
                                val cs = nav.currentScreen
                                if (cs is LauncherScreen.EditButtons && cs.listeningCanonical != null) {
                                    val elapsed = (System.currentTimeMillis() - startedAt).toInt()
                                    if (cs.countdownMs != elapsed) {
                                        nav.replaceTop(cs.copy(countdownMs = elapsed))
                                    }
                                }
                            }
                        }
                    }
                }
                EditButtonsScreen(
                    screen = currentScreen,
                    mapping = mapping,
                    modifier = Modifier.fillMaxSize(),
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.LoggingSettings -> {
                inputRouter?.let { dev.karipap.app.input.screen.compose.ScreenInput(it.loggingSettingsHandler) }
                LoggingSettingsScreen(
                    screen = currentScreen,
                    modifier = Modifier.fillMaxSize(),
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.OnboardingPermissions -> {
                inputRouter?.let { dev.karipap.app.input.screen.compose.ScreenInput(it.onboardingHandler) }
                dev.karipap.app.ui.screens.OnboardingPermissionsScreen(
                    permissions = currentScreen.permissions,
                    granted = currentScreen.granted,
                    volumes = currentScreen.volumes,
                    volumeIndex = currentScreen.volumeIndex,
                    customPath = currentScreen.customPath,
                    romDirectory = currentScreen.effectiveRomDirectory,
                    biosDirectory = currentScreen.effectiveBiosDirectory,
                    selectedIndex = currentScreen.selectedIndex,
                    buttonStyle = labels,
                )
            }
        }

        val systemListState = systemListViewModel?.state?.collectAsState()?.value
        val hideForDialog = dialog is DialogState.About
                || dialog is DialogState.Kitchen
                || dialog is DialogState.UpdateDownload
                || dialog is KeyboardInputState
        val hideForScreen = currentScreen is LauncherScreen.Credits
                || currentScreen is LauncherScreen.DirectoryBrowser
                || currentScreen is LauncherScreen.InputTester
                || currentScreen is LauncherScreen.OnboardingPermissions
                || (currentScreen is LauncherScreen.SystemList && systemListState?.isLoading == true)
        val hasContent = dev.karipap.app.server.KitchenManager.isRunning
                || appSettings.showWifi
                || appSettings.showBluetooth
                || appSettings.showVpn
                || appSettings.showClock
                || appSettings.batteryDisplay != dev.karipap.app.settings.BatteryDisplay.HIDE
                || (updateAvailable && appSettings.showUpdate)
        if (!hideForDialog && !hideForScreen && hasContent) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(screenPadding)
                .onGloballyPositioned { coords ->
                    statusBarLeftEdge.intValue = coords.positionInWindow().x.toInt()
                }
        ) {
            StatusBar(
                updateAvailable = updateAvailable,
                showWifi = appSettings.showWifi,
                showBluetooth = appSettings.showBluetooth,
                showVpn = appSettings.showVpn,
                showClock = appSettings.showClock,
                showBattery = appSettings.batteryDisplay != dev.karipap.app.settings.BatteryDisplay.HIDE,
                batteryIconOnly = appSettings.batteryDisplay == dev.karipap.app.settings.BatteryDisplay.ICON,
                showUpdate = appSettings.showUpdate,
                use24hTime = appSettings.use24h
            )
        }
        }
    }
    val settingsState = settingsViewModel.state.collectAsState().value
    val onPortraitMarginRow = currentScreen is LauncherScreen.Settings
        && settingsState.activeCategory == "display"
        && settingsState.items.getOrNull(settingsState.selectedIndex)?.key == "portrait_margin"
    if (onPortraitMarginRow && appSettings.portraitMarginPx > 0) {
        PortraitMarginOverlay(marginPx = appSettings.portraitMarginPx)
    }
    OsdHost(controller = osdController)
    }
    }
}
