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
    val metrics: MediaSyncStats = MediaSyncStats(),
)

data class CopyGamelistsResult(
    val success: Boolean,
    val systemsProcessed: Int,
    val systemsSucceeded: Int,
    val systemsFailed: Int,
    val systemResults: List<CopySystemResult>,
    val metrics: MediaSyncStats,
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

data class SystemSyncOutcome(
    val message: String,
    val metrics: MediaSyncStats = MediaSyncStats(),
)

data class SyncProgress(
    val totalSystems: Int,
    val completedSystems: Int,
    val currentSystemName: String? = null,
    val currentSystemCompletedActions: Int = 0,
    val currentSystemTotalActions: Int = 0,
    val status: String = "",
) {
    val fraction: Float
        get() {
            if (totalSystems <= 0) return 0f
            val currentSystemFraction = if (currentSystemTotalActions > 0) {
                currentSystemCompletedActions.toFloat() / currentSystemTotalActions.toFloat()
            } else {
                0f
            }
            return ((completedSystems.toFloat() + currentSystemFraction) / totalSystems.toFloat())
                .coerceIn(0f, 1f)
        }
}

object GamelistCopier {
    private const val TAG = "GamelistCopier"

    suspend fun copyGamelists(
        context: Context,
        fromPathUri: String,
        toPathUri: String,
        onProgress: suspend (SyncProgress) -> Unit = {}
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
            val directoriesToProcess = systemDirs
                .filter { it.isDirectory }
                .sortedBy { it.name.orEmpty().lowercase() }
            Log.d(TAG, "Found ${directoriesToProcess.size} system directories to process")
            val systemResults = mutableListOf<CopySystemResult>()
            val totalSystems = directoriesToProcess.size
            var completedSystems = 0

            directoriesToProcess.forEach { systemDir ->
                val dirName = systemDir.name ?: "unknown"
                Log.d(TAG, "Processing system directory: $dirName")
                try {
                    val result = processSystemDirectory(
                        context = context,
                        systemDir = systemDir,
                        toDocumentFile = toDocumentFile,
                        downloadedMediaDir = downloadedMediaDir,
                        totalSystems = totalSystems,
                        completedSystems = completedSystems,
                        onProgress = onProgress
                    )
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

                completedSystems++
                onProgress(
                    SyncProgress(
                        totalSystems = totalSystems,
                        completedSystems = completedSystems,
                        status = if (completedSystems == totalSystems) "Sync complete" else "Completed $completedSystems of $totalSystems systems"
                    )
                )
            }

            Log.d(TAG, "Finished processing all system directories")
            buildCopyResult(systemResults)
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error in copyGamelists", e)
            fatalResult("Sync failed: ${e.message ?: "unknown error"}")
        }
    }

    private suspend fun processSystemDirectory(
        context: Context,
        systemDir: DocumentFile,
        toDocumentFile: DocumentFile,
        downloadedMediaDir: DocumentFile?,
        totalSystems: Int,
        completedSystems: Int,
        onProgress: suspend (SyncProgress) -> Unit
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
        onProgress(
            SyncProgress(
                totalSystems = totalSystems,
                completedSystems = completedSystems,
                currentSystemName = dirName,
                currentSystemCompletedActions = 0,
                currentSystemTotalActions = 0,
                status = "Preparing $dirName"
            )
        )

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
            processGamelistWithMedia(
                context = context,
                gamelistFile = gamelistFile,
                toSystemDir = toSystemDir,
                mediaSystemDir = mediaSystemDir,
                systemName = dirName,
                totalSystems = totalSystems,
                completedSystems = completedSystems,
                onProgress = onProgress
            )
        } else {
            Log.w(TAG, "No media directory found for $dirName")
            // Still copy the gamelist even without media
            copyGamelistOnly(
                context = context,
                gamelistFile = gamelistFile,
                toSystemDir = toSystemDir,
                systemName = dirName,
                totalSystems = totalSystems,
                completedSystems = completedSystems,
                onProgress = onProgress
            )
        }

        return when {
            copyMessage == null -> CopySystemResult(dirName, false, "Failed to write gamelist.")
            else -> CopySystemResult(
                systemName = dirName,
                success = true,
                message = copyMessage.message,
                metrics = copyMessage.metrics
            )
        }
    }

    private suspend fun copyGamelistOnly(
        context: Context,
        gamelistFile: DocumentFile,
        toSystemDir: DocumentFile,
        systemName: String,
        totalSystems: Int,
        completedSystems: Int,
        onProgress: suspend (SyncProgress) -> Unit
    ): SystemSyncOutcome? {
        try {
            val content = context.contentResolver.openInputStream(gamelistFile.uri)?.use {
                it.reader().readText()
            }

            if (content == null) {
                Log.e(TAG, "Failed to read gamelist content")
                return null
            }

            onProgress(
                SyncProgress(
                    totalSystems = totalSystems,
                    completedSystems = completedSystems,
                    currentSystemName = systemName,
                    currentSystemCompletedActions = 0,
                    currentSystemTotalActions = 1,
                    status = "Writing gamelist for $systemName"
                )
            )

            return if (writeGamelist(context, toSystemDir, content)) {
                onProgress(
                    SyncProgress(
                        totalSystems = totalSystems,
                        completedSystems = completedSystems,
                        currentSystemName = systemName,
                        currentSystemCompletedActions = 1,
                        currentSystemTotalActions = 1,
                        status = "Finished $systemName"
                    )
                )
                SystemSyncOutcome("Synced gamelist only.")
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error copying gamelist", e)
            return null
        }
    }

    private suspend fun processGamelistWithMedia(
        context: Context,
        gamelistFile: DocumentFile,
        toSystemDir: DocumentFile,
        mediaSystemDir: DocumentFile,
        systemName: String,
        totalSystems: Int,
        completedSystems: Int,
        onProgress: suspend (SyncProgress) -> Unit
    ): SystemSyncOutcome? {
        // Ensure media directory structure exists
        var mediaDir = toSystemDir.findFile("media")
        if (mediaDir == null) {
            mediaDir = toSystemDir.createDirectory("media")
            if (mediaDir == null) {
                Log.e(TAG, "Failed to create media directory")
                return copyGamelistOnly(context, gamelistFile, toSystemDir, systemName, totalSystems, completedSystems, onProgress)
            }
        }

        var imageDir = mediaDir.findFile("image")
        if (imageDir == null) {
            imageDir = mediaDir.createDirectory("image")
            if (imageDir == null) {
                Log.e(TAG, "Failed to create image directory")
                return copyGamelistOnly(context, gamelistFile, toSystemDir, systemName, totalSystems, completedSystems, onProgress)
            }
        }

        var thumbnailDir = mediaDir.findFile("thumbnail")
        if (thumbnailDir == null) {
            thumbnailDir = mediaDir.createDirectory("thumbnail")
            if (thumbnailDir == null) {
                Log.e(TAG, "Failed to create thumbnail directory")
                return copyGamelistOnly(context, gamelistFile, toSystemDir, systemName, totalSystems, completedSystems, onProgress)
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
            return copyGamelistOnly(context, gamelistFile, toSystemDir, systemName, totalSystems, completedSystems, onProgress)
        }

        val thumbnailSourceResolver = buildThumbnailSourceResolver(coversDir)
        val imageSourceResolver = buildImageSourceResolver(fanartDir, screenshotsDir)
        val thumbnailActions = planMediaSync(
            desiredFileNames = syncPlan.desiredThumbnailFileNames,
            sourceFileSizes = syncPlan.desiredThumbnailFileNames.associateWith { fileName -> thumbnailSourceResolver(fileName)?.length() },
            existingFileSizes = listFilesByName(thumbnailDir).mapValues { (_, file) -> file.length() }
        )
        val imageActions = planMediaSync(
            desiredFileNames = syncPlan.desiredImageFileNames,
            sourceFileSizes = syncPlan.desiredImageFileNames.associateWith { fileName -> imageSourceResolver(fileName)?.length() },
            existingFileSizes = listFilesByName(imageDir).mapValues { (_, file) -> file.length() }
        )

        val totalActions = thumbnailActions.size + imageActions.size + 1
        var completedActions = 0

        suspend fun report(status: String) {
            onProgress(
                SyncProgress(
                    totalSystems = totalSystems,
                    completedSystems = completedSystems,
                    currentSystemName = systemName,
                    currentSystemCompletedActions = completedActions,
                    currentSystemTotalActions = totalActions,
                    status = status
                )
            )
        }

        report("Syncing media for $systemName")

        val thumbnailStats = syncMediaDirectory(
            context = context,
            targetDir = thumbnailDir,
            desiredFileNames = syncPlan.desiredThumbnailFileNames,
            sourceFileResolver = thumbnailSourceResolver,
            onActionFinished = {
                completedActions++
                report("Syncing media for $systemName")
            }
        )

        val imageStats = syncMediaDirectory(
            context = context,
            targetDir = imageDir,
            desiredFileNames = syncPlan.desiredImageFileNames,
            sourceFileResolver = imageSourceResolver,
            onActionFinished = {
                completedActions++
                report("Syncing media for $systemName")
            }
        )

        report("Writing gamelist for $systemName")
        val writeSucceeded = writeGamelist(context, toSystemDir, syncPlan.xmlContent)
        completedActions++
        report("Finished $systemName")
        val totalFailures = thumbnailStats.failed + imageStats.failed + if (writeSucceeded) 0 else 1

        val combinedStats = combineMediaSyncStats(thumbnailStats, imageStats)

        return if (totalFailures == 0) {
            SystemSyncOutcome(
                message = "Synced gamelist and media.",
                metrics = combinedStats
            )
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

    private suspend fun syncMediaDirectory(
        context: Context,
        targetDir: DocumentFile,
        desiredFileNames: Set<String>,
        sourceFileResolver: (String) -> DocumentFile?,
        onActionFinished: suspend () -> Unit
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
            onActionFinished()
        }

        return MediaSyncStats(
            copied = copied,
            updated = updated,
            deleted = deleted,
            skipped = skipped,
            failed = failed
        )
    }

    private fun listFilesByName(directory: DocumentFile): Map<String, DocumentFile> {
        return try {
            directory.listFiles()
                .mapNotNull { file -> file.name?.let { name -> name to file } }
                .toMap()
        } catch (e: Exception) {
            Log.e(TAG, "Error listing destination files in ${directory.name}", e)
            emptyMap()
        }
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

    private fun combineMediaSyncStats(thumbnailStats: MediaSyncStats, imageStats: MediaSyncStats): MediaSyncStats {
        return MediaSyncStats(
            copied = thumbnailStats.copied + imageStats.copied,
            updated = thumbnailStats.updated + imageStats.updated,
            deleted = thumbnailStats.deleted + imageStats.deleted,
            skipped = thumbnailStats.skipped + imageStats.skipped,
            failed = thumbnailStats.failed + imageStats.failed
        )
    }

    internal fun buildCopyResultForTest(systemResults: List<CopySystemResult>): CopyGamelistsResult =
        buildCopyResult(systemResults)

    private fun buildCopyResult(systemResults: List<CopySystemResult>): CopyGamelistsResult {
        val sortedResults = systemResults.sortedBy { it.systemName.lowercase() }
        val succeeded = sortedResults.count { it.success }
        val failed = sortedResults.size - succeeded
        val combinedMetrics = sortedResults.fold(MediaSyncStats()) { acc, result ->
            MediaSyncStats(
                copied = acc.copied + result.metrics.copied,
                updated = acc.updated + result.metrics.updated,
                deleted = acc.deleted + result.metrics.deleted,
                skipped = acc.skipped + result.metrics.skipped,
                failed = acc.failed + result.metrics.failed
            )
        }
        val message = when {
            sortedResults.isEmpty() -> "No system directories found."
            failed == 0 -> "Synced ${sortedResults.size} system(s) successfully."
            succeeded == 0 -> "Sync failed for all $failed system(s)."
            else -> "Synced $succeeded system(s); $failed failed."
        }

        return CopyGamelistsResult(
            success = sortedResults.isNotEmpty() && failed == 0,
            systemsProcessed = sortedResults.size,
            systemsSucceeded = succeeded,
            systemsFailed = failed,
            systemResults = sortedResults,
            metrics = combinedMetrics,
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
            metrics = MediaSyncStats(),
            message = message
        )
}
