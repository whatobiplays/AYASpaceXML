package com.ayaspacexml.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamelistTransformTest {

    @Test
    fun `findBestMatch prefers exact stem over prefix matches`() {
        val result = MediaFileMatcher.findBestMatch(
            gameFileName = "Mario Kart DS",
            availableFileNames = listOf(
                "Mario Kart DS Deluxe.png",
                "Mario Kart DS.png"
            )
        )

        assertEquals("Mario Kart DS.png", result)
    }

    @Test
    fun `findBestMatch rejects ambiguous prefix matches`() {
        val result = MediaFileMatcher.findBestMatch(
            gameFileName = "Pokemon",
            availableFileNames = listOf(
                "Pokemon Blue.png",
                "Pokemon Red.png"
            )
        )

        assertEquals(null, result)
    }

    @Test
    fun `selectMedia falls back to screenshot when fanart is missing`() {
        val result = MediaFileMatcher.selectMedia(
            gameFileName = "Advance Wars",
            coverFileNames = listOf("Advance Wars.png"),
            fanartFileNames = emptyList(),
            screenshotFileNames = listOf("Advance Wars-screen.png")
        )

        assertEquals("Advance Wars.png", result.thumbnailFileName)
        assertEquals("Advance Wars-screen.png", result.imageFileName)
    }

    @Test
    fun `enrich replaces existing media tags with selected media`() {
        val xml = """
            <gameList>
              <game>
                <path>./roms/Mario Kart DS.nds</path>
                <thumbnail>./old/thumb.png</thumbnail>
                <image>./old/image.png</image>
                <name>Mario Kart DS</name>
              </game>
            </gameList>
        """.trimIndent()

        val enriched = GamelistTransform.enrich(xml) {
            SelectedMedia(
                thumbnailFileName = "Mario Kart DS.png",
                imageFileName = "Mario Kart DS-fanart.png"
            )
        }

        assertTrue(enriched.contains("./media/thumbnail/Mario Kart DS.png"))
        assertTrue(enriched.contains("./media/image/Mario Kart DS-fanart.png"))
        assertFalse(enriched.contains("./old/thumb.png"))
        assertFalse(enriched.contains("./old/image.png"))
    }
}
