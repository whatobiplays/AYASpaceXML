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
            fatalResult("Copy failed: ${e.message ?: "unknown error"}")
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

        // Clear existing media folders
        val mediaClearFailed = !clearMediaFolders(toSystemDir)

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
            mediaClearFailed -> CopySystemResult(dirName, true, "$copyMessage Existing media cleanup was incomplete.")
            else -> CopySystemResult(dirName, true, copyMessage)
        }
    }

    private fun clearMediaFolders(systemDir: DocumentFile): Boolean {
        var allDeleted = true
        try {
            val mediaDir = systemDir.findFile("media")
            if (mediaDir != null) {
                Log.d(TAG, "Clearing existing media folders in ${systemDir.name}")

                mediaDir.findFile("image")?.listFiles()?.forEach {
                    if (!it.delete()) {
                        Log.w(TAG, "Failed to delete image: ${it.name}")
                        allDeleted = false
                    }
                }

                mediaDir.findFile("thumbnail")?.listFiles()?.forEach {
                    if (!it.delete()) {
                        Log.w(TAG, "Failed to delete thumbnail: ${it.name}")
                        allDeleted = false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing media folders", e)
            return false
        }
        return allDeleted
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

        val modifiedXml = parseAndEnrichGamelist(
            context,
            gamelistFile,
            coversDir,
            fanartDir,
            screenshotsDir,
            imageDir,
            thumbnailDir
        )

        if (modifiedXml == null) {
            Log.e(TAG, "Failed to parse and enrich gamelist")
            return copyGamelistOnly(context, gamelistFile, toSystemDir)
        }

        return if (writeGamelist(context, toSystemDir, modifiedXml)) {
            "Copied gamelist and media."
        } else {
            null
        }
    }

    private fun parseAndEnrichGamelist(
        context: Context,
        gamelistFile: DocumentFile,
        coversDir: DocumentFile?,
        fanartDir: DocumentFile?,
        screenshotsDir: DocumentFile?,
        imageDir: DocumentFile,
        thumbnailDir: DocumentFile
    ): String? {
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

            GamelistTransform.enrich(originalXml) { gameFileName ->
                copyMediaForGame(
                    context = context,
                    gameFileName = gameFileName,
                    coverFiles = coverFiles,
                    fanartFiles = fanartFiles,
                    screenshotFiles = screenshotFiles,
                    imageDir = imageDir,
                    thumbnailDir = thumbnailDir
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

    private fun copyMediaForGame(
        context: Context,
        gameFileName: String,
        coverFiles: Map<String, DocumentFile>,
        fanartFiles: Map<String, DocumentFile>,
        screenshotFiles: Map<String, DocumentFile>,
        imageDir: DocumentFile,
        thumbnailDir: DocumentFile
    ): SelectedMedia {
        Log.d(TAG, "Looking for media for: $gameFileName")

        val selectedMedia = MediaFileMatcher.selectMedia(
            gameFileName = gameFileName,
            coverFileNames = coverFiles.keys.toList(),
            fanartFileNames = fanartFiles.keys.toList(),
            screenshotFileNames = screenshotFiles.keys.toList()
        )

        val copiedThumbnail = selectedMedia.thumbnailFileName
            ?.let(coverFiles::get)
            ?.takeIf { copyFile(context, it, thumbnailDir) }
            ?.name

        val copiedImage = selectedMedia.imageFileName
            ?.let { fanartFiles[it] ?: screenshotFiles[it] }
            ?.takeIf { copyFile(context, it, imageDir) }
            ?.name

        if (copiedThumbnail == null && copiedImage == null) {
            Log.w(TAG, "No media copied for game: $gameFileName")
        } else {
            Log.d(TAG, "Copied media for game: $gameFileName")
        }

        return SelectedMedia(
            thumbnailFileName = copiedThumbnail,
            imageFileName = copiedImage
        )
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

    internal fun buildCopyResultForTest(systemResults: List<CopySystemResult>): CopyGamelistsResult =
        buildCopyResult(systemResults)

    private fun buildCopyResult(systemResults: List<CopySystemResult>): CopyGamelistsResult {
        val succeeded = systemResults.count { it.success }
        val failed = systemResults.size - succeeded
        val message = when {
            systemResults.isEmpty() -> "No system directories found."
            failed == 0 -> "Copied ${systemResults.size} system(s) successfully."
            succeeded == 0 -> "Copy failed for all $failed system(s)."
            else -> "Copied $succeeded system(s); $failed failed."
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
