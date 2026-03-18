package com.ayaspacexml.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamelistTransformTest {

    @Test
    fun `findBestMatch prefers exact stem over prefix matches`() {
        val result = MediaFileMatcher.findBestMatch(
            gameFileName = "Ultimate Battles DS",
            availableFileNames = listOf(
                "Ultimate Battles DS Deluxe.png",
                "Ultimate Battles DS.png"
            )
        )

        assertEquals("Ultimate Battles DS.png", result)
    }

    @Test
    fun `findBestMatch rejects ambiguous prefix matches`() {
        val result = MediaFileMatcher.findBestMatch(
            gameFileName = "Ultimate",
            availableFileNames = listOf(
                "Ultimate Blue.png",
                "Ultimate Red.png"
            )
        )

        assertEquals(null, result)
    }

    @Test
    fun `selectMedia falls back to screenshot when fanart is missing`() {
        val result = MediaFileMatcher.selectMedia(
            gameFileName = "Burning Hit!",
            coverFileNames = listOf("Burning Hit!.png"),
            fanartFileNames = emptyList(),
            screenshotFileNames = listOf("Burning Hit!-screen.png")
        )

        assertEquals("Burning Hit!.png", result.thumbnailFileName)
        assertEquals("Burning Hit!-screen.png", result.imageFileName)
    }

    @Test
    fun `enrich replaces existing media tags with selected media`() {
        val xml = """
            <gameList>
              <game>
                <path>./roms/Ultimate Battles DS.nds</path>
                <thumbnail>./old/thumb.png</thumbnail>
                <image>./old/image.png</image>
                <name>Ultimate Battles DS</name>
              </game>
            </gameList>
        """.trimIndent()

        val enriched = GamelistTransform.enrich(xml) {
            SelectedMedia(
                thumbnailFileName = "Ultimate Battles DS.png",
                imageFileName = "Ultimate Battles DS-fanart.png"
            )
        }

        assertTrue(enriched.contains("./media/thumbnail/Ultimate Battles DS.png"))
        assertTrue(enriched.contains("./media/image/Ultimate Battles DS-fanart.png"))
        assertFalse(enriched.contains("./old/thumb.png"))
        assertFalse(enriched.contains("./old/image.png"))
    }

    @Test
    fun `buildMediaSyncPlan tracks desired media files from selected games`() {
        val xml = """
            <gameList>
              <game>
                <path>./roms/Ultimate Battles DS.nds</path>
                <name>Ultimate Battles DS</name>
              </game>
              <game>
                <path>./roms/Burning Hit!.nds</path>
                <name>Burning Hit!</name>
              </game>
            </gameList>
        """.trimIndent()

        val plan = GamelistTransform.buildMediaSyncPlan(xml) { gameFileName ->
            when (gameFileName) {
                "Ultimate Battles DS" -> SelectedMedia(
                    thumbnailFileName = "Ultimate Battles DS.png",
                    imageFileName = "Ultimate Battles DS-fanart.png"
                )
                "Burning Hit!" -> SelectedMedia(
                    thumbnailFileName = "Burning Hit!.png",
                    imageFileName = null
                )
                else -> SelectedMedia()
            }
        }

        assertEquals(setOf("Ultimate Battles DS.png", "Burning Hit!.png"), plan.desiredThumbnailFileNames)
        assertEquals(setOf("Ultimate Battles DS-fanart.png"), plan.desiredImageFileNames)
        assertTrue(plan.xmlContent.contains("./media/thumbnail/Ultimate Battles DS.png"))
        assertTrue(plan.xmlContent.contains("./media/thumbnail/Burning Hit!.png"))
        assertTrue(plan.xmlContent.contains("./media/image/Ultimate Battles DS-fanart.png"))
    }

    @Test
    fun `planMediaSync covers copy update skip delete and missing source`() {
        val actions = GamelistCopierTestAccess.planMediaSync(
            desiredFileNames = setOf(
                "copy.png",
                "update.png",
                "skip.png",
                "missing-source.png"
            ),
            sourceFileSizes = mapOf(
                "copy.png" to 100L,
                "update.png" to 200L,
                "skip.png" to 300L,
                "missing-source.png" to null
            ),
            existingFileSizes = mapOf(
                "update.png" to 250L,
                "skip.png" to 300L,
                "orphan.png" to 400L
            )
        )

        assertEquals(
            listOf(
                MediaSyncAction("copy.png", MediaSyncActionType.COPY),
                MediaSyncAction("missing-source.png", MediaSyncActionType.FAIL_MISSING_SOURCE),
                MediaSyncAction("skip.png", MediaSyncActionType.SKIP),
                MediaSyncAction("update.png", MediaSyncActionType.UPDATE),
                MediaSyncAction("orphan.png", MediaSyncActionType.DELETE_ORPHAN)
            ),
            actions
        )
    }

    @Test
    fun `copy result reports complete success when all systems succeed`() {
        val result = GamelistCopierTestAccess.buildCopyResult(
            listOf(
                CopySystemResult("nds", true, "Copied gamelist and media."),
                CopySystemResult("n3ds", true, "Copied gamelist and media.")
            )
        )

        assertTrue(result.success)
        assertEquals(2, result.systemsProcessed)
        assertEquals(2, result.systemsSucceeded)
        assertEquals(0, result.systemsFailed)
        assertEquals("Synced 2 system(s) successfully.", result.message)
    }

    @Test
    fun `copy result reports partial failure correctly`() {
        val result = GamelistCopierTestAccess.buildCopyResult(
            listOf(
                CopySystemResult("nds", true, "Copied gamelist and media."),
                CopySystemResult("n3ds", false, "No gamelist.xml found.")
            )
        )

        assertFalse(result.success)
        assertEquals(2, result.systemsProcessed)
        assertEquals(1, result.systemsSucceeded)
        assertEquals(1, result.systemsFailed)
        assertEquals("Synced 1 system(s); 1 failed.", result.message)
    }
}
