package dev.karipap.app.artwork

import androidx.annotation.StringRes
import dev.karipap.app.R
import dev.karipap.app.config.CannoliPaths
import dev.karipap.app.config.PlatformConfig
import dev.karipap.app.db.RomsRepository
import dev.karipap.app.di.CannoliPathsProvider
import dev.karipap.app.model.Rom
import dev.karipap.app.util.ArtworkLookup
import dev.karipap.app.util.GameMetadata
import dev.karipap.app.util.GamelistXmlManager
import org.json.JSONObject
import org.jsoup.Jsoup
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.parsers.DocumentBuilderFactory

enum class ArtworkScraperSource(@StringRes val labelRes: Int, val cacheNoteRes: Int? = null) {
    SCREENSCRAPER(R.string.artwork_source_screenscraper, R.string.artwork_source_with_description),
    SCREENSCRAPER_NO_DESC(R.string.artwork_source_screenscraper_nodesc, R.string.artwork_source_no_description),
    LIBRETRO(R.string.artwork_source_libretro, R.string.artwork_scraper_cache_note),
    DAIJISHO(R.string.artwork_source_daijisho, R.string.artwork_scraper_cache_note),
    THEGAMESDB(R.string.artwork_source_thegamesdb),
}

data class ArtworkScraperPlatform(
    val tag: String,
    val name: String,
    val romCount: Int,
)

data class ArtworkScrapeResult(
    val attempted: Int,
    val downloaded: Int,
    val skippedExisting: Int,
    val notFound: Int,
    val errors: Int,
    val message: String,
)

@Singleton
class ArtworkScraper @Inject constructor(
    private val pathsProvider: CannoliPathsProvider,
    private val romsRepository: RomsRepository,
    private val platformConfig: PlatformConfig,
    private val artworkLookup: ArtworkLookup,
    private val gamelistXmlManager: GamelistXmlManager,
) {
    private val paths: CannoliPaths get() = CannoliPaths(pathsProvider.root)
    private val robotsCache = ConcurrentHashMap<String, RobotsRules>()
    private val lastFetchMsByHost = ConcurrentHashMap<String, Long>()
    private val throttleLock = Any()

    fun platformsWithRoms(): List<ArtworkScraperPlatform> {
        return romsRepository.platformCounts()
            .filterValues { it > 0 }
            .map { (tag, count) ->
                val upper = tag.uppercase(Locale.US)
                ArtworkScraperPlatform(
                    tag = upper,
                    name = platformConfig.getDisplayName(upper),
                    romCount = count,
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    suspend fun scrape(
        platform: ArtworkScraperPlatform,
        source: ArtworkScraperSource,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ArtworkScrapeResult {
        return when (source) {
            ArtworkScraperSource.SCREENSCRAPER -> scrapeScreenScraper(platform, onProgress, saveMeta = true)
            ArtworkScraperSource.SCREENSCRAPER_NO_DESC -> scrapeScreenScraper(platform, onProgress, saveMeta = false)
            ArtworkScraperSource.LIBRETRO -> scrapeLibretro(platform, onProgress)
            ArtworkScraperSource.DAIJISHO -> scrapeDaijisho(platform, onProgress)
            ArtworkScraperSource.THEGAMESDB -> scrapeTheGamesDb(platform, onProgress)
        }
    }

    private suspend fun scrapeLibretro(
        platform: ArtworkScraperPlatform,
        onProgress: suspend (done: Int, total: Int) -> Unit,
    ): ArtworkScrapeResult {
        val repoName = libretroThumbnailRepos[platform.tag]
            ?: return ArtworkScrapeResult(
                attempted = 0,
                downloaded = 0,
                skippedExisting = 0,
                notFound = 0,
                errors = 0,
                message = "No Libretro thumbnail set is mapped for ${platform.name}.",
            )
        val roms = romsRepository.romsForArtwork(platform.tag)
        return scrapeRoms(platform, roms, onProgress) { rom, artDir ->
            scrapeLibretroRom(repoName, rom, artDir, "Libretro Thumbnails")
        }
    }

    private suspend fun scrapeDaijisho(
        platform: ArtworkScraperPlatform,
        onProgress: suspend (done: Int, total: Int) -> Unit,
    ): ArtworkScrapeResult {
        val specs = daijishoScraperSpecs(platform)
        if (specs.isEmpty()) {
            return ArtworkScrapeResult(
                attempted = 0,
                downloaded = 0,
                skippedExisting = 0,
                notFound = 0,
                errors = 0,
                message = "No Daijisho scraper source is mapped for ${platform.name}.",
            )
        }
        val roms = romsRepository.romsForArtwork(platform.tag)
        return scrapeRoms(platform, roms, onProgress) { rom, artDir ->
            val tags = lazy { dsessTags(platform, rom) }
            var hadError = false
            for (spec in specs) {
                val outcome = try {
                    when (spec) {
                        is DaijishoScraperSpec.Libretro -> {
                            scrapeLibretroRom(spec.repoName, rom, artDir, "Daijisho Libretro thumbnails")
                        }
                        is DaijishoScraperSpec.Dsess -> {
                            scrapeDsessRom(spec.config, tags.value, rom, artDir)
                        }
                    }
                } catch (_: Throwable) {
                    ScrapeOutcome.Error
                }
                when (outcome) {
                    ScrapeOutcome.Downloaded -> return@scrapeRoms ScrapeOutcome.Downloaded
                    ScrapeOutcome.Error -> hadError = true
                    ScrapeOutcome.NotFound -> Unit
                }
            }
            if (hadError) ScrapeOutcome.Error else ScrapeOutcome.NotFound
        }
    }

    private suspend fun scrapeTheGamesDb(
        platform: ArtworkScraperPlatform,
        onProgress: suspend (done: Int, total: Int) -> Unit,
    ): ArtworkScrapeResult {
        val keyFile = File(paths.configDir, "ArtworkScraper/thegamesdb_api_key.txt")
        val apiKey = keyFile.takeIf { it.isFile }?.readText()?.trim().orEmpty()
        if (apiKey.isEmpty()) {
            return ArtworkScrapeResult(
                attempted = 0, downloaded = 0, skippedExisting = 0,
                notFound = 0, errors = 0,
                message = "TheGamesDB API key missing. Put it at Config/ArtworkScraper/thegamesdb_api_key.txt.",
            )
        }
        val platformId = resolveTheGamesDbPlatformId(apiKey, platform)
        val roms = romsRepository.romsForArtwork(platform.tag)
        if (roms.isEmpty()) {
            return ArtworkScrapeResult(0, 0, 0, 0, 0, "No ROMs found for ${platform.name}.")
        }
        val artDir = paths.artFor(platform.tag)
        artDir.mkdirs()
        var skipped = 0
        var downloaded = 0
        var notFound = 0
        var errors = 0
        val metaEntries = mutableMapOf<String, GameMetadata>()
        roms.forEachIndexed { index, rom ->
            onProgress(index + 1, roms.size)
            if (cachedArtworkExists(artDir, rom.path.nameWithoutExtension)) {
                skipped++
                return@forEachIndexed
            }
            val (outcome, meta) = try {
                scrapeTheGamesDbRomWithMeta(apiKey, platformId, rom, artDir)
            } catch (_: Throwable) {
                ScrapeOutcome.Error to null
            }
            when (outcome) {
                ScrapeOutcome.Downloaded -> {
                    downloaded++
                    if (meta != null) metaEntries[rom.path.nameWithoutExtension] = meta
                }
                ScrapeOutcome.NotFound -> notFound++
                ScrapeOutcome.Error -> errors++
            }
        }
        if (metaEntries.isNotEmpty()) {
            try { gamelistXmlManager.saveAll(platform.tag, metaEntries) } catch (_: Throwable) {}
        }
        artworkLookup.invalidate(platform.tag)
        romsRepository.invalidateGamelistCache(platform.tag)
        val attempted = roms.size - skipped
        val message = buildString {
            append("${platform.name}: cached $downloaded cover")
            if (downloaded != 1) append("s")
            append(".")
            if (skipped > 0) append(" Existing: $skipped.")
            if (notFound > 0) append(" Not found: $notFound.")
            if (errors > 0) append(" Errors: $errors.")
            if (metaEntries.isNotEmpty()) append(" Metadata: ${metaEntries.size}.")
        }
        return ArtworkScrapeResult(attempted, downloaded, skipped, notFound, errors, message)
    }

    private suspend fun scrapeDsess(
        platform: ArtworkScraperPlatform,
        onProgress: suspend (done: Int, total: Int) -> Unit,
    ): ArtworkScrapeResult {
        val configs = readDsessConfigs()
        if (configs.isEmpty()) {
            return ArtworkScrapeResult(
                attempted = 0,
                downloaded = 0,
                skippedExisting = 0,
                notFound = 0,
                errors = 0,
                message = "DSESS config missing. Add BOX_ART lines to Config/ArtworkScraper/dsess.txt.",
            )
        }
        val roms = romsRepository.romsForArtwork(platform.tag)
        return scrapeRoms(platform, roms, onProgress) { rom, artDir ->
            val tags = dsessTags(platform, rom)
            for (config in configs) {
                val outcome = scrapeDsessRom(config, tags, rom, artDir)
                if (outcome == ScrapeOutcome.Downloaded) return@scrapeRoms outcome
            }
            ScrapeOutcome.NotFound
        }
    }

    private suspend fun scrapeScreenScraper(
        platform: ArtworkScraperPlatform,
        onProgress: suspend (done: Int, total: Int) -> Unit,
        saveMeta: Boolean = true,
    ): ArtworkScrapeResult {
        val systemId = ScreenScraperPlatformIds.map[platform.tag]
            ?: return ArtworkScrapeResult(
                attempted = 0, downloaded = 0, skippedExisting = 0,
                notFound = 0, errors = 0,
                message = "No ScreenScraper platform ID mapped for ${platform.name}.",
            )
        val credsFile = File(paths.configDir, "ArtworkScraper/screenscraper_credentials.txt")
        val (devId, devPassword) = if (credsFile.isFile) {
            val creds = credsFile.readLines().associate {
                val parts = it.split("=", limit = 2)
                parts[0].trim() to (parts.getOrNull(1)?.trim().orEmpty())
            }
            creds["devid"].orEmpty() to creds["password"].orEmpty()
        } else {
            BUILTIN_SCR_DEVID to BUILTIN_SCR_DEVPASSWORD
        }
        val roms = romsRepository.romsForArtwork(platform.tag)
        if (roms.isEmpty()) {
            return ArtworkScrapeResult(0, 0, 0, 0, 0, "No ROMs found for ${platform.name}.")
        }
        val artDir = paths.artFor(platform.tag)
        artDir.mkdirs()
        var skipped = 0
        var downloaded = 0
        var notFound = 0
        var errors = 0
        val metaEntries = mutableMapOf<String, GameMetadata>()
        roms.forEachIndexed { index, rom ->
            onProgress(index + 1, roms.size)
            if (cachedArtworkExists(artDir, rom.path.nameWithoutExtension)) {
                skipped++
                return@forEachIndexed
            }
            val (outcome, meta) = try {
                scrapeScreenScraperRom(devId, devPassword, systemId, rom, artDir)
            } catch (_: Throwable) {
                ScrapeOutcome.Error to null
            }
            when (outcome) {
                ScrapeOutcome.Downloaded -> {
                    downloaded++
                    if (saveMeta && meta != null) metaEntries[rom.path.nameWithoutExtension] = meta
                }
                ScrapeOutcome.NotFound -> notFound++
                ScrapeOutcome.Error -> errors++
            }
        }
        if (metaEntries.isNotEmpty()) {
            try { gamelistXmlManager.saveAll(platform.tag, metaEntries) } catch (_: Throwable) {}
        }
        artworkLookup.invalidate(platform.tag)
        romsRepository.invalidateGamelistCache(platform.tag)
        val attempted = roms.size - skipped
        val message = buildString {
            append("${platform.name}: cached $downloaded cover")
            if (downloaded != 1) append("s")
            append(".")
            if (skipped > 0) append(" Existing: $skipped.")
            if (notFound > 0) append(" Not found: $notFound.")
            if (errors > 0) append(" Errors: $errors.")
            if (metaEntries.isNotEmpty()) append(" Metadata: ${metaEntries.size}.")
        }
        return ArtworkScrapeResult(attempted, downloaded, skipped, notFound, errors, message)
    }

    private fun scrapeScreenScraperRom(
        devId: String,
        devPassword: String,
        systemId: Int,
        rom: Rom,
        artDir: File,
    ): Pair<ScrapeOutcome, GameMetadata?> {
        for (name in titleCandidates(rom)) {
            val encodedName = queryEncode(name)
            val url = URL(
                "https://www.screenscraper.fr/api2/jeuInfos.php" +
                    "?devid=${queryEncode(devId)}" +
                    "&devpassword=${queryEncode(devPassword)}" +
                    "&softname=Karipap" +
                    "&output=xml" +
                    "&romnom=$encodedName" +
                    "&systemeid=$systemId" +
                    "&ssid=&sspassword="
            )
            val body = fetchText(url, "text/xml", checkRobots = false) ?: continue
            val doc = try {
                val factory = DocumentBuilderFactory.newInstance()
                factory.newDocumentBuilder().parse(ByteArrayInputStream(body.toByteArray(StandardCharsets.UTF_8)))
            } catch (_: Throwable) {
                continue
            }
            doc.documentElement.normalize()
            val jeu = doc.getElementsByTagName("jeu").item(0) as? Element ?: continue

            val ssName = firstXmlChildText(jeu, "noms", "nom", "region")

            val synopsisEl = firstDirectChild(jeu, "synopsis")
            val desc = firstXmlChildText(synopsisEl, null, "synopsis", "langue")
                ?: synopsisEl?.textContent?.trim()?.takeIf { it.isNotEmpty() }

            val datesEl = firstDirectChild(jeu, "dates")
            val releaseDate = firstXmlChildText(datesEl, null, "date", "region")
                ?: datesEl?.textContent?.trim()?.takeIf { it.isNotEmpty() }

            val developer = firstDirectChild(jeu, "developpeur")?.textContent?.trim()?.takeIf { it.isNotEmpty() }
            val publisher = firstDirectChild(jeu, "editeur")?.textContent?.trim()?.takeIf { it.isNotEmpty() }

            val genresEl = firstDirectChild(jeu, "genres")
            val genre = firstXmlChildText(genresEl, null, "genre", "langue")
                ?: genresEl?.textContent?.trim()?.takeIf { it.isNotEmpty() }

            val players = firstDirectChild(jeu, "joueurs")?.textContent?.trim()?.takeIf { it.isNotEmpty() }

            val noteEl = firstDirectChild(jeu, "note")
            val noteValue = noteEl?.textContent?.trim()?.toIntOrNull()
            val rating = if (noteValue != null && noteValue > 0) noteValue / 20f else null

            val medias = jeu.getElementsByTagName("medias").item(0) as? Element
            val imageUrl = findScreenScraperMedia(medias) ?: continue

            val ext = imageExtension(URL(imageUrl))
            val dest = File(artDir, "${rom.path.nameWithoutExtension}.$ext")
            if (!downloadImage(URL(imageUrl), dest, "ScreenScraper", imageUrl, checkRobots = true)) {
                return ScrapeOutcome.NotFound to null
            }

            val meta = GameMetadata(
                name = ssName,
                desc = desc,
                rating = rating,
                releaseDate = releaseDate,
                developer = developer,
                publisher = publisher,
                genre = genre,
                players = players,
                imagePath = "./${rom.path.nameWithoutExtension}.$ext",
            )
            return ScrapeOutcome.Downloaded to meta
        }
        return ScrapeOutcome.NotFound to null
    }

    private fun findScreenScraperMedia(medias: Element?): String? {
        if (medias == null) return null
        val mediaList = medias.getElementsByTagName("media")
        if (mediaList.length == 0) return null
        for (type in listOf("box-2D", "box-texture", "ss", "sstitle", "screenmarquee")) {
            for (region in listOf("us", "wor", "cus", "jp", "eu", "ss")) {
                for (i in 0 until mediaList.length) {
                    val m = mediaList.item(i) as? Element ?: continue
                    if (m.getAttribute("type") == type && m.getAttribute("region") == region) {
                        val text = m.textContent.trim()
                        if (text.isNotEmpty() && text.startsWith("http")) return text
                    }
                }
            }
        }
        val first = (mediaList.item(0) as? Element)?.textContent?.trim()
        return if (first.isNullOrEmpty() || !first.startsWith("http")) null else first
    }

    private fun firstDirectChild(parent: Element?, tagName: String): Element? {
        if (parent == null) return null
        val list = parent.getElementsByTagName(tagName)
        for (i in 0 until list.length) {
            val child = list.item(i) as? Element ?: continue
            if (child.parentNode == parent) return child
        }
        return null
    }

    private fun firstXmlChildText(parent: Element?, wrapperTag: String?, childTag: String, attr: String?): String? {
        if (parent == null) return null
        val wrapper = if (wrapperTag != null) firstDirectChild(parent, wrapperTag) ?: return null
        else parent
        val nodes: NodeList = wrapper.getElementsByTagName(childTag)
        if (nodes.length == 0) return null
        if (attr == null) return nodes.item(0).textContent?.trim()?.takeIf { it.isNotEmpty() }
        val regionOrder = listOf("us", "wor", "cus", "jp", "eu", "ss", "en")
        for (region in regionOrder) {
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as? Element ?: continue
                if (el.getAttribute(attr).equals(region, ignoreCase = true)) {
                    return el.textContent.trim().takeIf { it.isNotEmpty() }
                }
            }
        }
        return nodes.item(0).textContent?.trim()?.takeIf { it.isNotEmpty() }
    }

    private suspend fun scrapeRoms(
        platform: ArtworkScraperPlatform,
        roms: List<Rom>,
        onProgress: suspend (done: Int, total: Int) -> Unit,
        scraper: (Rom, File) -> ScrapeOutcome,
    ): ArtworkScrapeResult {
        if (roms.isEmpty()) {
            return ArtworkScrapeResult(0, 0, 0, 0, 0, "No ROMs found for ${platform.name}.")
        }
        val artDir = paths.artFor(platform.tag)
        artDir.mkdirs()
        var skipped = 0
        var downloaded = 0
        var notFound = 0
        var errors = 0
        roms.forEachIndexed { index, rom ->
            onProgress(index + 1, roms.size)
            if (cachedArtworkExists(artDir, rom.path.nameWithoutExtension)) {
                skipped++
                return@forEachIndexed
            }
            when (try { scraper(rom, artDir) } catch (_: Throwable) { ScrapeOutcome.Error }) {
                ScrapeOutcome.Downloaded -> downloaded++
                ScrapeOutcome.NotFound -> notFound++
                ScrapeOutcome.Error -> errors++
            }
        }
        artworkLookup.invalidate(platform.tag)
        val attempted = roms.size - skipped
        val message = buildString {
            append("${platform.name}: cached $downloaded cover")
            if (downloaded != 1) append("s")
            append(".")
            if (skipped > 0) append(" Existing: $skipped.")
            if (notFound > 0) append(" Not found: $notFound.")
            if (errors > 0) append(" Errors: $errors.")
        }
        return ArtworkScrapeResult(attempted, downloaded, skipped, notFound, errors, message)
    }

    private fun scrapeTheGamesDbRomWithMeta(
        apiKey: String,
        platformId: Int?,
        rom: Rom,
        artDir: File,
    ): Pair<ScrapeOutcome, GameMetadata?> {
        for (title in titleCandidates(rom)) {
            val url = buildString {
                append("https://api.thegamesdb.net/v1.1/Games/ByGameName")
                append("?apikey=").append(queryEncode(apiKey))
                append("&name=").append(queryEncode(title))
                append("&include=boxart,platform")
                append("&fields=overview,release_date,players,rating,developers,publishers,genres")
                append("&mode=natural")
                if (platformId != null) append("&filter%5Bplatform%5D=").append(platformId)
            }
            val body = fetchText(URL(url), "application/json", checkRobots = false) ?: continue
            val root = JSONObject(body)
            if (root.optInt("code") == 403) return ScrapeOutcome.Error to null
            val games = root.optJSONObject("data")?.optJSONArray("games")
            if (games == null || games.length() == 0) continue
            val selectedIdx = (0 until games.length()).firstOrNull { idx ->
                val g = games.optJSONObject(idx)
                platformId == null || g.optInt("platform") == platformId
            } ?: 0
            val selected = games.optJSONObject(selectedIdx) ?: continue
            val selectedId = selected.optInt("id", 0)
            if (selectedId == 0) continue

            val overview = selected.optString("overview", "").trim().takeIf { it.isNotEmpty() }
            val releaseDate = selected.optString("release_date", "").trim().takeIf { it.isNotEmpty() }
            val players = selected.optInt("players", 0).let { if (it > 0) it.toString() else null }
            val rating = selected.optDouble("rating", -1.0).let { if (it >= 0) it.toFloat() else null }
            val devArr = selected.optJSONArray("developers")
            val developerIds = if (devArr != null) (0 until devArr.length()).mapNotNull { devArr.optInt(it) } else emptyList()
            val pubArr = selected.optJSONArray("publishers")
            val publisherIds = if (pubArr != null) (0 until pubArr.length()).mapNotNull { pubArr.optInt(it) } else emptyList()
            val genArr = selected.optJSONArray("genres")
            val genreIds = if (genArr != null) (0 until genArr.length()).mapNotNull { genArr.optInt(it) } else emptyList()

            val boxart = root.optJSONObject("include")?.optJSONObject("boxart") ?: continue
            val baseUrl = boxart.optJSONObject("base_url")
                ?.let { it.optString("medium").ifEmpty { it.optString("original") }.ifEmpty { it.optString("thumb") } }
                ?: continue
            val artEntries = boxart.optJSONObject("data")?.optJSONArray(selectedId.toString()) ?: continue
            val filename = (0 until artEntries.length())
                .mapNotNull { artEntries.optJSONObject(it) }
                .firstOrNull {
                    it.optString("type") == "boxart" &&
                        (it.optString("side").isEmpty() || it.optString("side") == "front")
                }
                ?.optString("filename")
                ?.takeIf { it.isNotEmpty() }
                ?: continue
            val imageUrl = URL(baseUrl + filename)
            val ext = imageExtension(imageUrl)
            val dest = File(artDir, "${rom.path.nameWithoutExtension}.$ext")
            if (!downloadImage(imageUrl, dest, "TheGamesDB", imageUrl.toString(), checkRobots = false)) {
                return ScrapeOutcome.NotFound to null
            }

            val resourceDevs = resolveTheGamesDbResources(apiKey, "Developers", developerIds)
            val resourcePubs = resolveTheGamesDbResources(apiKey, "Publishers", publisherIds)
            val resourceGenres = resolveTheGamesDbResources(apiKey, "Genres", genreIds)

            val meta = GameMetadata(
                name = selected.optString("game_title", "").trim().takeIf { it.isNotEmpty() },
                desc = overview,
                rating = rating,
                releaseDate = releaseDate,
                developer = resourceDevs.ifEmpty { null },
                publisher = resourcePubs.ifEmpty { null },
                genre = resourceGenres.ifEmpty { null },
                players = players,
                imagePath = "./${rom.path.nameWithoutExtension}.$ext",
            )
            return ScrapeOutcome.Downloaded to meta
        }
        return ScrapeOutcome.NotFound to null
    }

    private fun resolveTheGamesDbResources(apiKey: String, resourceType: String, ids: List<Int>): String {
        if (ids.isEmpty()) return ""
        val cacheDir = File(paths.configDir, "ArtworkScraper/thegamesdb")
        cacheDir.mkdirs()
        val cacheFile = File(cacheDir, "${resourceType.lowercase(Locale.US)}.json")
        val cached: Map<Int, String> = if (cacheFile.isFile) {
            try {
                val json = JSONObject(cacheFile.readText())
                json.keys().asSequence().associate { it.toInt() to json.optString(it) }
            } catch (_: Throwable) { emptyMap() }
        } else emptyMap()
        val missing = ids.filter { it !in cached }
        if (missing.isEmpty()) {
            return ids.mapNotNull { cached[it] }.joinToString(", ")
        }
        try {
            val url = URL(
                "https://api.thegamesdb.net/v1.1/$resourceType" +
                    "?apikey=${queryEncode(apiKey)}&id=${missing.joinToString(",")}"
            )
            val body = fetchText(url, "application/json", checkRobots = false)
            if (body != null) {
                val root = JSONObject(body)
                val data = root.optJSONObject("data")?.optJSONObject(resourceType.lowercase(Locale.US))
                val freshEntries = mutableMapOf<Int, String>()
                if (data != null) {
                    val keys = data.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val id = key.toIntOrNull() ?: continue
                        val obj = data.optJSONObject(key)
                        val name = obj?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }
                        if (name != null) freshEntries[id] = name
                    }
                }
                val merged = cached.toMutableMap()
                merged.putAll(freshEntries)
                try {
                    val out = JSONObject()
                    merged.forEach { (k, v) -> out.put(k.toString(), v) }
                    cacheFile.writeText(out.toString())
                } catch (_: Throwable) {}
                return ids.mapNotNull { merged[it] ?: cached[it] }.joinToString(", ")
            }
        } catch (_: Throwable) {}
        return ids.mapNotNull { cached[it] }.joinToString(", ")
    }

    private fun scrapeTheGamesDbRom(
        apiKey: String,
        platformId: Int?,
        rom: Rom,
        artDir: File,
    ): ScrapeOutcome {
        return scrapeTheGamesDbRomWithMeta(apiKey, platformId, rom, artDir).first
    }

    private fun resolveTheGamesDbPlatformId(apiKey: String, platform: ArtworkScraperPlatform): Int? {
        val names = listOfNotNull(theGamesDbPlatformNames[platform.tag], platform.name).distinct()
        for (name in names) {
            val url = URL(
                "https://api.thegamesdb.net/v1/Platforms/ByPlatformName" +
                    "?apikey=${queryEncode(apiKey)}&name=${queryEncode(name)}"
            )
            val body = fetchText(url, "application/json", checkRobots = false) ?: continue
            val platforms = JSONObject(body).optJSONObject("data")?.optJSONArray("platforms") ?: continue
            val normalizedTarget = normalizeTitle(name)
            val exact = (0 until platforms.length())
                .mapNotNull { platforms.optJSONObject(it) }
                .firstOrNull { normalizeTitle(it.optString("name")) == normalizedTarget }
            val chosen = exact ?: platforms.optJSONObject(0)
            val id = chosen?.optInt("id", 0) ?: 0
            if (id > 0) return id
        }
        return null
    }

    private fun selectTheGamesDbGameId(games: org.json.JSONArray, title: String, platformId: Int?): Int? {
        val normalized = normalizeTitle(title)
        val candidates = (0 until games.length()).mapNotNull { games.optJSONObject(it) }
        val exact = candidates.firstOrNull { game ->
            normalizeTitle(game.optString("game_title")) == normalized &&
                (platformId == null || game.optInt("platform") == platformId)
        }
        val platformMatch = candidates.firstOrNull { platformId == null || it.optInt("platform") == platformId }
        return (exact ?: platformMatch ?: candidates.firstOrNull())?.optInt("id", 0)?.takeIf { it > 0 }
    }

    private fun scrapeLibretroRom(
        repoName: String,
        rom: Rom,
        artDir: File,
        sourceName: String,
    ): ScrapeOutcome {
        val baseName = rom.path.nameWithoutExtension
        for (name in titleCandidates(rom)) {
            val remoteName = sanitizeLibretroName(name)
            val url = URL(
                "https://thumbnails.libretro.com/${encodePathSegment(repoName)}/Named_Boxarts/" +
                    "${encodePathSegment(remoteName)}.png"
            )
            val dest = File(artDir, "$baseName.png")
            if (downloadImage(url, dest, sourceName, url.toString(), checkRobots = true)) {
                return ScrapeOutcome.Downloaded
            }
        }
        return ScrapeOutcome.NotFound
    }

    private fun scrapeDsessRom(
        config: DsessConfig,
        tags: Map<String, String>,
        rom: Rom,
        artDir: File,
    ): ScrapeOutcome {
        if (!config.requiredTags.all { tags.containsKey(it) }) return ScrapeOutcome.NotFound
        val imageUrl = resolveDsessImage(config, tags) ?: return ScrapeOutcome.NotFound
        val ext = imageExtension(imageUrl)
        val dest = File(artDir, "${rom.path.nameWithoutExtension}.$ext")
        return if (downloadImage(imageUrl, dest, config.sourceName, imageUrl.toString(), checkRobots = true)) {
            ScrapeOutcome.Downloaded
        } else {
            ScrapeOutcome.NotFound
        }
    }

    private fun readDsessConfigs(): List<DsessConfig> {
        val file = File(paths.configDir, "ArtworkScraper/dsess.txt")
        if (!file.isFile) return emptyList()
        return file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line -> parseDsessConfig(line, "DSESS HTML scraper") }
    }

    private fun parseDsessConfig(line: String, sourceName: String): DsessConfig? {
        val parts = line.split(":", limit = 4)
        if (parts.size != 4 || parts[0] != "DSESS" || parts[1] != "BOX_ART") return null
        val tags = Regex("""TAGS\(([^)]*)\)""").find(parts[2])
            ?.groupValues?.getOrNull(1)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        return DsessConfig(requiredTags = tags, urlTemplate = parts[3], sourceName = sourceName)
    }

    private fun daijishoScraperSpecs(platform: ArtworkScraperPlatform): List<DaijishoScraperSpec> {
        val fileName = daijishoPlatformFiles[platform.tag]
        val specs = mutableListOf<DaijishoScraperSpec>()
        if (fileName != null) {
            readDaijishoPlatformJson(fileName)
                ?.optJSONArray("scraperSourceList")
                ?.let { sources ->
                    for (index in 0 until sources.length()) {
                        parseDaijishoScraperSpec(sources.optString(index))?.let { specs += it }
                    }
                }
        }
        specs += fallbackDaijishoScraperSpecs(platform)
        return specs.distinctBy { it.identity }
    }

    private fun parseDaijishoScraperSpec(line: String): DaijishoScraperSpec? {
        return when {
            line.startsWith("LIBRETRO:") -> {
                val repoName = line.substringAfter("LIBRETRO:").trim()
                if (repoName.isEmpty()) null else DaijishoScraperSpec.Libretro(repoName)
            }
            line.startsWith("DSESS:") -> {
                parseDsessConfig(line, "Daijisho DSESS scraper")
                    ?.let { DaijishoScraperSpec.Dsess(it) }
            }
            else -> null
        }
    }

    private fun fallbackDaijishoScraperSpecs(platform: ArtworkScraperPlatform): List<DaijishoScraperSpec> {
        val specs = mutableListOf<DaijishoScraperSpec>()
        libretroThumbnailRepos[platform.tag]?.let { repoName ->
            specs += DaijishoScraperSpec.Libretro(repoName)
        }
        daijishoOpenRetroSlugs[platform.tag]?.let { slug ->
            openRetroDsessConfig(slug)?.let { config ->
                specs += DaijishoScraperSpec.Dsess(config)
            }
        }
        return specs
    }

    private fun openRetroDsessConfig(slug: String): DsessConfig? {
        return parseDsessConfig(
            "DSESS:BOX_ART:TAGS(scraperKeyword):" +
                "https://openretro.org/browse/$slug?q=%7BscraperKeyword%7D" +
                "&dsess_target_site_selector=div.game_box+a" +
                "&dsess_target_site=%5E.%2Agame.%2A" +
                "&dsess_selector=img.game-cover%5Balt%3D%22front+image%22%5D" +
                "&dsess_attribute=src" +
                "&dsess_extractor=%5E%28%5B%5E%3F%5D%2A%29%3F.%2A.%2A",
            "Daijisho OpenRetro scraper",
        )
    }

    private fun readDaijishoPlatformJson(fileName: String): JSONObject? {
        val cacheDir = File(paths.configDir, "ArtworkScraper/daijisho")
        val cacheFile = File(cacheDir, fileName)
        val now = System.currentTimeMillis()
        if (cacheFile.isFile && now - cacheFile.lastModified() <= DAIJISHO_CACHE_MAX_AGE_MS) {
            parseJsonObject(cacheFile.readText())?.let { return it }
        }

        val url = URL(DAIJISHO_PLATFORM_BASE_URL + encodePathSegment(fileName))
        val fetched = try {
            fetchText(url, "application/json", checkRobots = false)
        } catch (_: Throwable) {
            null
        }
        if (!fetched.isNullOrBlank()) {
            parseJsonObject(fetched)?.let { json ->
                try {
                    cacheDir.mkdirs()
                    cacheFile.writeText(fetched)
                } catch (_: Throwable) {
                    // Best-effort cache only.
                }
                return json
            }
        }

        return if (cacheFile.isFile) parseJsonObject(cacheFile.readText()) else null
    }

    private fun parseJsonObject(text: String): JSONObject? {
        return try { JSONObject(text) } catch (_: Throwable) { null }
    }

    private fun dsessTags(platform: ArtworkScraperPlatform, rom: Rom): Map<String, String> {
        val keyword = titleCandidates(rom).firstOrNull().orEmpty()
        return mapOf(
            "scraperKeyword" to keyword,
            "scraperKeywordNormalized" to normalizeTitle(keyword),
            "platform" to platform.tag,
            "platformName" to platform.name,
            "localeLanguage" to Locale.getDefault().language,
            "localeCountry" to Locale.getDefault().country,
        )
    }

    private fun resolveDsessImage(config: DsessConfig, tags: Map<String, String>): URL? {
        val taggedUrl = applyDsessTags(config.urlTemplate, tags)
        val (requestUrl, params) = stripDsessParams(taggedUrl)
        val html = fetchText(URL(requestUrl), "text/html", checkRobots = true) ?: return null
        val document = Jsoup.parse(html, requestUrl)

        val targetUrl = resolveDsessTarget(document, requestUrl, params, tags)
        val targetDocument = if (targetUrl == requestUrl) {
            document
        } else {
            val targetHtml = fetchText(URL(targetUrl), "text/html", checkRobots = true) ?: return null
            Jsoup.parse(targetHtml, targetUrl)
        }

        val selector = params["dsess_selector"].orEmpty()
        if (selector.isEmpty()) return null
        val element = targetDocument.select(selector).firstOrNull() ?: return null
        val attr = params["dsess_selector_attr"] ?: params["dsess_attribute"]
        var raw = if (attr.isNullOrEmpty()) element.text() else element.absUrl(attr).ifEmpty { element.attr(attr) }
        val extractor = params["dsess_selector_regex_extract"] ?: params["dsess_extractor"]
        if (!extractor.isNullOrEmpty()) {
            raw = try {
                Regex(extractor).find(raw)?.groupValues?.getOrNull(1) ?: raw
            } catch (_: Throwable) {
                raw
            }
        }
        val replacer = params["dsess_selector_regex_replace"] ?: params["dsess_replacer"]
        if (!replacer.isNullOrEmpty()) {
            raw = try {
                Regex(replacer).replace(
                    raw,
                    params["dsess_selector_regex_replace_by"] ?: params["dsess_replacer_by"].orEmpty()
                )
            } catch (_: Throwable) {
                raw
            }
        }
        return targetDocument.resolveUrl(raw)
    }

    private fun resolveDsessTarget(
        document: org.jsoup.nodes.Document,
        requestUrl: String,
        params: Map<String, String>,
        tags: Map<String, String>,
    ): String {
        val targetRegex = params["dsess_target_site"]?.takeIf { it.isNotEmpty() }?.let {
            try { Regex(it) } catch (_: Throwable) { null }
        }
        val linkSelector = (params["dsess_target_selector"] ?: params["dsess_target_site_selector"])
            .orEmpty()
            .ifEmpty { "a" }
        val labelSelector = params["dsess_target_selector_label"] ?: params["dsess_target_site_selector_label"]
        val links = collectDsessTargetLinks(document, linkSelector, labelSelector)
        val matcherTerms = params
            .filterKeys { it.startsWith("dsess_target_ordered_matcher_") }
            .toSortedMap()
            .values
            .plus(tags["scraperKeyword"].orEmpty())
            .map { normalizeTitle(it) }
            .filter { it.isNotEmpty() }
            .distinct()
        val scoredLinks = links.map { candidate -> ScoredDsessTarget(candidate, scoreDsessTarget(candidate, matcherTerms)) }
        val regexMatches = scoredLinks.filter { (candidate, _) ->
            targetRegex?.containsMatchIn(candidate.url) ?: true
        }
        val match = (regexMatches.ifEmpty { scoredLinks })
            .sortedWith(
                compareByDescending<ScoredDsessTarget> { it.score }
                    .thenBy { normalizeTitle(it.target.label).length }
                    .thenBy { it.target.url.length }
            )
            .firstOrNull()
            ?.target
            ?.url
        return match ?: requestUrl
    }

    private fun collectDsessTargetLinks(
        document: org.jsoup.nodes.Document,
        linkSelector: String,
        labelSelector: String?,
    ): List<DsessTarget> {
        val explicit = linksForSelector(document, linkSelector, labelSelector)
        if (explicit.isNotEmpty()) return explicit
        return fallbackLinkSelectors(linkSelector)
            .asSequence()
            .flatMap { linksForSelector(document, it, labelSelector).asSequence() }
            .distinctBy { it.url }
            .toList()
    }

    private fun linksForSelector(
        document: org.jsoup.nodes.Document,
        selector: String,
        labelSelector: String?,
    ): List<DsessTarget> {
        return try {
            document.select(selector)
        } catch (_: Throwable) {
            emptyList()
        }.mapNotNull { element ->
            val href = element.absUrl("href").ifEmpty { element.attr("href") }
            if (href.isEmpty()) return@mapNotNull null
            val resolved = document.resolveUrl(href)?.toString() ?: return@mapNotNull null
            DsessTarget(resolved, dsessTargetLabel(element, labelSelector))
        }
    }

    private fun fallbackLinkSelectors(selector: String): List<String> {
        val trimmed = selector.trim()
        val selectors = mutableListOf<String>()
        if (trimmed.endsWith("+a")) {
            selectors += trimmed.removeSuffix("+a").trimEnd() + " a[href]"
        }
        if (trimmed.endsWith(">a")) {
            selectors += trimmed.removeSuffix(">a").trimEnd() + " a[href]"
        }
        if (trimmed.endsWith(" a")) {
            selectors += trimmed + "[href]"
        }
        selectors += "div.game_box a[href]"
        selectors += "a[href]"
        return selectors.distinct()
    }

    private fun dsessTargetLabel(element: org.jsoup.nodes.Element, labelSelector: String?): String {
        val selectedLabel = labelSelector
            ?.let { selector ->
                try { element.select(selector).text() } catch (_: Throwable) { "" }
            }
            .orEmpty()
            .trim()
        if (selectedLabel.isNotEmpty()) return selectedLabel
        val text = element.text().trim()
        if (text.isNotEmpty()) return text
        val imageAlt = element.select("img[alt]").firstOrNull()?.attr("alt").orEmpty().trim()
        if (imageAlt.isNotEmpty()) return imageAlt.removePrefix("Cover for").trim()
        return element.parents().firstOrNull { it.hasClass("game_box") }?.ownText().orEmpty().trim()
    }

    private fun scoreDsessTarget(target: DsessTarget, matcherTerms: List<String>): Int {
        if (matcherTerms.isEmpty()) return 0
        val label = normalizeTitle(target.label)
        val url = normalizeTitle(safeUrlDecode(target.url.substringAfterLast('/')))
        var score = 0
        for (term in matcherTerms) {
            when {
                label == term -> score += 100
                label.startsWith(term) -> score += 60
                label.contains(term) -> score += 30
                url == term -> score += 80
                url.contains(term) -> score += 20
            }
        }
        return score
    }

    private fun org.jsoup.nodes.Document.resolveUrl(raw: String): URL? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return try { URL(URL(this.location()), trimmed) } catch (_: Throwable) { null }
    }

    private fun applyDsessTags(template: String, tags: Map<String, String>): String {
        var out = template
        for ((key, value) in tags) {
            val encoded = queryEncode(value)
            out = out
                .replace("{$key}", encoded)
                .replace("%7B$key%7D", encoded)
                .replace("%7b$key%7d", encoded)
        }
        return out
    }

    private fun stripDsessParams(url: String): Pair<String, Map<String, String>> {
        val qIndex = url.indexOf('?')
        if (qIndex < 0) return url to emptyMap()
        val base = url.substring(0, qIndex)
        val query = url.substring(qIndex + 1)
        val kept = mutableListOf<String>()
        val dsess = linkedMapOf<String, String>()
        for (pair in query.split("&")) {
            if (pair.isEmpty()) continue
            val key = pair.substringBefore("=")
            val decodedKey = urlDecode(key)
            if (decodedKey.startsWith("dsess_")) {
                dsess[decodedKey] = urlDecode(pair.substringAfter("=", ""))
            } else {
                kept.add(pair)
            }
        }
        val cleaned = if (kept.isEmpty()) base else "$base?${kept.joinToString("&")}"
        return cleaned to dsess
    }

    private fun titleCandidates(rom: Rom): List<String> {
        val fileName = rom.path.nameWithoutExtension
        val displayName = rom.displayName
        val strippedDisplay = stripTitleDecorations(displayName)
        val strippedFile = stripTitleDecorations(fileName)
        return listOf(displayName, fileName, strippedDisplay, strippedFile)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun stripTitleDecorations(value: String): String {
        return value
            .replace(Regex("""\s*\[[^]]*]"""), "")
            .replace(Regex("""\s*\([^)]*\)"""), "")
            .trim()
    }

    private fun sanitizeLibretroName(value: String): String {
        return value.replace(Regex("""[&*/:`<>?\\|]"""), "_")
    }

    private fun normalizeTitle(value: String): String {
        return stripTitleDecorations(value)
            .lowercase(Locale.US)
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
    }

    private fun cachedArtworkExists(artDir: File, basename: String): Boolean {
        return artDir.listFiles { f -> f.isFile && f.nameWithoutExtension == basename }?.isNotEmpty() == true
    }

    private fun downloadImage(
        url: URL,
        dest: File,
        sourceName: String,
        sourceUrl: String,
        checkRobots: Boolean,
    ): Boolean {
        if (checkRobots && !robotsAllowed(url)) return false
        val conn = openGet(url, "image/*")
        try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return false
            if (code !in 200..299) throw IOException("HTTP $code")
            val type = conn.contentType.orEmpty().lowercase(Locale.US)
            if (!type.startsWith("image/")) return false
            val finalDest = destinationForContentType(dest, type)
            finalDest.parentFile?.mkdirs()
            val temp = File(finalDest.parentFile, "${finalDest.name}.download")
            conn.inputStream.use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output) }
            }
            if (temp.length() <= 0L) {
                temp.delete()
                return false
            }
            if (finalDest.exists()) finalDest.delete()
            if (finalDest != dest && dest.exists()) dest.delete()
            if (!temp.renameTo(finalDest)) {
                temp.copyTo(finalDest, overwrite = true)
                temp.delete()
            }
            writeAttribution(finalDest, sourceName, sourceUrl)
            return true
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchText(url: URL, accept: String, checkRobots: Boolean): String? {
        if (checkRobots && !robotsAllowed(url)) return null
        val conn = openGet(url, accept)
        try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (code !in 200..299) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun openGet(url: URL, accept: String): HttpURLConnection {
        throttle(url)
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", accept)
        }
    }

    private fun throttle(url: URL) {
        val key = "${url.protocol}://${url.host}:${effectivePort(url)}"
        synchronized(throttleLock) {
            val now = System.currentTimeMillis()
            val last = lastFetchMsByHost[key] ?: 0L
            val waitMs = 350L - (now - last)
            if (waitMs > 0L) Thread.sleep(waitMs)
            lastFetchMsByHost[key] = System.currentTimeMillis()
        }
    }

    private fun robotsAllowed(url: URL): Boolean {
        val key = "${url.protocol}://${url.host}:${effectivePort(url)}"
        val rules = robotsCache.getOrPut(key) { loadRobotsRules(url) }
        return rules.allows(url.path.ifEmpty { "/" })
    }

    private fun loadRobotsRules(url: URL): RobotsRules {
        val robotsUrl = URL(url.protocol, url.host, url.port, "/robots.txt")
        return try {
            val conn = (robotsUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 5_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_NOT_FOUND) return RobotsRules(emptyList())
                if (code !in 200..299) return RobotsRules(listOf(RobotsRule(false, "/")))
                parseRobots(conn.inputStream.bufferedReader().use { it.readText() })
            } finally {
                conn.disconnect()
            }
        } catch (_: Throwable) {
            RobotsRules(listOf(RobotsRule(false, "/")))
        }
    }

    private fun parseRobots(text: String): RobotsRules {
        val rules = mutableListOf<RobotsRule>()
        var applies = false
        for (rawLine in text.lineSequence()) {
            val line = rawLine.substringBefore("#").trim()
            if (line.isEmpty() || !line.contains(":")) continue
            val key = line.substringBefore(":").trim().lowercase(Locale.US)
            val value = line.substringAfter(":").trim()
            when (key) {
                "user-agent" -> applies = value == "*" || value.contains("Karipap", ignoreCase = true)
                "allow" -> if (applies && value.isNotEmpty()) rules.add(RobotsRule(true, value))
                "disallow" -> if (applies && value.isNotEmpty()) rules.add(RobotsRule(false, value))
            }
        }
        return RobotsRules(rules)
    }

    private fun writeAttribution(dest: File, sourceName: String, sourceUrl: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        File(dest.parentFile, "${dest.nameWithoutExtension}.source.txt").writeText(
            "Source: $sourceName\nURL: $sourceUrl\nCached: $stamp\n"
        )
    }

    private fun imageExtension(url: URL): String {
        val ext = url.path.substringAfterLast('.', "jpg").lowercase(Locale.US)
        return if (ext in imageExtensions) ext else "jpg"
    }

    private fun destinationForContentType(dest: File, contentType: String): File {
        val ext = when (contentType.substringBefore(";").trim().lowercase(Locale.US)) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> null
        } ?: return dest
        return if (dest.extension.lowercase(Locale.US) == ext) {
            dest
        } else {
            File(dest.parentFile, "${dest.nameWithoutExtension}.$ext")
        }
    }

    private fun encodePathSegment(value: String): String = queryEncode(value).replace("+", "%20")
    private fun queryEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun urlDecode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    private fun safeUrlDecode(value: String): String = try { urlDecode(value) } catch (_: Throwable) { value }
    private fun effectivePort(url: URL): Int = if (url.port > 0) url.port else url.defaultPort

    private enum class ScrapeOutcome { Downloaded, NotFound, Error }

    private data class DsessConfig(
        val requiredTags: List<String>,
        val urlTemplate: String,
        val sourceName: String,
    )

    private data class DsessTarget(
        val url: String,
        val label: String,
    )

    private data class ScoredDsessTarget(
        val target: DsessTarget,
        val score: Int,
    )

    private sealed class DaijishoScraperSpec {
        data class Libretro(val repoName: String) : DaijishoScraperSpec()
        data class Dsess(val config: DsessConfig) : DaijishoScraperSpec()

        val identity: String
            get() = when (this) {
                is Libretro -> "LIBRETRO:$repoName"
                is Dsess -> "DSESS:${config.urlTemplate}"
            }
    }

    private data class RobotsRule(val allow: Boolean, val path: String)

    private class RobotsRules(private val rules: List<RobotsRule>) {
        fun allows(path: String): Boolean {
            val matching = rules.filter { path.startsWith(it.path) }
            if (matching.isEmpty()) return true
            val longest = matching.maxOf { it.path.length }
            return matching.filter { it.path.length == longest }.any { it.allow }
        }
    }

    private companion object {
        private val SCR_DEVID_ENC = intArrayOf(91, 32, 7, 17)
        private val SCR_DEVPWD_ENC = intArrayOf(108, 28, 54, 55, 83, 43, 91, 44, 30, 22, 41, 12, 0, 108, 38, 29)
        private val SCR_KEY = intArrayOf(54, 73, 115, 100, 101, 67, 111, 107, 79, 66, 68, 66, 67, 56, 118, 77, 54, 88, 101, 54)

        private fun scramble(encoded: IntArray): String {
            return buildString(encoded.size) {
                for (i in encoded.indices) {
                    append((encoded[i] xor SCR_KEY[i % SCR_KEY.size]).toChar())
                }
            }
        }

        private val BUILTIN_SCR_DEVID: String by lazy { scramble(SCR_DEVID_ENC) }
        private val BUILTIN_SCR_DEVPASSWORD: String by lazy { scramble(SCR_DEVPWD_ENC) }
        private const val USER_AGENT = "KaripapArtworkScraper/1.0 (+local-cache)"
        private const val DAIJISHO_PLATFORM_BASE_URL =
            "https://raw.githubusercontent.com/TapiocaFox/Daijishou/main/platforms/"
        private const val DAIJISHO_CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L

        private val imageExtensions = setOf("jpg", "jpeg", "png", "webp")

        private val daijishoPlatformFiles = mapOf(
            "GB" to "NintendoGameBoy.json",
            "GBC" to "NintendoGameBoyColor.json",
            "GBA" to "NintendoGameBoyAdvance.json",
            "NES" to "NintendoEntertainmentSystem.json",
            "FDS" to "FamicomDiskSystem.json",
            "SNES" to "SuperNintendoEntertainmentSystem.json",
            "N64" to "Nintendo64.json",
            "NDS" to "NintendoDS.json",
            "3DS" to "Nintendo3DS.json",
            "VIRTUALBOY" to "VirtualBoy.json",
            "POKEMINI" to "PokemonMini.json",
            "GC" to "NintendoGameCube.json",
            "WII" to "NintendoWii.json",
            "WIIU" to "NintendoWiiU.json",
            "NSW" to "NintendoSwitch.json",
            "GG" to "SegaGameGear.json",
            "SMS" to "SegaMasterSystem.json",
            "MD" to "SegaGenesis.json",
            "SG1000" to "SegaSG1000.json",
            "32X" to "Sega32X.json",
            "SEGACD" to "SegaCD.json",
            "SATURN" to "SegaSaturn.json",
            "DC" to "Dreamcast.json",
            "PS" to "SonyPlayStation.json",
            "PS2" to "SonyPlayStation2.json",
            "PSP" to "PlayStationPortable.json",
            "PS3" to "SonyPlayStation3.json",
            "PSVITA" to "SonyPSVita.json",
            "LYNX" to "AtariLynx.json",
            "JAGUAR" to "AtariJaguar.json",
            "ATARI2600" to "Atari2600.json",
            "ATARI5200" to "Atari5200.json",
            "ATARI7800" to "Atari7800.json",
            "PCE" to "TurboGrafx16.json",
            "SUPERGRAFX" to "SuperGrafx.json",
            "PCFX" to "NECPCFX.json",
            "NGP" to "NeoGeoPocket.json",
            "NGPC" to "NeoGeoPocketColor.json",
            "NEOGEO" to "NeoGeo.json",
            "WS" to "WonderSwan.json",
            "WSC" to "WonderSwanColor.json",
            "MAME" to "ArcadeMAME.json",
            "FBN" to "ArcadeFinalBurnNeo.json",
            "COLECOVISION" to "ColecoVision.json",
            "VECTREX" to "Vectrex.json",
            "INTELLIVISION" to "Intellivision.json",
            "AMIGA" to "CommodoreAmiga.json",
            "DOS" to "DOS.json",
            "SCUMMVM" to "ScummVM.json",
        )

        private val daijishoOpenRetroSlugs = mapOf(
            "GB" to "gb",
            "GBC" to "gbc",
            "GBA" to "gba",
            "NES" to "nes",
            "FDS" to "fds",
            "SNES" to "snes",
            "N64" to "n64",
            "NDS" to "nds",
            "3DS" to "3ds",
            "VIRTUALBOY" to "virtualboy",
            "POKEMINI" to "pokemon-mini",
            "GC" to "ngc",
            "WII" to "wii",
            "WIIU" to "wiiu",
            "NSW" to "switch",
            "GG" to "sgg",
            "SMS" to "sms",
            "MD" to "smd",
            "SG1000" to "sg1000",
            "32X" to "32x",
            "SEGACD" to "segacd",
            "SATURN" to "saturn",
            "DC" to "dreamcast",
            "PS" to "psx",
            "PS2" to "ps2",
            "PSP" to "psp",
            "PS3" to "ps3",
            "PSVITA" to "psvita",
            "LYNX" to "lynx",
            "JAGUAR" to "jaguar",
            "ATARI2600" to "a2600",
            "ATARI5200" to "a5200",
            "ATARI7800" to "a7800",
            "PCE" to "tg16",
            "SUPERGRAFX" to "supergrafx",
            "PCFX" to "pcfx",
            "NGP" to "ngp",
            "NGPC" to "ngpc",
            "NEOGEO" to "neogeo",
            "WS" to "ws",
            "WSC" to "wsc",
            "MAME" to "arcade",
            "FBN" to "arcade",
            "COLECOVISION" to "coleco",
            "VECTREX" to "vectrex",
            "INTELLIVISION" to "intellivision",
            "AMIGA" to "amiga",
            "DOS" to "dos",
            "SCUMMVM" to "scummvm",
        )

        private val libretroThumbnailRepos = mapOf(
            "GB" to "Nintendo - Game Boy",
            "GBC" to "Nintendo - Game Boy Color",
            "GBA" to "Nintendo - Game Boy Advance",
            "NES" to "Nintendo - Nintendo Entertainment System",
            "FDS" to "Nintendo - Family Computer Disk System",
            "SNES" to "Nintendo - Super Nintendo Entertainment System",
            "N64" to "Nintendo - Nintendo 64",
            "NDS" to "Nintendo - Nintendo DS",
            "3DS" to "Nintendo - Nintendo 3DS",
            "VIRTUALBOY" to "Nintendo - Virtual Boy",
            "POKEMINI" to "Nintendo - Pokemon Mini",
            "GC" to "Nintendo - GameCube",
            "WII" to "Nintendo - Wii",
            "WIIU" to "Nintendo - Wii U",
            "GG" to "Sega - Game Gear",
            "SMS" to "Sega - Master System - Mark III",
            "MD" to "Sega - Mega Drive - Genesis",
            "SG1000" to "Sega - SG-1000",
            "32X" to "Sega - 32X",
            "SEGACD" to "Sega - Mega-CD - Sega CD",
            "SATURN" to "Sega - Saturn",
            "DC" to "Sega - Dreamcast",
            "PS" to "Sony - PlayStation",
            "PS2" to "Sony - PlayStation 2",
            "PSP" to "Sony - PlayStation Portable",
            "PS3" to "Sony - PlayStation 3",
            "PSVITA" to "Sony - PlayStation Vita",
            "LYNX" to "Atari - Lynx",
            "JAGUAR" to "Atari - Jaguar",
            "ATARI2600" to "Atari - 2600",
            "ATARI5200" to "Atari - 5200",
            "ATARI7800" to "Atari - 7800",
            "PCE" to "NEC - PC Engine - TurboGrafx 16",
            "SUPERGRAFX" to "NEC - PC Engine SuperGrafx",
            "PCFX" to "NEC - PC-FX",
            "NGP" to "SNK - Neo Geo Pocket",
            "NGPC" to "SNK - Neo Geo Pocket Color",
            "NEOGEO" to "SNK - Neo Geo",
            "WS" to "Bandai - WonderSwan",
            "WSC" to "Bandai - WonderSwan Color",
            "COLECOVISION" to "Coleco - ColecoVision",
            "VECTREX" to "GCE - Vectrex",
            "INTELLIVISION" to "Mattel - Intellivision",
            "AMIGA" to "Commodore - Amiga",
            "DOS" to "DOS",
            "SCUMMVM" to "ScummVM",
        )

        private val theGamesDbPlatformNames = mapOf(
            "MD" to "Sega Genesis",
            "PS" to "Sony Playstation",
            "PCE" to "TurboGrafx-16",
            "SEGACD" to "Sega CD",
            "SNES" to "Super Nintendo Entertainment System",
            "NES" to "Nintendo Entertainment System (NES)",
            "GB" to "Nintendo Game Boy",
            "GBC" to "Nintendo Game Boy Color",
            "GBA" to "Nintendo Game Boy Advance",
            "NDS" to "Nintendo DS",
            "3DS" to "Nintendo 3DS",
            "N64" to "Nintendo 64",
            "GC" to "Nintendo GameCube",
            "WII" to "Nintendo Wii",
            "WIIU" to "Nintendo Wii U",
            "PS2" to "Sony Playstation 2",
            "PS3" to "Sony Playstation 3",
            "PSP" to "Sony PSP",
            "PSVITA" to "Sony Playstation Vita",
        )
    }
}
