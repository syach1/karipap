package dev.karipap.app.util

import dev.karipap.app.config.CannoliPaths
import dev.karipap.app.di.CannoliPathsProvider
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.File
import java.io.StringWriter
import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

data class GameMetadata(
    val name: String? = null,
    val desc: String? = null,
    val rating: Float? = null,
    val releaseDate: String? = null,
    val developer: String? = null,
    val publisher: String? = null,
    val genre: String? = null,
    val players: String? = null,
    val imagePath: String? = null,
)

@Singleton
class GamelistXmlManager @Inject constructor(
    private val pathsProvider: CannoliPathsProvider,
) {
    private val paths: CannoliPaths get() = CannoliPaths(pathsProvider.root)

    private fun gamelistFile(platformTag: String): File {
        val dir = File(paths.configDir, "gamelists/${platformTag}")
        dir.mkdirs()
        return File(dir, "gamelist.xml")
    }

    fun loadAll(platformTag: String): Map<String, GameMetadata> {
        val file = gamelistFile(platformTag)
        if (!file.isFile) return emptyMap()
        return try {
            parseGamelist(file)
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    fun load(platformTag: String, romBasename: String): GameMetadata? {
        return loadAll(platformTag)[romBasename]
    }

    fun save(platformTag: String, romBasename: String, metadata: GameMetadata) {
        val file = gamelistFile(platformTag)
        val all = if (file.isFile) {
            try { parseGamelist(file).toMutableMap() } catch (_: Throwable) { mutableMapOf() }
        } else {
            mutableMapOf()
        }
        all[romBasename] = metadata
        writeGamelist(file, all)
    }

    fun saveAll(platformTag: String, entries: Map<String, GameMetadata>) {
        val file = gamelistFile(platformTag)
        val all = if (file.isFile) {
            try { parseGamelist(file).toMutableMap() } catch (_: Throwable) { mutableMapOf() }
        } else {
            mutableMapOf()
        }
        all.putAll(entries)
        writeGamelist(file, all)
    }

    private fun parseGamelist(file: File): Map<String, GameMetadata> {
        val result = mutableMapOf<String, GameMetadata>()
        val factory = DocumentBuilderFactory.newInstance()
        val doc = factory.newDocumentBuilder().parse(file)
        doc.documentElement.normalize()
        val gameNodes = doc.getElementsByTagName("game")
        for (i in 0 until gameNodes.length) {
            val gameEl = gameNodes.item(i) as? Element ?: continue
            val path = getText(gameEl, "path") ?: continue
            val basename = File(path).nameWithoutExtension
            if (basename.isEmpty()) continue
            val ratingStr = getText(gameEl, "rating")
            result[basename] = GameMetadata(
                name = getText(gameEl, "name"),
                desc = getText(gameEl, "desc"),
                rating = ratingStr?.toFloatOrNull(),
                releaseDate = getText(gameEl, "releasedate"),
                developer = getText(gameEl, "developer"),
                publisher = getText(gameEl, "publisher"),
                genre = getText(gameEl, "genre"),
                players = getText(gameEl, "players"),
                imagePath = getText(gameEl, "image"),
            )
        }
        return result
    }

    private fun writeGamelist(file: File, entries: Map<String, GameMetadata>) {
        val factory = DocumentBuilderFactory.newInstance()
        val doc = factory.newDocumentBuilder().newDocument()
        val root = doc.createElement("gameList")
        doc.appendChild(root)

        for ((basename, meta) in entries) {
            val game = doc.createElement("game")
            root.appendChild(game)

            addElement(doc, game, "path", "./$basename")
            meta.name?.let { addElement(doc, game, "name", it) }
            meta.desc?.let { addElement(doc, game, "desc", it) }
            meta.rating?.let { addElement(doc, game, "rating", it.toString()) }
            meta.releaseDate?.let { addElement(doc, game, "releasedate", it) }
            meta.developer?.let { addElement(doc, game, "developer", it) }
            meta.publisher?.let { addElement(doc, game, "publisher", it) }
            meta.genre?.let { addElement(doc, game, "genre", it) }
            meta.players?.let { addElement(doc, game, "players", it) }
            meta.imagePath?.let { addElement(doc, game, "image", it) }
        }

        file.parentFile?.mkdirs()
        val writer = StringWriter()
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.INDENT, "yes")
            setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        }
        transformer.transform(DOMSource(doc), StreamResult(writer))
        file.writeText(writer.toString())
    }

    private fun addElement(doc: Document, parent: Element, tag: String, text: String) {
        val el = doc.createElement(tag)
        el.textContent = text
        parent.appendChild(el)
    }

    private fun getText(parent: Element, tag: String): String? {
        val list: NodeList = parent.getElementsByTagName(tag)
        if (list.length == 0) return null
        return list.item(0).textContent?.trim()?.takeIf { it.isNotEmpty() }
    }
}