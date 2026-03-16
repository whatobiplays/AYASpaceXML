package com.ayaspacexml.app

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CopySystemResult(
    val systemName: String,
    val success: Boolean,
    val message: String,
)

data class CopyGamelistsResult(
    val success: Boolean,
    val systemsProcessed: Int,
    val systemsSucceeded: Int,
    val systemsFailed: Int,
    val systemResults: List<CopySystemResult>,
    val message: String,
)

data class MediaSyncStats(
    val copied: Int = 0,
    val updated: Int = 0,
    val deleted: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
)

enum class MediaSyncActionType {
    COPY,
    UPDATE,
    SKIP,
    DELETE_ORPHAN,
    FAIL_MISSING_SOURCE,
}

data class MediaSyncAction(
    val fileName: String,
    val actionType: MediaSyncActionType,
)

object GamelistCopier {
    private const val TAG = "GamelistCopier"

    suspend fun copyGamelists(
        context: Context,
        fromPathUri: String,
        toPathUri: String
    ): CopyGamelistsResult = withContext(Dispatchers.IO) {
        try {
            val fromDocumentFile = DocumentFile.fromTreeUri(context, fromPathUri.toUri())
            if (fromDocumentFile == null) {
                Log.e(TAG, "Failed to open source directory: $fromPathUri")
                return@withContext fatalResult("Failed to open source directory.")
            }

            val toDocumentFile = DocumentFile.fromTreeUri(context, toPathUri.toUri())
            if (toDocumentFile == null) {
                Log.e(TAG, "Failed to open destination directory: $toPathUri")
                return@withContext fatalResult("Failed to open destination directory.")
            }

            val gamelistsDir = fromDocumentFile.findFile("gamelists")
            if (gamelistsDir == null) {
                Log.e(TAG, "gamelists directory not found in source")
                return@withContext fatalResult("Source folder is missing 'gamelists'.")
            }

            val downloadedMediaDir = fromDocumentFile.findFile("downloaded_media")
            if (downloadedMediaDir == null) {
                Log.w(TAG, "downloaded_media directory not found in source")
            }

            val systemDirs = gamelistsDir.listFiles()
            Log.d(TAG, "Found ${systemDirs.size} system directories to process")
            val systemResults = mutableListOf<CopySystemResult>()

            systemDirs.forEach { systemDir ->
                if (systemDir.isDirectory) {
                    val dirName = systemDir.name ?: "unknown"
                    Log.d(TAG, "Processing system directory: $dirName")
                    try {
                        val result = processSystemDirectory(context, systemDir, toDocumentFile, downloadedMediaDir)
                        systemResults += result
                        if (result.success) {
                            Log.d(TAG, "Successfully processed: $dirName")
                        } else {
                            Log.w(TAG, "Processed with failure: $dirName - ${result.message}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing system directory $dirName", e)
                        systemResults += CopySystemResult(
                            systemName = dirName,
                            success = false,
                            message = "Unexpected error: ${e.message ?: "unknown"}"
                        )
                    }
                }
            }

            Log.d(TAG, "Finished processing all system directories")
            buildCopyResult(systemResults)
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error in copyGamelists", e)
            fatalResult("Sync failed: ${e.message ?: "unknown error"}")
        }
    }

    private fun processSystemDirectory(
        context: Context,
        systemDir: DocumentFile,
        toDocumentFile: DocumentFile,
        downloadedMediaDir: DocumentFile?
    ): CopySystemResult {
        val dirName = systemDir.name
        if (dirName == null) {
            Log.e(TAG, "System directory has no name")
            return CopySystemResult("unknown", false, "System directory has no name.")
        }

        val gamelistFile = systemDir.findFile("gamelist.xml")
        if (gamelistFile?.isFile != true) {
            Log.w(TAG, "No gamelist.xml found in $dirName")
            return CopySystemResult(dirName, false, "No gamelist.xml found.")
        }

        Log.d(TAG, "Found gamelist.xml in $dirName")

        // Find or create destination system directory
        var toSystemDir = toDocumentFile.findFile(dirName)
        if (toSystemDir == null) {
            Log.d(TAG, "Creating system directory: $dirName")
            toSystemDir = toDocumentFile.createDirectory(dirName)
            if (toSystemDir == null) {
                Log.e(TAG, "Failed to create system directory: $dirName")
                return CopySystemResult(dirName, false, "Failed to create destination system directory.")
            }
        }

        // Process media if available
        val mediaSystemDir = downloadedMediaDir?.findFile(dirName)
        val copyMessage = if (mediaSystemDir != null) {
            Log.d(TAG, "Found media directory for $dirName")
            processGamelistWithMedia(context, gamelistFile, toSystemDir, mediaSystemDir)
        } else {
            Log.w(TAG, "No media directory found for $dirName")
            // Still copy the gamelist even without media
            copyGamelistOnly(context, gamelistFile, toSystemDir)
        }

        return when {
            copyMessage == null -> CopySystemResult(dirName, false, "Failed to write gamelist.")
            else -> CopySystemResult(dirName, true, copyMessage)
        }
    }

    private fun copyGamelistOnly(
        context: Context,
        gamelistFile: DocumentFile,
        toSystemDir: DocumentFile
    ): String? {
        try {
            val content = context.contentResolver.openInputStream(gamelistFile.uri)?.use {
                it.reader().readText()
            }

            if (content == null) {
                Log.e(TAG, "Failed to read gamelist content")
                return null
            }

            return if (writeGamelist(context, toSystemDir, content)) {
                "Copied gamelist."
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error copying gamelist", e)
            return null
        }
    }

    private fun processGamelistWithMedia(
        context: Context,
        gamelistFile: DocumentFile,
        toSystemDir: DocumentFile,
        mediaSystemDir: DocumentFile
    ): String? {
        // Ensure media directory structure exists
        var mediaDir = toSystemDir.findFile("media")
        if (mediaDir == null) {
            mediaDir = toSystemDir.createDirectory("media")
            if (mediaDir == null) {
                Log.e(TAG, "Failed to create media directory")
                return copyGamelistOnly(context, gamelistFile, toSystemDir)
            }
        }

        var imageDir = mediaDir.findFile("image")
        if (imageDir == null) {
            imageDir = mediaDir.createDirectory("image")
            if (imageDir == null) {
                Log.e(TAG, "Failed to create image directory")
                return copyGamelistOnly(context, gamelistFile, toSystemDir)
            }
        }

        var thumbnailDir = mediaDir.findFile("thumbnail")
        if (thumbnailDir == null) {
            thumbnailDir = mediaDir.createDirectory("thumbnail")
            if (thumbnailDir == null) {
                Log.e(TAG, "Failed to create thumbnail directory")
                return copyGamelistOnly(context, gamelistFile, toSystemDir)
            }
        }

        val coversDir = mediaSystemDir.findFile("covers")
        val fanartDir = mediaSystemDir.findFile("fanart")
        val screenshotsDir = mediaSystemDir.findFile("screenshots")

        Log.d(TAG, "Media directories - covers: ${coversDir != null}, fanart: ${fanartDir != null}, screenshots: ${screenshotsDir != null}")

        val syncPlan = buildMediaSyncPlan(
            context,
            gamelistFile,
            coversDir,
            fanartDir,
            screenshotsDir
        )

        if (syncPlan == null) {
            Log.e(TAG, "Failed to parse and enrich gamelist")
            return copyGamelistOnly(context, gamelistFile, toSystemDir)
        }

        val thumbnailStats = syncMediaDirectory(
            context = context,
            targetDir = thumbnailDir,
            desiredFileNames = syncPlan.desiredThumbnailFileNames,
            sourceFileResolver = buildThumbnailSourceResolver(coversDir)
        )

        val imageStats = syncMediaDirectory(
            context = context,
            targetDir = imageDir,
            desiredFileNames = syncPlan.desiredImageFileNames,
            sourceFileResolver = buildImageSourceResolver(fanartDir, screenshotsDir)
        )

        val writeSucceeded = writeGamelist(context, toSystemDir, syncPlan.xmlContent)
        val totalFailures = thumbnailStats.failed + imageStats.failed + if (writeSucceeded) 0 else 1

        return if (totalFailures == 0) {
            "Synced gamelist and media. ${formatMediaSyncStats(thumbnailStats, imageStats)}"
        } else {
            null
        }
    }

    private fun buildMediaSyncPlan(
        context: Context,
        gamelistFile: DocumentFile,
        coversDir: DocumentFile?,
        fanartDir: DocumentFile?,
        screenshotsDir: DocumentFile?
    ): MediaSyncPlan? {
        return try {
            val originalXml = context.contentResolver.openInputStream(gamelistFile.uri)?.use {
                it.reader().readText()
            }

            if (originalXml == null) {
                Log.e(TAG, "Failed to read original XML")
                return null
            }

            val coverFiles = fileMap(coversDir)
            val fanartFiles = fileMap(fanartDir)
            val screenshotFiles = fileMap(screenshotsDir)

            GamelistTransform.buildMediaSyncPlan(originalXml) { gameFileName ->
                MediaFileMatcher.selectMedia(
                    gameFileName = gameFileName,
                    coverFileNames = coverFiles.keys.toList(),
                    fanartFileNames = fanartFiles.keys.toList(),
                    screenshotFileNames = screenshotFiles.keys.toList()
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing and enriching gamelist", e)
            null
        }
    }

    private fun fileMap(directory: DocumentFile?): Map<String, DocumentFile> {
        if (directory == null) return emptyMap()

        return try {
            directory.listFiles()
                .mapNotNull { file -> file.name?.let { name -> name to file } }
                .toMap()
        } catch (e: Exception) {
            Log.e(TAG, "Error listing files for ${directory.name}", e)
            emptyMap()
        }
    }

    private fun buildThumbnailSourceResolver(coversDir: DocumentFile?): (String) -> DocumentFile? {
        val files = fileMap(coversDir)
        return { fileName -> files[fileName] }
    }

    private fun buildImageSourceResolver(
        fanartDir: DocumentFile?,
        screenshotsDir: DocumentFile?
    ): (String) -> DocumentFile? {
        val fanartFiles = fileMap(fanartDir)
        val screenshotFiles = fileMap(screenshotsDir)
        return { fileName -> fanartFiles[fileName] ?: screenshotFiles[fileName] }
    }

    private fun writeGamelist(context: Context, toSystemDir: DocumentFile, xmlContent: String): Boolean {
        try {
            // Delete existing gamelist if it exists
            toSystemDir.findFile("gamelist.xml")?.let { existingFile ->
                if (!existingFile.delete()) {
                    Log.w(TAG, "Failed to delete existing gamelist.xml")
                }
            }

            // Create new gamelist file
            val gamelistFile = toSystemDir.createFile("application/xml", "gamelist.xml")
            if (gamelistFile == null) {
                Log.e(TAG, "Failed to create gamelist.xml in ${toSystemDir.name}")
                return false
            }

            context.contentResolver.openOutputStream(gamelistFile.uri)?.use { outputStream ->
                outputStream.writer().use { writer ->
                    writer.write(xmlContent)
                    writer.flush()
                }
            }

            Log.d(TAG, "Successfully wrote gamelist.xml to ${toSystemDir.name}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error writing gamelist", e)
            return false
        }
    }

    private fun syncMediaDirectory(
        context: Context,
        targetDir: DocumentFile,
        desiredFileNames: Set<String>,
        sourceFileResolver: (String) -> DocumentFile?
    ): MediaSyncStats {
        var copied = 0
        var updated = 0
        var deleted = 0
        var skipped = 0
        var failed = 0

        val existingFiles = try {
            targetDir.listFiles().mapNotNull { file -> file.name?.let { it to file } }.toMap()
        } catch (e: Exception) {
            Log.e(TAG, "Error listing destination files in ${targetDir.name}", e)
            emptyMap()
        }

        val sourceFiles = desiredFileNames.associateWith(sourceFileResolver)
        val actions = planMediaSync(
            desiredFileNames = desiredFileNames,
            sourceFileSizes = sourceFiles.mapValues { (_, file) -> file?.length() },
            existingFileSizes = existingFiles.mapValues { (_, file) -> file.length() }
        )

        actions.forEach { action ->
            when (action.actionType) {
                MediaSyncActionType.COPY -> {
                    val sourceFile = sourceFiles[action.fileName]
                    if (sourceFile != null && copyFile(context, sourceFile, targetDir)) copied++ else failed++
                }
                MediaSyncActionType.UPDATE -> {
                    val sourceFile = sourceFiles[action.fileName]
                    val existingFile = existingFiles[action.fileName]
                    if (sourceFile != null && existingFile != null && replaceFile(context, sourceFile, existingFile, targetDir)) {
                        updated++
                    } else {
                        failed++
                    }
                }
                MediaSyncActionType.SKIP -> skipped++
                MediaSyncActionType.DELETE_ORPHAN -> {
                    val existingFile = existingFiles[action.fileName]
                    if (existingFile != null && existingFile.delete()) {
                        deleted++
                    } else {
                        Log.w(TAG, "Failed to delete orphaned media: ${action.fileName}")
                        failed++
                    }
                }
                MediaSyncActionType.FAIL_MISSING_SOURCE -> {
                    Log.w(TAG, "Missing source file for expected media: ${action.fileName}")
                    failed++
                }
            }
        }

        return MediaSyncStats(
            copied = copied,
            updated = updated,
            deleted = deleted,
            skipped = skipped,
            failed = failed
        )
    }

    internal fun planMediaSyncForTest(
        desiredFileNames: Set<String>,
        sourceFileSizes: Map<String, Long?>,
        existingFileSizes: Map<String, Long>
    ): List<MediaSyncAction> = planMediaSync(desiredFileNames, sourceFileSizes, existingFileSizes)

    private fun planMediaSync(
        desiredFileNames: Set<String>,
        sourceFileSizes: Map<String, Long?>,
        existingFileSizes: Map<String, Long>
    ): List<MediaSyncAction> {
        val actions = mutableListOf<MediaSyncAction>()

        desiredFileNames.sorted().forEach { fileName ->
            val sourceSize = sourceFileSizes[fileName]
            val existingSize = existingFileSizes[fileName]
            val actionType = when {
                sourceSize == null -> MediaSyncActionType.FAIL_MISSING_SOURCE
                existingSize == null -> MediaSyncActionType.COPY
                existingSize != sourceSize -> MediaSyncActionType.UPDATE
                else -> MediaSyncActionType.SKIP
            }
            actions += MediaSyncAction(fileName, actionType)
        }

        existingFileSizes.keys
            .filter { it !in desiredFileNames }
            .sorted()
            .forEach { fileName ->
                actions += MediaSyncAction(fileName, MediaSyncActionType.DELETE_ORPHAN)
            }

        return actions
    }

    private fun copyFile(
        context: Context,
        sourceFile: DocumentFile,
        targetDir: DocumentFile
    ): Boolean {
        return try {
            val fileName = sourceFile.name
            if (fileName == null) {
                Log.w(TAG, "Source file has no name")
                return false
            }

            // Check if file already exists and delete it
            targetDir.findFile(fileName)?.let { existingFile ->
                if (!existingFile.delete()) {
                    Log.w(TAG, "Failed to delete existing file: $fileName")
                }
            }

            val newFile = targetDir.createFile("image/*", fileName)
            if (newFile == null) {
                Log.e(TAG, "Failed to create file: $fileName in ${targetDir.name}")
                return false
            }

            var copySuccess = false
            context.contentResolver.openInputStream(sourceFile.uri)?.use { input ->
                context.contentResolver.openOutputStream(newFile.uri)?.use { output ->
                    val bytesCopied = input.copyTo(output)
                    output.flush()
                    copySuccess = bytesCopied > 0
                }
            }

            if (!copySuccess) {
                Log.e(TAG, "Failed to copy file content: $fileName")
                newFile.delete()
                return false
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error copying file ${sourceFile.name}", e)
            false
        }
    }

    private fun replaceFile(
        context: Context,
        sourceFile: DocumentFile,
        existingTargetFile: DocumentFile,
        targetDir: DocumentFile
    ): Boolean {
        val fileName = existingTargetFile.name ?: return false
        if (!existingTargetFile.delete()) {
            Log.w(TAG, "Failed to delete existing file: $fileName")
            return false
        }
        return copyFile(context, sourceFile, targetDir)
    }

    private fun formatMediaSyncStats(thumbnailStats: MediaSyncStats, imageStats: MediaSyncStats): String {
        val combined = MediaSyncStats(
            copied = thumbnailStats.copied + imageStats.copied,
            updated = thumbnailStats.updated + imageStats.updated,
            deleted = thumbnailStats.deleted + imageStats.deleted,
            skipped = thumbnailStats.skipped + imageStats.skipped,
            failed = thumbnailStats.failed + imageStats.failed
        )
        return "Added ${combined.copied}, updated ${combined.updated}, deleted ${combined.deleted}, skipped ${combined.skipped}."
    }

    internal fun buildCopyResultForTest(systemResults: List<CopySystemResult>): CopyGamelistsResult =
        buildCopyResult(systemResults)

    private fun buildCopyResult(systemResults: List<CopySystemResult>): CopyGamelistsResult {
        val succeeded = systemResults.count { it.success }
        val failed = systemResults.size - succeeded
        val message = when {
            systemResults.isEmpty() -> "No system directories found."
            failed == 0 -> "Copied ${systemResults.size} system(s) successfully."
            succeeded == 0 -> "Sync failed for all $failed system(s)."
            else -> "Synced $succeeded system(s); $failed failed."
        }

        return CopyGamelistsResult(
            success = systemResults.isNotEmpty() && failed == 0,
            systemsProcessed = systemResults.size,
            systemsSucceeded = succeeded,
            systemsFailed = failed,
            systemResults = systemResults,
            message = message
        )
    }

    private fun fatalResult(message: String): CopyGamelistsResult =
        CopyGamelistsResult(
            success = false,
            systemsProcessed = 0,
            systemsSucceeded = 0,
            systemsFailed = 0,
            systemResults = emptyList(),
            message = message
        )
}
