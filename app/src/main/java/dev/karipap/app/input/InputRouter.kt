package dev.karipap.app.input

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.igm.ShortcutAction
import dev.karipap.app.artwork.ArtworkScraperSource
import dev.karipap.app.config.PlatformConfig
import dev.karipap.app.input.screen.ControllerDetailInputHandler
import dev.karipap.app.input.screen.ControllersInputHandler
import dev.karipap.app.input.screen.EditButtonsInputHandler
import dev.karipap.app.input.screen.GameListInputHandler
import dev.karipap.app.input.screen.LoggingSettingsInputHandler
import dev.karipap.app.input.screen.InputTesterInputHandler
import dev.karipap.app.input.screen.ScrollListInputHandler
import dev.karipap.app.input.screen.SettingsInputHandler
import dev.karipap.app.input.screen.OnboardingInputHandler
import dev.karipap.app.input.screen.SystemListInputHandler
import dev.karipap.app.launcher.InstalledCoreService
import dev.karipap.app.launcher.LaunchManager
import dev.karipap.app.navigation.LauncherScreen
import dev.karipap.app.navigation.NavigationController
import dev.karipap.app.settings.GlobalOverridesManager
import dev.karipap.app.ui.components.CREDITS
import dev.karipap.app.ui.screens.CoreMappingEntry
import dev.karipap.app.ui.screens.DialogState
import dev.karipap.app.input.runtime.InputDispatcher
import javax.inject.Inject

@ActivityScoped
class InputRouter @Inject constructor(
    private val nav: NavigationController,
    private val dialogHandler: DialogInputHandler,
    val systemListHandler: SystemListInputHandler,
    val gameListHandler: GameListInputHandler,
    val settingsHandler: SettingsInputHandler,
    val onboardingHandler: OnboardingInputHandler,
    val inputTesterHandler: InputTesterInputHandler,
    val controllerDetailHandler: ControllerDetailInputHandler,
    val controllersHandler: ControllersInputHandler,
    val editButtonsHandler: EditButtonsInputHandler,
    val loggingSettingsHandler: LoggingSettingsInputHandler,
    private val scrollListFactory: ScrollListInputHandler.Factory,
    private val platformConfig: PlatformConfig,
    private val installedCoreService: InstalledCoreService,
    private val globalOverrides: GlobalOverridesManager,
    private val launcherActions: LauncherActions,
    private val bindingController: BindingController,
    private val screenInputRegistry: dev.karipap.app.input.runtime.ScreenInputRegistry,
    @ApplicationContext private val context: Context,
) {
    var unregisterCoreQueryReceiver: () -> Unit = {}

    fun wire(dispatcher: InputDispatcher) {
        gameListHandler.buildContextOptions = dialogHandler::buildGameContextOptions

        // Dialog handler gets first crack and returns true if it consumed the input. When no
        // dialog is active, dispatch goes to whichever ScreenInputHandler is at the top of the
        // registry (every launcher screen pushes one via ScreenInput in AppNavGraph; if the
        // registry is briefly empty during a transition, EmptyScreenInputHandler's defaults are
        // safe no-ops).
        fun screen(): dev.karipap.app.input.ScreenInputHandler = screenInputRegistry.top
        dispatcher.onUp = { if (!dialogHandler.onUp()) screen().onUp() }
        dispatcher.onDown = { if (!dialogHandler.onDown()) screen().onDown() }
        dispatcher.onLeft = { if (!dialogHandler.onLeft()) screen().onLeft() }
        dispatcher.onRight = { if (!dialogHandler.onRight()) screen().onRight() }
        dispatcher.onConfirm = { if (!dialogHandler.onConfirm()) screen().onConfirm() }
        dispatcher.onBack = { if (!dialogHandler.onBack()) screen().onBack() }
        dispatcher.onStart = { if (!dialogHandler.onStart()) screen().onStart() }
        dispatcher.onSelect = { if (!dialogHandler.onSelect()) screen().onSelect() }
        dispatcher.onSelectUp = { onSelectUp() }
        dispatcher.onNorth = { if (!dialogHandler.onNorth()) screen().onNorth() }
        dispatcher.onWest = { if (!dialogHandler.onWest()) screen().onWest() }
        dispatcher.onL1 = { if (!dialogHandler.onL1()) screen().onL1() }
        dispatcher.onR1 = { if (!dialogHandler.onR1()) screen().onR1() }
        dispatcher.onL2 = { if (!dialogHandler.onL2()) screen().onL2() }
        dispatcher.onR2 = { if (!dialogHandler.onR2()) screen().onR2() }
    }

    fun onSelectUp() {
        dialogHandler.cancelSelectHold()
        gameListHandler.cancelSelectHoldTimer()

        val dialogConsumed = dialogHandler.onSelectUp()
        if (!dialogConsumed) screenInputRegistry.top.onSelectUp()

        nav.selectDown = false
        nav.selectHeld = false
    }

    /**
     * Returns the appropriate handler for a ScrollableScreen instance. Used by [AppNavGraph] to
     * push a handler via ScreenInput for screens that don't have a dedicated handler class --
     * the handler is generated inline from the [scrollable] factory each time the screen is
     * composed. Other screens (with dedicated handler classes like [SystemListInputHandler])
     * push their handler directly via this router's public fields, bypassing this method.
     */
    fun currentHandler(): ScreenInputHandler = when (val screen = nav.currentScreen) {
        is LauncherScreen.ScrollableScreen -> scrollableHandlerFor(screen)
        else -> object : ScreenInputHandler {}
    }

    /**
     * Per-screen handler factory for everything that implements [LauncherScreen.ScrollableScreen].
     *
     * The actual logic lives in the named *Handler() methods below; each one is a thin wrapper
     * around [scrollable], which buries the [nav.currentScreen] cast and the default move/back
     * boilerplate so each screen only has to spell out the parts that differ.
     */
    private fun scrollableHandlerFor(screen: LauncherScreen.ScrollableScreen): ScreenInputHandler = when (screen) {
        is LauncherScreen.CoreMapping       -> coreMappingHandler()
        is LauncherScreen.CorePicker        -> corePickerHandler()
        is LauncherScreen.ColorList         -> colorListHandler()
        is LauncherScreen.CollectionPicker  -> collectionPickerHandler()
        is LauncherScreen.ChildPicker       -> childPickerHandler()
        is LauncherScreen.AppPicker         -> appPickerHandler()
        is LauncherScreen.ShortcutBinding   -> shortcutBindingHandler()
        is LauncherScreen.Credits           -> creditsHandler()
        is LauncherScreen.InstalledCores    -> installedCoresHandler()
        is LauncherScreen.ArtworkScraperPlatforms -> artworkScraperPlatformsHandler()
        is LauncherScreen.ArtworkScraperSources   -> artworkScraperSourcesHandler()
        else -> object : ScreenInputHandler {}
    }

    private inline fun <reified T> scrollable(
        crossinline onConfirm: T.() -> Unit = {},
        crossinline onBack: T.() -> Unit = { nav.pop() },
        crossinline onMove: T.(Int) -> LauncherScreen = { idx -> withScroll(selectedIndex = idx, scrollTarget = scrollTarget) },
        noinline onStart: (T.() -> Unit)? = null,
        noinline onWest: (T.() -> Unit)? = null,
        noinline onNorth: (T.() -> Unit)? = null,
    ): ScrollListInputHandler where T : LauncherScreen, T : LauncherScreen.ScrollableScreen {
        val current: () -> T? = { nav.currentScreen as? T }
        return scrollListFactory.create(
            itemCount = { current()?.itemCount ?: 0 },
            selectedIndex = { current()?.selectedIndex ?: 0 },
            onMove = { idx -> current()?.let { nav.replaceTop(it.onMove(idx)) } ?: Unit },
            onConfirm = { current()?.onConfirm() ?: Unit },
            onBack = { current()?.onBack() ?: nav.pop() },
            onStart = onStart?.let { fn -> { current()?.fn() ?: Unit } },
            onWest = onWest?.let { fn -> { current()?.fn() ?: Unit } },
            onNorth = onNorth?.let { fn -> { current()?.fn() ?: Unit } },
        )
    }

    private fun coreMappingHandler() = scrollable<LauncherScreen.CoreMapping>(
        onConfirm = {
            mappings.getOrNull(selectedIndex)?.let { entry ->
                val bundledCoresDir = LaunchManager.extractBundledCores(context)
                val options = platformConfig.getCorePickerOptions(
                    entry.tag, context.packageManager,
                    installedRaCores = installedCoreService.installedCores,
                    embeddedCoresDir = bundledCoresDir,
                    unresponsivePackages = installedCoreService.unresponsivePackages
                )
                val currentCore = platformConfig.getCoreMapping(entry.tag)
                val currentApp = platformConfig.getAppPackage(entry.tag)
                val currentRunner = entry.runnerLabel
                val selectedIdx = if ((currentRunner == "App" || currentRunner == "Standalone") && currentApp != null) {
                    options.indexOfFirst { it.appPackage == currentApp }.coerceAtLeast(0)
                } else {
                    options.indexOfFirst {
                        it.coreId == currentCore &&
                            PlatformConfig.normalizeRunnerLabel(it.runnerLabel) == PlatformConfig.normalizeRunnerLabel(currentRunner)
                    }
                        .coerceAtLeast(options.indexOfFirst { it.coreId == currentCore }.coerceAtLeast(0))
                }
                nav.push(LauncherScreen.CorePicker(
                    tag = entry.tag,
                    platformName = entry.platformName,
                    cores = options,
                    selectedIndex = selectedIdx,
                    activeIndex = selectedIdx
                ))
            }
        },
        onBack = {
            platformConfig.saveCoreMappings()
            nav.pop()
        },
        onWest = {
            val newFilter = (filter + 1) % 4
            nav.replaceTop(copy(
                mappings = filterCoreMappings(allMappings, newFilter),
                filter = newFilter, selectedIndex = 0, scrollTarget = 0
            ))
        },
    )

    private fun corePickerHandler() = scrollable<LauncherScreen.CorePicker>(
        onConfirm = { dialogHandler.onCorePickerConfirm(this) },
        onBack = {
            val s = this
            nav.pop()
            if (s.gamePath != null) {
                dialogHandler.restoreContextMenu()
            } else {
                val cm = nav.screenStack.lastOrNull()
                if (cm is LauncherScreen.CoreMapping) {
                    val all = platformConfig.getDetailedMappings(
                        context.packageManager,
                        installedCoreService.installedCores,
                        LaunchManager.extractBundledCores(context),
                        installedCoreService.unresponsivePackages
                    )
                    val filtered = filterCoreMappings(all, cm.filter)
                    val idx = filtered.indexOfFirst { it.tag == s.tag }.coerceAtLeast(0)
                    nav.screenStack[nav.screenStack.lastIndex] =
                        cm.copy(mappings = filtered, allMappings = all, selectedIndex = idx)
                }
            }
        },
    )

    private fun colorListHandler() = scrollable<LauncherScreen.ColorList>(
        onConfirm = { colors.getOrNull(selectedIndex)?.let { launcherActions.openColorPicker(it.key) } },
    )

    private fun collectionPickerHandler() = scrollable<LauncherScreen.CollectionPicker>(
        onConfirm = {
            if (collectionIds.isNotEmpty()) {
                val newChecked = if (selectedIndex in checkedIndices) checkedIndices - selectedIndex
                                 else checkedIndices + selectedIndex
                nav.replaceTop(copy(checkedIndices = newChecked))
            }
        },
        onBack = { dialogHandler.onCollectionPickerConfirm(this) },
        onWest = { nav.dialogState.value = DialogState.NewCollectionInput(gamePaths = gamePaths) },
    )

    private fun childPickerHandler() = scrollable<LauncherScreen.ChildPicker>(
        onConfirm = {
            if (collectionIds.isNotEmpty()) {
                val newChecked = if (selectedIndex in checkedIndices) checkedIndices - selectedIndex
                                 else checkedIndices + selectedIndex
                nav.replaceTop(copy(checkedIndices = newChecked))
            }
        },
        onBack = { dialogHandler.onChildPickerConfirm(this) },
    )

    private fun appPickerHandler() = scrollable<LauncherScreen.AppPicker>(
        onConfirm = {
            val newChecked = if (selectedIndex in checkedIndices) checkedIndices - selectedIndex
                             else checkedIndices + selectedIndex
            nav.replaceTop(copy(checkedIndices = newChecked))
        },
        onBack = { settingsHandler.confirmAppPicker(this) },
    )

    private fun shortcutBindingHandler() = scrollable<LauncherScreen.ShortcutBinding>(
        onMove = { idx -> if (listening) this else copy(selectedIndex = idx) },
        onConfirm = {
            if (!listening) {
                nav.replaceTop(copy(listening = true, heldKeys = emptySet(), countdownMs = 0))
                bindingController.startListening()
            }
        },
        onBack = {
            bindingController.stopListening()
            globalOverrides.saveShortcuts(shortcuts)
            nav.pop()
        },
        onNorth = {
            if (!listening) {
                ShortcutAction.entries.getOrNull(selectedIndex)?.let { action ->
                    nav.replaceTop(copy(shortcuts = shortcuts + (action to emptySet())))
                }
            }
        },
    )

    private fun creditsHandler() = scrollable<LauncherScreen.Credits>()

    private fun installedCoresHandler() = scrollable<LauncherScreen.InstalledCores>(
        onBack = {
            unregisterCoreQueryReceiver()
            nav.pop()
        },
    )

    private fun artworkScraperPlatformsHandler() = scrollable<LauncherScreen.ArtworkScraperPlatforms>(
        onConfirm = {
            if (!loading) {
                platforms.getOrNull(selectedIndex)?.let { platform ->
                    nav.push(LauncherScreen.ArtworkScraperSources(platform = platform))
                }
            }
        },
    )

    private fun artworkScraperSourcesHandler() = scrollable<LauncherScreen.ArtworkScraperSources>(
        onConfirm = {
            ArtworkScraperSource.entries.getOrNull(selectedIndex)?.let { source ->
                launcherActions.runArtworkScraper(platform, source)
            }
        },
    )

    private fun filterCoreMappings(all: List<CoreMappingEntry>, filter: Int): List<CoreMappingEntry> = when (filter) {
        1 -> all.filter { it.coreDisplayName == "Missing" || it.coreDisplayName == "None" || it.runnerLabel == "Missing" || it.runnerLabel == "Unknown" }
        2 -> all.filter { it.runnerLabel == "Internal" }
        3 -> all.filter { it.runnerLabel != "Internal" && it.coreDisplayName != "Missing" && it.coreDisplayName != "None" && it.runnerLabel != "Missing" && it.runnerLabel != "Unknown" }
        else -> all
    }
}
