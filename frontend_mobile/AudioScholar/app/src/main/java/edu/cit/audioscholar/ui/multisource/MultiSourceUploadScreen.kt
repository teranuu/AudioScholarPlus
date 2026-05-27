package edu.cit.audioscholar.ui.multisource

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jeziellago.compose.markdowntext.MarkdownText
import edu.cit.audioscholar.ui.components.ModernButton
import edu.cit.audioscholar.ui.components.ModernOutlinedButton
import edu.cit.audioscholar.ui.components.ModernTextField
import edu.cit.audioscholar.ui.components.OutputTypeBadge
import edu.cit.audioscholar.ui.components.OutputTypeSelector
import edu.cit.audioscholar.ui.components.QualityReportSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiSourceUploadScreen(
    drawerState: DrawerState,
    scope: CoroutineScope,
    viewModel: MultiSourceUploadViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val uris = mutableListOf<Uri>()
            data?.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) {
                    uris.add(clip.getItemAt(index).uri)
                }
            }
            data?.data?.let { uris.add(it) }
            viewModel.onFilesSelected(uris)
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Merge Sources") },
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Filled.Menu, contentDescription = "Open navigation drawer")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModernTextField(
                        value = uiState.title,
                        onValueChange = viewModel::onTitleChanged,
                        label = "Title",
                        modifier = Modifier.fillMaxWidth()
                    )
                    ModernTextField(
                        value = uiState.description,
                        onValueChange = viewModel::onDescriptionChanged,
                        label = "Description",
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        singleLine = false
                    )
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Output Type", style = MaterialTheme.typography.titleMedium)
                    OutputTypeSelector(
                        selectedValue = uiState.selectedOutputType,
                        onSelected = viewModel::onOutputTypeSelected
                    )
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sources", style = MaterialTheme.typography.titleMedium)
                    ModernOutlinedButton(
                        onClick = {
                            try {
                                picker.launch(
                                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                        type = "*/*"
                                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*"))
                                    }
                                )
                            } catch (e: Exception) {
                                Toast.makeText(context, "Could not open file picker.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.UploadFile, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Select 2-5 audio/video files")
                    }
                }
            }

            itemsIndexed(uiState.files) { index, file ->
                SourceFileCard(
                    label = "Source ${('A'.code + index).toChar()}",
                    file = file,
                    onRemove = { viewModel.removeFile(file.uri) }
                )
            }

            item {
                ModernButton(
                    onClick = viewModel::upload,
                    enabled = uiState.canUpload,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (uiState.isUploading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.CloudUpload, contentDescription = null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (uiState.isUploading) "Merging..." else "Upload and Merge")
                }
            }

            uiState.job?.let { job ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Merged Summary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                OutputTypeBadge(outputType = job.outputType)
                            }
                            Text("Status: ${job.status ?: "PROCESSING"}", style = MaterialTheme.typography.labelMedium)
                            job.failureReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            job.mergedSummary?.formattedSummaryText?.takeIf { it.isNotBlank() }?.let { summary ->
                                MarkdownText(markdown = summary, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                job.sourceFiles.orEmpty().forEach { source ->
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "${source.sourceLabel ?: "Source"}: ${source.fileName ?: "File"}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                QualityReportSection(
                                    report = source.qualityReport,
                                    isLoading = false
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceFileCard(
    label: String,
    file: SelectedSourceFile,
    onRemove: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(file.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = listOfNotNull(file.mimeType, file.sizeBytes?.let { "${it / 1024} KB" }).joinToString(" | "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = "Remove file")
            }
        }
    }
}
