package com.ayaspacexml.app

import org.w3c.dom.Element
import java.io.StringReader
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.xml.sax.InputSource

data class SelectedMedia(
    val thumbnailFileName: String? = null,
    val imageFileName: String? = null,
)

object MediaFileMatcher {
    fun extractGameFileName(path: String): String {
        val filename = path.substringAfterLast('/')
        return filename.substringBeforeLast('.')
    }

    fun selectMedia(
        gameFileName: String,
        coverFileNames: List<String>,
        fanartFileNames: List<String>,
        screenshotFileNames: List<String>
    ): SelectedMedia {
        val thumbnail = findBestMatch(gameFileName, coverFileNames)
        val fanart = findBestMatch(gameFileName, fanartFileNames)
        val screenshot = if (fanart == null) findBestMatch(gameFileName, screenshotFileNames) else null

        return SelectedMedia(
            thumbnailFileName = thumbnail,
            imageFileName = fanart ?: screenshot
        )
    }

    fun findBestMatch(gameFileName: String, availableFileNames: List<String>): String? {
        if (availableFileNames.isEmpty()) return null

        val exactMatches = availableFileNames.filter { stem(it).equals(gameFileName, ignoreCase = true) }
        if (exactMatches.isNotEmpty()) {
            return exactMatches.minByOrNull(String::length)
        }

        val prefixMatches = availableFileNames.filter { stem(it).startsWith(gameFileName, ignoreCase = true) }
        return if (prefixMatches.size == 1) prefixMatches.single() else null
    }

    private fun stem(fileName: String): String = fileName.substringBeforeLast('.')
}

object GamelistTransform {
    fun enrich(xmlContent: String, resolveMedia: (String) -> SelectedMedia): String {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }

        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xmlContent)))
        val games = document.getElementsByTagName("game")

        for (index in 0 until games.length) {
            val game = games.item(index) as? Element ?: continue
            val gamePath = directChildText(game, "path")?.trim().orEmpty()
            if (gamePath.isBlank()) continue

            val gameFileName = MediaFileMatcher.extractGameFileName(gamePath)
            val selectedMedia = resolveMedia(gameFileName)

            removeDirectChildren(game, "image")
            removeDirectChildren(game, "thumbnail")

            selectedMedia.thumbnailFileName?.let {
                appendChildWithText(document, game, "thumbnail", "./media/thumbnail/$it")
            }
            selectedMedia.imageFileName?.let {
                appendChildWithText(document, game, "image", "./media/image/$it")
            }
        }

        val transformer = TransformerFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        }.newTransformer().apply {
            setOutputProperty(OutputKeys.INDENT, "yes")
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        }

        return StringWriter().use { writer ->
            transformer.transform(DOMSource(document), StreamResult(writer))
            writer.toString()
        }
    }

    private fun directChildText(parent: Element, tagName: String): String? {
        val child = directChildren(parent, tagName).firstOrNull() ?: return null
        return child.textContent
    }

    private fun removeDirectChildren(parent: Element, tagName: String) {
        directChildren(parent, tagName).forEach(parent::removeChild)
    }

    private fun directChildren(parent: Element, tagName: String): List<Element> {
        val children = mutableListOf<Element>()
        val nodes = parent.childNodes
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            if (node is Element && node.tagName == tagName) {
                children += node
            }
        }
        return children
    }

    private fun appendChildWithText(
        document: org.w3c.dom.Document,
        parent: Element,
        tagName: String,
        text: String
    ) {
        val child = document.createElement(tagName)
        child.textContent = text
        parent.appendChild(child)
    }
}
