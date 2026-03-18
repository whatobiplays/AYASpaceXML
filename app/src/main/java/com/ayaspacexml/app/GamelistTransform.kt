package com.ayaspacexml.app

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.xml.sax.InputSource

data class SelectedMedia(
    val thumbnailFileName: String? = null,
    val imageFileName: String? = null,
)

data class MediaSyncPlan(
    val xmlContent: String,
    val desiredThumbnailFileNames: Set<String>,
    val desiredImageFileNames: Set<String>,
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
        return buildMediaSyncPlan(xmlContent, resolveMedia).xmlContent
    }

    fun buildMediaSyncPlan(xmlContent: String, resolveMedia: (String) -> SelectedMedia): MediaSyncPlan {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setXIncludeAwareIfSupported(false)
            setExpandEntityReferencesIfSupported(false)
            setFeatureIfSupported("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeatureIfSupported("http://xml.org/sax/features/external-general-entities", false)
            setFeatureIfSupported("http://xml.org/sax/features/external-parameter-entities", false)
        }

        val document = parseAsFragment(factory, xmlContent)
        val games = document.getElementsByTagName("game")
        val desiredThumbnailFileNames = linkedSetOf<String>()
        val desiredImageFileNames = linkedSetOf<String>()

        for (index in 0 until games.length) {
            val game = games.item(index) as? Element ?: continue
            val gamePath = directChildText(game, "path")?.trim().orEmpty()
            if (gamePath.isBlank()) continue

            val gameFileName = MediaFileMatcher.extractGameFileName(gamePath)
            val selectedMedia = resolveMedia(gameFileName)

            removeDirectChildren(game, "image")
            removeDirectChildren(game, "thumbnail")

            selectedMedia.thumbnailFileName?.let {
                desiredThumbnailFileNames += it
                appendChildWithText(document, game, "thumbnail", "./media/thumbnail/$it")
            }
            selectedMedia.imageFileName?.let {
                desiredImageFileNames += it
                appendChildWithText(document, game, "image", "./media/image/$it")
            }
        }

        val transformer = TransformerFactory.newInstance().apply {
            setFeatureIfSupported(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        }.newTransformer().apply {
            setOutputProperty(OutputKeys.INDENT, "yes")
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        }

        return MediaSyncPlan(
            xmlContent = serializeDocumentFragment(document, transformer),
            desiredThumbnailFileNames = desiredThumbnailFileNames,
            desiredImageFileNames = desiredImageFileNames
        )
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

    private fun parseAsFragment(factory: DocumentBuilderFactory, xmlContent: String): org.w3c.dom.Document {
        val sanitizedXml = xmlContent
            .replace(Regex("^\\uFEFF"), "")
            .replace(Regex("<\\?xml[^>]*\\?>"), "")

        val wrappedXml = "<document-fragment>$sanitizedXml</document-fragment>"
        return factory.newDocumentBuilder().parse(InputSource(StringReader(wrappedXml)))
    }

    private fun serializeDocumentFragment(
        document: org.w3c.dom.Document,
        transformer: javax.xml.transform.Transformer
    ): String {
        val fragmentRoot = document.documentElement
        val output = StringBuilder()
        val childNodes = fragmentRoot.childNodes

        for (index in 0 until childNodes.length) {
            val child = childNodes.item(index)
            if (child.nodeType == Node.TEXT_NODE && child.textContent.isBlank()) {
                continue
            }

            val childText = StringWriter().use { writer ->
                transformer.transform(DOMSource(child), StreamResult(writer))
                writer.toString()
            }.removeXmlDeclaration()

            if (output.isNotEmpty() && !output.endsWith("\n") && childText.isNotBlank()) {
                output.append('\n')
            }
            output.append(childText)
        }

        return output.toString().trim()
    }

    private fun DocumentBuilderFactory.setFeatureIfSupported(name: String, value: Boolean) {
        try {
            setFeature(name, value)
        } catch (_: ParserConfigurationException) {
            // Android parser support varies by implementation; ignore unsupported hardening flags.
        }
    }

    private fun DocumentBuilderFactory.setXIncludeAwareIfSupported(value: Boolean) {
        try {
            isXIncludeAware = value
        } catch (_: UnsupportedOperationException) {
            // Some Android parser implementations do not support XInclude configuration.
        }
    }

    private fun DocumentBuilderFactory.setExpandEntityReferencesIfSupported(value: Boolean) {
        try {
            setExpandEntityReferences(value)
        } catch (_: UnsupportedOperationException) {
            // Some Android parser implementations do not support entity expansion configuration.
        }
    }

    private fun TransformerFactory.setFeatureIfSupported(name: String, value: Boolean) {
        try {
            setFeature(name, value)
        } catch (_: Exception) {
            // Ignore unsupported transformer flags and continue with the default implementation.
        }
    }

    private fun String.removeXmlDeclaration(): String =
        replaceFirst(Regex("^\\s*<\\?xml[^>]*\\?>\\s*"), "")
}
