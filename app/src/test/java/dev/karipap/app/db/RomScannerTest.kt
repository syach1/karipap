package dev.karipap.app.db

import dev.karipap.app.util.RomDirectoryWalker
import org.json.JSONArray
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RomScannerTest {
    @Test
    fun stored_rows_differ_when_database_is_missing_scanned_roms() {
        val scanned = listOf(
            RomDirectoryWalker.ScannedRom("neogeo/mslug.zip", "mslug", null, null),
            RomDirectoryWalker.ScannedRom("neogeo/kof98.zip", "kof98", null, null),
        )

        assertTrue(storedRomsDifferFromScan(emptyList(), scanned))
    }

    @Test
    fun stored_rows_match_when_path_metadata_and_disc_paths_match_scan_result() {
        val discPaths = listOf("psx/Game (Disc 1).cue", "psx/Game (Disc 2).cue")
        val scanned = listOf(
            RomDirectoryWalker.ScannedRom(
                relativePath = "psx/Game (Disc 1).cue",
                displayName = "Game",
                tags = "(USA)",
                discPaths = discPaths,
            ),
        )
        val stored = listOf(
            StoredRomSnapshot(
                path = "psx/Game (Disc 1).cue",
                displayName = "Game",
                tags = "(USA)",
                discPaths = JSONArray(discPaths).toString(),
            ),
        )

        assertFalse(storedRomsDifferFromScan(stored, scanned))
    }

    @Test
    fun stored_rows_differ_when_metadata_changes_even_if_paths_match() {
        val scanned = listOf(
            RomDirectoryWalker.ScannedRom("neogeo/mslug.zip", "Metal Slug", null, null),
        )
        val stored = listOf(
            StoredRomSnapshot("neogeo/mslug.zip", "mslug", null, null),
        )

        assertTrue(storedRomsDifferFromScan(stored, scanned))
    }
}
