package com.ayaspacexml.app

object GamelistCopierTestAccess {
    fun buildCopyResult(systemResults: List<CopySystemResult>): CopyGamelistsResult =
        GamelistCopier.buildCopyResultForTest(systemResults)

    fun planMediaSync(
        desiredFileNames: Set<String>,
        sourceFileSizes: Map<String, Long?>,
        existingFileSizes: Map<String, Long>
    ): List<MediaSyncAction> =
        GamelistCopier.planMediaSyncForTest(desiredFileNames, sourceFileSizes, existingFileSizes)
}
