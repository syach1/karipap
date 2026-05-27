package dev.karipap.app.input.screen

import dagger.hilt.android.scopes.ActivityScoped
import dev.karipap.app.input.ActivityActions
import dev.karipap.app.input.LauncherActions
import dev.karipap.app.input.ScreenInputHandler
import dev.karipap.app.navigation.BrowsePurpose
import dev.karipap.app.navigation.LauncherScreen
import dev.karipap.app.navigation.NavigationController
import dev.karipap.app.navigation.OnboardingPermission
import dev.karipap.app.settings.SettingsRepository
import dev.karipap.app.setup.SetupCoordinator
import dev.karipap.app.ui.screens.DialogState
import dev.karipap.app.util.StoragePermissions
import java.io.File
import javax.inject.Inject

@ActivityScoped
class OnboardingInputHandler @Inject constructor(
    private val nav: NavigationController,
    private val settings: SettingsRepository,
    private val setupCoordinator: SetupCoordinator,
    private val activityActions: ActivityActions,
    private val launcherActions: LauncherActions,
) : ScreenInputHandler {

    var onRequestPermission: ((OnboardingPermission) -> Unit)? = null
    var onFolderChosen: ((targetPath: String) -> Unit)? = null

    override fun onUp() {
        when (val screen = nav.currentScreen) {
            is LauncherScreen.OnboardingPermissions -> nav.replaceTop(screen.moved(-1))
            is LauncherScreen.DirectoryBrowser -> {
                val hasSelect = screen.currentPath != "/storage/"
                val count = screen.entries.size + if (hasSelect) 1 else 0
                if (count > 0) {
                    val newIdx = (screen.selectedIndex - 1 + count) % count
                    nav.replaceTop(screen.copy(selectedIndex = newIdx))
                }
            }
            else -> {}
        }
    }

    override fun onDown() {
        when (val screen = nav.currentScreen) {
            is LauncherScreen.OnboardingPermissions -> nav.replaceTop(screen.moved(1))
            is LauncherScreen.DirectoryBrowser -> {
                val hasSelect = screen.currentPath != "/storage/"
                val count = screen.entries.size + if (hasSelect) 1 else 0
                if (count > 0) {
                    val newIdx = (screen.selectedIndex + 1) % count
                    nav.replaceTop(screen.copy(selectedIndex = newIdx))
                }
            }
            else -> {}
        }
    }

    override fun onLeft() {
        val screen = nav.currentScreen as? LauncherScreen.OnboardingPermissions ?: return
        if (screen.isStorageRowFocused) nav.replaceTop(screen.cycledVolume(-1))
    }

    override fun onRight() {
        val screen = nav.currentScreen as? LauncherScreen.OnboardingPermissions ?: return
        if (screen.isStorageRowFocused) nav.replaceTop(screen.cycledVolume(1))
    }

    override fun onConfirm() {
        when (val screen = nav.currentScreen) {
            is LauncherScreen.OnboardingPermissions -> {
                if (screen.isRomRowFocused) {
                    pushSetupDirectoryBrowser(BrowsePurpose.SETUP_ROM_DIRECTORY, screen.effectiveRomDirectory ?: screen.targetPath ?: "/storage/")
                } else if (screen.isBiosRowFocused) {
                    pushSetupDirectoryBrowser(BrowsePurpose.SETUP_BIOS_DIRECTORY, screen.effectiveBiosDirectory ?: screen.targetPath ?: "/storage/")
                } else if (screen.isStorageRowFocused) {
                    if (screen.isCustomVolume) {
                        pushSetupDirectoryBrowser(BrowsePurpose.SETUP, "/storage/")
                    }
                } else if (!screen.isFocusedGranted) {
                    screen.focusedPermission?.let { onRequestPermission?.invoke(it) }
                }
            }
            is LauncherScreen.DirectoryBrowser -> {
                val hasSelect = screen.currentPath != "/storage/"
                if (hasSelect && screen.selectedIndex == 0) {
                    val resolved = if (screen.purpose == BrowsePurpose.SETUP && setupCoordinator.isVolumeRoot(screen.currentPath))
                        screen.currentPath + "Karipap/"
                    else screen.currentPath
                    when (screen.purpose) {
                        BrowsePurpose.SETUP -> {
                            val wizardIdx = nav.screenStack.indexOfLast { it is LauncherScreen.OnboardingPermissions }
                            if (wizardIdx >= 0) {
                                val wizard = nav.screenStack[wizardIdx] as LauncherScreen.OnboardingPermissions
                                val path = if (resolved.endsWith("/")) resolved else "$resolved/"
                                nav.screenStack[wizardIdx] = wizard.copy(customPath = path)
                            }
                            nav.pop()
                        }
                        BrowsePurpose.SETUP_ROM_DIRECTORY -> {
                            val wizardIdx = nav.screenStack.indexOfLast { it is LauncherScreen.OnboardingPermissions }
                            if (wizardIdx >= 0) {
                                val wizard = nav.screenStack[wizardIdx] as LauncherScreen.OnboardingPermissions
                                nav.screenStack[wizardIdx] = wizard.copy(romDirectory = normalizeDirectory(resolved))
                            }
                            nav.pop()
                        }
                        BrowsePurpose.SETUP_BIOS_DIRECTORY -> {
                            val wizardIdx = nav.screenStack.indexOfLast { it is LauncherScreen.OnboardingPermissions }
                            if (wizardIdx >= 0) {
                                val wizard = nav.screenStack[wizardIdx] as LauncherScreen.OnboardingPermissions
                                nav.screenStack[wizardIdx] = wizard.copy(biosDirectory = normalizeDirectory(resolved))
                            }
                            nav.pop()
                        }
                        BrowsePurpose.SD_ROOT -> {
                            settings.sdCardRoot = resolved
                            nav.pop()
                            nav.dialogState.value = DialogState.RestartRequired
                        }
                        BrowsePurpose.ROM_DIRECTORY -> {
                            settings.romDirectory = resolved
                            launcherActions.invalidateAllLibraryCaches()
                            nav.pop()
                            nav.dialogState.value = DialogState.RestartRequired
                        }
                        BrowsePurpose.BIOS_DIRECTORY -> {
                            settings.biosDirectory = resolved
                            nav.pop()
                            nav.dialogState.value = DialogState.RestartRequired
                        }
                    }
                } else {
                    val entryIdx = screen.selectedIndex - if (hasSelect) 1 else 0
                    val folderName = screen.entries.getOrNull(entryIdx) ?: return
                    val newPath = setupCoordinator.resolveDirectoryEntry(screen.currentPath, folderName)
                    val newEntries = setupCoordinator.listDirectories(newPath)
                    nav.replaceTop(screen.copy(currentPath = newPath, entries = newEntries, selectedIndex = 0))
                }
            }
            else -> {}
        }
    }

    override fun onBack() {
        when (val screen = nav.currentScreen) {
            is LauncherScreen.OnboardingPermissions -> activityActions.finishAffinity()
            is LauncherScreen.DirectoryBrowser -> {
                val parent = setupCoordinator.parentDirectory(screen.currentPath)
                if (parent != null) {
                    val newEntries = setupCoordinator.listDirectories(parent)
                    nav.replaceTop(screen.copy(currentPath = parent, entries = newEntries, selectedIndex = 0))
                } else if (screen.purpose != BrowsePurpose.SETUP) {
                    nav.pop()
                }
            }
            else -> {}
        }
    }

    override fun onWest() {
        if (nav.currentScreen is LauncherScreen.DirectoryBrowser) {
            nav.pop()
        }
    }

    override fun onNorth() {
        val screen = nav.currentScreen as? LauncherScreen.DirectoryBrowser ?: return
        if (screen.currentPath != "/storage/") {
            nav.dialogState.value = DialogState.NewFolderInput(parentPath = screen.currentPath)
        }
    }

    override fun onStart() {
        val screen = nav.currentScreen as? LauncherScreen.OnboardingPermissions ?: return
        val target = screen.targetPath ?: return
        val romDirectory = screen.effectiveRomDirectory ?: ""
        val biosDirectory = screen.effectiveBiosDirectory ?: ""
        createInitialDirectories(target, romDirectory, biosDirectory)
        settings.sdCardRoot = target
        settings.setupCompleted = true
        settings.romDirectory = romDirectory
        settings.biosDirectory = biosDirectory
        settings.flush()
        launcherActions.invalidateAllLibraryCaches()
        onFolderChosen?.invoke(target)
    }

    private fun pushSetupDirectoryBrowser(purpose: BrowsePurpose, startPath: String) {
        val path = when {
            startPath.isBlank() -> "/storage/"
            startPath == "/storage/" -> startPath
            else -> startPath.trimEnd('/') + "/"
        }
        val entries = setupCoordinator.listDirectories(path)
        nav.push(LauncherScreen.DirectoryBrowser(
            purpose = purpose,
            currentPath = path,
            entries = entries
        ))
    }

    private fun normalizeDirectory(path: String): String =
        if (path == "/storage/") path else path.trimEnd('/')

    private fun createInitialDirectories(rootPath: String, romDirectory: String, biosDirectory: String) {
        try {
            val dirs = buildList {
                add(File(rootPath))
                if (romDirectory.isNotBlank()) add(File(romDirectory))
                if (biosDirectory.isNotBlank()) add(File(biosDirectory))
            }
            StoragePermissions.ensurePcWritable(*dirs.toTypedArray())
        } catch (_: SecurityException) {
        }
    }
}
