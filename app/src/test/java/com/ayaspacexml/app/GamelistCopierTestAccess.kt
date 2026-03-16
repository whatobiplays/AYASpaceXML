package com.ayaspacexml.app

object GamelistCopierTestAccess {
    fun buildCopyResult(systemResults: List<CopySystemResult>): CopyGamelistsResult =
        GamelistCopier.buildCopyResultForTest(systemResults)
}
