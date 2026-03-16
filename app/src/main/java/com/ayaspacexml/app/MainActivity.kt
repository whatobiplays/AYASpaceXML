package com.ayaspacexml.app

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.ayaspacexml.app.ui.theme.AYASpaceXMLTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        enableEdgeToEdge()
        setContent {
            AYASpaceXMLTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(prefs = prefs)
                }
            }
        }
    }
}

fun getDisplayNameFromUri(uriString: String?): String {
    if (uriString == null) return "Not Selected"
    return try {
        uriString.toUri().lastPathSegment?.substringAfterLast(':') ?: "Selected"
    } catch (e: Exception) {
        "Selected"
    }
}

private data class FolderCardModel(
    val title: String,
    val description: String,
    val selectedPath: String,
    val buttonLabel: String,
    val onSelect: () -> Unit,
    val footer: (@Composable () -> Unit)? = null,
)

@Composable
fun MainScreen(prefs: SharedPreferences) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var fromPathUri by remember { mutableStateOf(prefs.getString("from_path", null)) }
    var toPathUri by remember { mutableStateOf(prefs.getString("to_path", null)) }
    var isSyncing by remember { mutableStateOf(false) }
    var isSourceValid by remember { mutableStateOf(false) }
    var sourceChecked by remember { mutableStateOf(false) }
    var syncProgress by remember { mutableStateOf<SyncProgress?>(null) }
    var syncResult by remember { mutableStateOf<CopyGamelistsResult?>(null) }
    val coroutineScope = rememberCoroutineScope()

    suspend fun validateSourceFolder(context: Context, uriString: String?): Boolean {
        if (uriString == null) return false
        return withContext(Dispatchers.IO) {
            val tree = DocumentFile.fromTreeUri(context, uriString.toUri()) ?: return@withContext false
            val hasGamelists = tree.findFile("gamelists")?.isDirectory == true
            val hasMedia = tree.findFile("downloaded_media")?.isDirectory == true
            hasGamelists && hasMedia
        }
    }

    // Validate saved folder automatically on launch
    LaunchedEffect(fromPathUri) {
        if (fromPathUri != null && !sourceChecked) {
            val valid = validateSourceFolder(context, fromPathUri)
            isSourceValid = valid
            sourceChecked = true
        }
    }

    val fromPathLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri: Uri? ->
            if (uri != null) {
                val contentResolver = context.contentResolver
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )

                fromPathUri = uri.toString()
                prefs.edit { putString("from_path", fromPathUri) }

                coroutineScope.launch {
                    val valid = validateSourceFolder(context, fromPathUri)
                    isSourceValid = valid
                }
            }
        }
    )

    val toPathLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri: Uri? ->
            if (uri != null) {
                val contentResolver = context.contentResolver
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                toPathUri = uri.toString()
                prefs.edit { putString("to_path", toPathUri) }
            }
        }
    )

    val folderCards = listOf(
        FolderCardModel(
            title = "Source Folder",
            description = "Choose the folder containing your 'gamelists' and 'downloaded_media' directories (e.g., ES-DE folder)",
            selectedPath = getDisplayNameFromUri(fromPathUri),
            buttonLabel = "Select Source",
            onSelect = { fromPathLauncher.launch(null) },
            footer = {
                if (fromPathUri != null && !isSourceValid && sourceChecked) {
                    Text(
                        text = "⚠ Folder must contain 'gamelists' and 'downloaded_media'.",
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        ),
        FolderCardModel(
            title = "Destination Folder",
            description = "Choose the folder with your system subdirectories (e.g., 'nds', '3ds'). Gamelists will be synced into each system folder.",
            selectedPath = getDisplayNameFromUri(toPathUri),
            buttonLabel = "Select Destination",
            onSelect = { toPathLauncher.launch(null) }
        )
    )

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "AYASpaceXML",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(vertical = 16.dp)
            )

            Text(
                text = "Sync ES-DE 'gamelist.xml' files and media assets ('image', 'thumbnail') to enable full AYASpace compatibility.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            if (isLandscape) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    folderCards.forEach { card ->
                        FolderCard(
                            title = card.title,
                            description = card.description,
                            selectedPath = card.selectedPath,
                            buttonLabel = card.buttonLabel,
                            onSelect = card.onSelect,
                            modifier = Modifier.weight(1f),
                            footer = card.footer
                        )
                    }
                }
            } else {
                folderCards.forEachIndexed { index, card ->
                    FolderCard(
                        title = card.title,
                        description = card.description,
                        selectedPath = card.selectedPath,
                        buttonLabel = card.buttonLabel,
                        onSelect = card.onSelect,
                        modifier = Modifier.padding(bottom = if (index == folderCards.lastIndex) 32.dp else 20.dp),
                        footer = card.footer
                    )
                }
            }

            syncProgress?.let { progress ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "Sync Progress",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        LinearProgressIndicator(
                            progress = { progress.fraction },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = buildSyncProgressSummary(progress),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (progress.status.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = progress.status,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Sync button
            Button(
                onClick = {
                    if (fromPathUri != null && toPathUri != null) {
                        isSyncing = true
                        syncProgress = SyncProgress(
                            totalSystems = 0,
                            completedSystems = 0,
                            status = "Preparing sync"
                        )
                        coroutineScope.launch {
                            syncResult = GamelistCopier.copyGamelists(
                                context,
                                fromPathUri!!,
                                toPathUri!!
                            ) { progress ->
                                withContext(Dispatchers.Main) {
                                    syncProgress = progress
                                }
                            }
                            syncProgress = null
                            isSyncing = false
                        }
                    }
                },
                enabled = fromPathUri != null && toPathUri != null && !isSyncing && isSourceValid,
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = if (isSyncing) "Syncing..." else "Sync Gamelists",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    syncResult?.let { result ->
        AlertDialog(
            onDismissRequest = { syncResult = null },
            title = {
                Text(if (result.success) "Sync Complete" else "Sync Summary")
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(buildCopyResultSummary(result))
                }
            },
            confirmButton = {
                TextButton(onClick = { syncResult = null }) {
                    Text("Dismiss")
                }
            }
        )
    }
}

@Composable
private fun FolderCard(
    title: String,
    description: String,
    selectedPath: String,
    buttonLabel: String,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    footer: @Composable (() -> Unit)? = null
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp)
            ) {
                Text(
                    text = selectedPath,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onSelect,
                modifier = Modifier.widthIn(min = 160.dp)
            ) {
                Text(buttonLabel)
            }

            footer?.let {
                Spacer(modifier = Modifier.height(10.dp))
                it()
            }
        }
    }
}

private fun buildCopyResultSummary(result: CopyGamelistsResult): String {
    if (result.systemResults.isEmpty()) {
        return result.message
    }

    val details = result.systemResults.joinToString("\n") { systemResult ->
        val status = if (systemResult.success) "OK" else "Failed"
        "$status: ${systemResult.systemName} - ${systemResult.message}"
    }

    return buildString {
        appendLine(result.message)
        appendLine()
        appendLine("Processed: ${result.systemsProcessed}")
        appendLine("Succeeded: ${result.systemsSucceeded}")
        appendLine("Failed: ${result.systemsFailed}")
        appendLine()
        append(details)
    }.trim()
}

private fun buildSyncProgressSummary(progress: SyncProgress): String {
    if (progress.totalSystems <= 0) {
        return "Preparing systems..."
    }

    val currentSystem = progress.currentSystemName ?: "Finalizing"
    val systemActionProgress = if (progress.currentSystemTotalActions > 0) {
        "${progress.currentSystemCompletedActions}/${progress.currentSystemTotalActions}"
    } else {
        "calculating..."
    }

    return buildString {
        append("Systems: ${progress.completedSystems}/${progress.totalSystems}")
        append('\n')
        append("Current: $currentSystem")
        append('\n')
        append("Actions: $systemActionProgress")
    }
}
