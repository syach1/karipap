package dev.cannoli.scorza.input.autoconfig

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class BundledAutoconfigEntries(load: () -> List<RetroArchCfgEntry>) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deferred: Deferred<List<RetroArchCfgEntry>> = scope.async { load() }

    fun entries(): List<RetroArchCfgEntry> =
        if (deferred.isCompleted) deferred.getCompleted()
        else runBlocking { deferred.await() }
}
