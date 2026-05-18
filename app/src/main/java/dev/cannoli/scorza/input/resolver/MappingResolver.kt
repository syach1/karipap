package dev.cannoli.scorza.input.resolver

import dev.cannoli.scorza.input.autoconfig.RetroArchCfgEntry
import dev.cannoli.scorza.input.ConnectedDevice
import dev.cannoli.scorza.input.DeviceMapping
import dev.cannoli.scorza.input.hints.ControllerHintTable
import dev.cannoli.scorza.input.repo.MappingRepository
import java.io.File

data class ResolvedMapping(
    val mapping: DeviceMapping,
    val persistent: Boolean,
)

class MappingResolver(
    private val repository: MappingRepository,
    private val bundledRetroArchEntries: dev.cannoli.scorza.input.autoconfig.BundledAutoconfigEntries,
    private val hints: ControllerHintTable,
    private val mappingsDir: File? = null,
) {

    /**
     * Resolve a connected device to its mapping.
     *
     * [persistenceDescriptor] is the identifier the caller wants the resulting mapping to be
     * persisted under. Callers (the bridge) compute this from sibling-folded InputDevices — the
     * gamepad's own descriptor when it's unique, or a sibling's descriptor on Retroid-style
     * phantom-rewrite hosts where the gamepad endpoint has a degenerate (empty-uniqueId) hash.
     * Null means "use the device's own descriptor (or none)".
     */
    fun resolve(device: ConnectedDevice, persistenceDescriptor: String? = null): ResolvedMapping {
        val matchInput = device.toMatchInput(
            descriptor = persistenceDescriptor?.takeIf { it.isNotEmpty() }
                ?: device.descriptor.takeIf { it.isNotEmpty() }
        )

        val candidates = repository.list()
            .map { it to it.match.score(matchInput) }
            .filter { it.second > 0 }
        if (candidates.isNotEmpty()) {
            val best = candidates.maxWithOrNull(
                compareBy<Pair<DeviceMapping, Int>>({ it.second })
                    .thenBy { mappingsDir?.let { dir -> File(dir, "${it.first.id}.ini").lastModified() } ?: 0L }
            )
            if (best != null) return ResolvedMapping(best.first, persistent = true)
        }

        val raMatch = bestRetroArchEntry(device)
        if (raMatch != null) {
            return ResolvedMapping(
                RetroArchAutoconfigImporter.import(raMatch, device, hints, persistenceDescriptor),
                persistent = false,
            )
        }

        return ResolvedMapping(
            AndroidDefaultMappingFactory.create(device, hints, persistenceDescriptor),
            persistent = false,
        )
    }

    // Name signal beats VID/PID signal when they disagree. On phantom-rewrite hosts (Retroid
    // handhelds rewriting a paired BT pad's gamepad endpoint to report the built-in's VID/PID
    // while keeping the BT pad's own name), the device's reported VID/PID identifies a different
    // bundled cfg than the device's name does, and only the name-matching cfg has the right
    // button layout for the physical pad in the user's hand.
    private fun bestRetroArchEntry(device: ConnectedDevice): RetroArchCfgEntry? {
        var nameAndVidPid: RetroArchCfgEntry? = null
        var nameOnly: RetroArchCfgEntry? = null
        var vidPidOnly: RetroArchCfgEntry? = null
        for (entry in bundledRetroArchEntries.entries()) {
            val nameMatch = entry.deviceName.isNotEmpty() && entry.deviceName == device.name
            val hasVidPid = device.vendorId != 0 && device.productId != 0 &&
                entry.vendorId != null && entry.productId != null
            val vidPidMatch = hasVidPid &&
                entry.vendorId == device.vendorId &&
                entry.productId == device.productId
            when {
                nameMatch && vidPidMatch -> if (nameAndVidPid == null) nameAndVidPid = entry
                nameMatch -> if (nameOnly == null) nameOnly = entry
                vidPidMatch -> if (vidPidOnly == null) vidPidOnly = entry
            }
        }
        return nameAndVidPid ?: nameOnly ?: vidPidOnly
    }
}
