package com.ayaspacexml.app

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object GamelistCopier {
    private const val TAG = "GamelistCopier"

    suspend fun copyGamelists(
        context: Context,
        fromPathUri: String,
        toPathUri: String
    ) = withContext(Dispatchers.IO) {
        try {
            val fromDocumentFile = DocumentFile.fromTreeUri(context, fromPathUri.toUri())
            if (fromDocumentFile == null) {
                Log.e(TAG, "Failed to open source directory: $fromPathUri")
                return@withContext
            }

            val toDocumentFile = DocumentFile.fromTreeUri(context, toPathUri.toUri())
            if (toDocumentFile == null) {
                Log.e(TAG, "Failed to open destination directory: $toPathUri")
                return@withContext
            }

            val gamelistsDir = fromDocumentFile.findFile("gamelists")
            if (gamelistsDir == null) {
                Log.e(TAG, "gamelists directory not found in source")
                return@withContext
            }

            val downloadedMediaDir = fromDocumentFile.findFile("downloaded_media")
            if (downloadedMediaDir == null) {
                Log.w(TAG, "downloaded_media directory not found in source")
            }

            val systemDirs = gamelistsDir.listFiles()
            Log.d(TAG, "Found ${systemDirs.size} system directories to process")

            systemDirs.forEach { systemDir ->
                if (systemDir.isDirectory) {
                    val dirName = systemDir.name ?: "unknown"
                    Log.d(TAG, "Processing system directory: $dirName")
                    try {
                        processSystemDirectory(context, systemDir, toDocumentFile, downloadedMediaDir)
                        Log.d(TAG, "Successfully processed: $dirName")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing system directory $dirName", e)
                    }
                }
            }

            Log.d(TAG, "Finished processing all system directories")
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error in copyGamelists", e)
        }
    }

    private fun processSystemDirectory(
        context: Context,
        systemDir: DocumentFile,
        toDocumentFile: DocumentFile,
        downloadedMediaDir: DocumentFile?
    ) {
        val dirName = systemDir.name
        if (dirName == null) {
            Log.e(TAG, "System directory has no name")
            return
        }

        val gamelistFile = systemDir.findFile("gamelist.xml")
        if (gamelistFile?.isFile != true) {
            Log.w(TAG, "No gamelist.xml found in $dirName")
            return
        }

        Log.d(TAG, "Found gamelist.xml in $dirName")

        // Find or create destination system directory
        var toSystemDir = toDocumentFile.findFile(dirName)
        if (toSystemDir == null) {
            Log.d(TAG, "Creating system directory: $dirName")
            toSystemDir = toDocumentFile.createDirectory(dirName)
            if (toSystemDir == null) {
                Log.e(TAG, "Failed to create system directory: $dirName")
                return
            }
        }

        // Clear existing media folders
        clearMediaFolders(toSystemDir)

        // Process media if available
        val mediaSystemDir = downloadedMediaDir?.findFile(dirName)
        if (mediaSystemDir != null) {
            Log.d(TAG, "Found media directory for $dirName")
            processGamelistWithMedia(context, gamelistFile, toSystemDir, mediaSystemDir)
        } else {
            Log.w(TAG, "No media directory found for $dirName")
            // Still copy the gamelist even without media
            copyGamelistOnly(context, gamelistFile, toSystemDir)
        }
    }

    private fun clearMediaFolders(systemDir: DocumentFile) {
        try {
            val mediaDir = systemDir.findFile("media")
            if (mediaDir != null) {
                Log.d(TAG, "Clearing existing media folders in ${systemDir.name}")

                mediaDir.findFile("image")?.listFiles()?.forEach {
                    if (!it.delete()) {
                        Log.w(TAG, "Failed to delete image: ${it.name}")
                    }
                }

                mediaDir.findFile("thumbnail")?.listFiles()?.forEach {
                    if (!it.delete()) {
                        Log.w(TAG, "Failed to delete thumbnail: ${it.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing media folders", e)
        }
    }

    private fun copyGamelistOnly(
        context: Context,
        gamelistFile: DocumentFile,
        toSystemDir: DocumentFile
    ) {
        try {
            val content = context.contentResolver.openInputStream(gamelistFile.uri)?.use {
                it.reader().readText()
            }

            if (content == null) {
                Log.e(TAG, "Failed to read gamelist content")
                return
            }

            writeGamelist(context, toSystemDir, content)
        } catch (e: Exception) {
            Log.e(TAG, "Error copying gamelist", e)
        }
    }

    private fun processGamelistWithMedia(
        context: Context,
        gamelistFile: DocumentFile,
        toSystemDir: DocumentFile,
        mediaSystemDir: DocumentFile
    ) {
        // Ensure media directory structure exists
        var mediaDir = toSystemDir.findFile("media")
        if (mediaDir == null) {
            mediaDir = toSystemDir.createDirectory("media")
            if (mediaDir == null) {
                Log.e(TAG, "Failed to create media directory")
                copyGamelistOnly(context, gamelistFile, toSystemDir)
                return
            }
        }

        var imageDir = mediaDir.findFile("image")
        if (imageDir == null) {
            imageDir = mediaDir.createDirectory("image")
            if (imageDir == null) {
                Log.e(TAG, "Failed to create image directory")
                copyGamelistOnly(context, gamelistFile, toSystemDir)
                return
            }
        }

        var thumbnailDir = mediaDir.findFile("thumbnail")
        if (thumbnailDir == null) {
            thumbnailDir = mediaDir.createDirectory("thumbnail")
            if (thumbnailDir == null) {
                Log.e(TAG, "Failed to create thumbnail directory")
                copyGamelistOnly(context, gamelistFile, toSystemDir)
                return
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
            copyGamelistOnly(context, gamelistFile, toSystemDir)
            return
        }

        writeGamelist(context, toSystemDir, modifiedXml)
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

    private fun writeGamelist(context: Context, toSystemDir: DocumentFile, xmlContent: String) {
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
                return
            }

            context.contentResolver.openOutputStream(gamelistFile.uri)?.use { outputStream ->
                outputStream.writer().use { writer ->
                    writer.write(xmlContent)
                    writer.flush()
                }
            }

            Log.d(TAG, "Successfully wrote gamelist.xml to ${toSystemDir.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Error writing gamelist", e)
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
}
