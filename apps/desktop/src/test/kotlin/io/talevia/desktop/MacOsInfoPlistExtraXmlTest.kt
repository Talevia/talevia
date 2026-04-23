package io.talevia.desktop

import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards [macos-info-plist-extra.xml] — the raw XML fragment Compose
 * Desktop's `nativeDistributions.macOS.infoPlist.extraKeysRawXml` injects
 * into the packaged `Info.plist`. A mis-balanced `<dict>` / typo'd key
 * used to surface only when a user double-clicked a `.talevia` bundle in
 * Finder and nothing happened. We catch it at test time by:
 *
 *  1. Loading the same file `build.gradle.kts` reads.
 *  2. Wrapping it in a minimal `<plist>…<dict>…</dict></plist>` envelope
 *     (the fragment is key/value pairs, not a full plist document).
 *  3. Parsing with `javax.xml.parsers.DocumentBuilder` — no DTD, no
 *     network fetch, pure well-formedness check (unbalanced tags,
 *     forbidden entities, malformed character data all fail loud).
 *  4. Walking the parsed DOM to assert the Launch-Services-critical
 *     keys actually exist and point at the right UTI / extension, so a
 *     refactor that accidentally drops `LSTypeIsPackage` or the
 *     `.talevia` tag also trips this test before a release cut.
 */
class MacOsInfoPlistExtraXmlTest {

    @Test
    fun `fragment wraps into a well-formed plist document`() {
        val wrapped = wrappedPlistDoc(rawFragment())
        val doc = parse(wrapped)
        val root = doc.documentElement
        assertEquals("plist", root.tagName)
        // Outer dict should carry the two top-level keys we promised macOS.
        val outerKeys = doc.getElementsByTagName("key")
        val keyValues = (0 until outerKeys.length).map { (outerKeys.item(it) as Element).textContent }
        assertTrue("UTExportedTypeDeclarations" in keyValues, "missing UTI declarations")
        assertTrue("CFBundleDocumentTypes" in keyValues, "missing CFBundleDocumentTypes")
    }

    @Test
    fun `fragment declares the io_talevia_project UTI against the talevia extension`() {
        val doc = parse(wrappedPlistDoc(rawFragment()))
        val strings = doc.getElementsByTagName("string")
        val stringContents = (0 until strings.length).map { (strings.item(it) as Element).textContent }
        assertTrue("io.talevia.project" in stringContents, "missing io.talevia.project UTI")
        assertTrue("talevia" in stringContents, "missing .talevia filename extension")
        assertTrue("com.apple.package" in stringContents, "missing com.apple.package conformance")
    }

    @Test
    fun `fragment keeps LSTypeIsPackage so Finder treats bundle as document`() {
        val doc = parse(wrappedPlistDoc(rawFragment()))
        // LSTypeIsPackage must exist as a <key> *immediately before* a
        // <true/> element — if some cycle accidentally replaces <true/> with
        // <string>true</string>, macOS parses it as a string key and
        // ignores it, defeating the whole Finder double-click story.
        val root = doc.documentElement
        val lsTypePackageTrue = findTrueAfterKey(root, "LSTypeIsPackage")
        assertNotNull(lsTypePackageTrue, "LSTypeIsPackage must be followed by <true/>")
    }

    @Test
    fun `malformed xml is rejected by the parser`() {
        // Positive control — the parser we rely on must actually refuse
        // broken input. If some future JVM replaces DocumentBuilder with a
        // permissive mode, this fires and we add `setFeature(...)` config.
        val broken = "<plist><dict><key>Unclosed</key></plist>"
        assertFailsWith<SAXParseException> { parse(broken) }
    }

    @Test
    fun `fragment has exactly one UTExportedTypeDeclarations and one CFBundleDocumentTypes`() {
        val doc = parse(wrappedPlistDoc(rawFragment()))
        val outerDict = doc.documentElement.firstElementChildNamed("dict")
        assertNotNull(outerDict)
        // Direct children: walk the key list. Launch Services silently
        // takes the last occurrence of a dup'd key, so multiple
        // UTExportedTypeDeclarations would compound bugs rather than fail
        // loud.
        val keyElements = outerDict.childElements().filter { it.tagName == "key" }
        val counts = keyElements.groupingBy { it.textContent }.eachCount()
        assertEquals(1, counts["UTExportedTypeDeclarations"] ?: 0)
        assertEquals(1, counts["CFBundleDocumentTypes"] ?: 0)
    }

    // ---- helpers ----

    private fun rawFragment(): String =
        // Must match the path read by apps/desktop/build.gradle.kts.
        java.io.File("src/main/resources/macos-info-plist-extra.xml").readText()

    private fun wrappedPlistDoc(fragment: String): String =
        """<?xml version="1.0" encoding="UTF-8"?>
        |<plist version="1.0">
        |  <dict>
        |$fragment
        |  </dict>
        |</plist>
        """.trimMargin()

    private fun parse(xml: String): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            // Keep the parser quiet and offline — no DTD fetch, no external
            // entity resolution. Well-formedness is the only invariant we
            // care about here.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isNamespaceAware = false
        }
        val builder = factory.newDocumentBuilder()
        return builder.parse(InputSource(StringReader(xml)))
    }

    private fun findTrueAfterKey(scope: Element, keyText: String): Element? {
        val keys = scope.getElementsByTagName("key")
        for (i in 0 until keys.length) {
            val k = keys.item(i) as Element
            if (k.textContent == keyText) {
                var sibling = k.nextSibling
                while (sibling != null && sibling.nodeType != org.w3c.dom.Node.ELEMENT_NODE) {
                    sibling = sibling.nextSibling
                }
                if (sibling is Element && sibling.tagName == "true") return sibling
            }
        }
        return null
    }

    private fun Element.firstElementChildNamed(name: String): Element? {
        var c = firstChild
        while (c != null) {
            if (c is Element && c.tagName == name) return c
            c = c.nextSibling
        }
        return null
    }

    private fun Element.childElements(): Sequence<Element> = sequence {
        var c = firstChild
        while (c != null) {
            if (c is Element) yield(c)
            c = c.nextSibling
        }
    }
}
