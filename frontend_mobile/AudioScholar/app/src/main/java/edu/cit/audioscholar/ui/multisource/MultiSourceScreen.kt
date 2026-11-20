package edu.cit.audioscholar.ui.multisource

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import dev.jeziellago.compose.markdowntext.MarkdownText
import edu.cit.audioscholar.data.remote.dto.MultiSourceJobDto
import edu.cit.audioscholar.data.remote.dto.MultiSourceSummaryDto

private val choices = listOf("NOTES" to "Notes", "STUDY_MATERIAL" to "Study Material", "REVIEW_MATERIAL" to "Review Material")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiSourceScreen(navController: NavHostController, viewModel: MultiSourceViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        viewModel.selectSources(uris)
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Merge lecture sources") }, navigationIcon = {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Select two to five audio, video, or text-based PDF sources from the same lecture.")
            Text("Combined limit: 100 MB. Media limit: 75 minutes total. Scanned PDFs cannot be processed.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                picker.launch(arrayOf("audio/*", "video/*", "application/pdf", "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.ms-powerpoint",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation"))
            }, enabled = !state.submitting && state.sources.size < 5) { Text("Add sources") }
            state.sources.forEachIndexed { index, source ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("Source ${'A' + index}: ${source.name}")
                            Text(if (source.isMedia) "Media" else "Document", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = { viewModel.removeSource(source.uri) }, enabled = !state.submitting) {
                            Text("Remove")
                        }
                    }
                }
            }
            Text("Output type", style = MaterialTheme.typography.titleMedium)
            choices.forEach { (value, label) ->
                FilterChip(selected = state.outputType == value, onClick = { viewModel.setOutputType(value) },
                    label = { Text(label) }, enabled = !state.submitting)
            }
            OutlinedTextField(value = state.title, onValueChange = viewModel::setTitle,
                label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), enabled = !state.submitting)
            Button(onClick = viewModel::submit, enabled = !state.submitting && state.sources.size in 2..5 && state.outputType != null) {
                Text("Upload and generate")
            }
            if (state.submitting) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.height(24.dp))
                    Text("Uploading sources. Keep this screen open until accepted.")
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.job?.let { job ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(job.title ?: "Merged lecture", style = MaterialTheme.typography.titleMedium)
                        Text("${choices.find { it.first == job.outputType }?.second ?: job.outputType ?: "Output"} · ${job.status ?: "PROCESSING"}")
                        job.sourceFiles.orEmpty().forEach { source ->
                            Text("${source.sourceLabel ?: "Source"}: ${source.fileName ?: "Unknown file"} · ${source.uploadStatus ?: "Processing"}")
                        }
                        if (job.quotaRetryAt != null) Text("Waiting for quota; retry scheduled at ${job.quotaRetryAt}.")
                        if (job.status == "FAILED") Text(job.failureReason ?: "Processing failed. Select the sources again to retry.",
                            color = MaterialTheme.colorScheme.error)
                        if (job.status == "COMPLETE" && job.mergedSummary != null) {
                            MergedContent(job)
                        } else if (job.status == "INCOMPLETE" && job.mergedSummary != null) {
                            Text("Partial result: required source evidence or validation is incomplete. Treat unresolved citations as unverified.",
                                color = MaterialTheme.colorScheme.error)
                            MergedContent(job)
                        }
                    }
                }
            }
            if (state.recentJobs.isNotEmpty()) {
                Text("Recent merged jobs", style = MaterialTheme.typography.titleMedium)
                state.recentJobs.forEach { job ->
                    OutlinedButton(onClick = { job.jobId?.let(viewModel::openJob) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${job.title ?: "Merged lecture"} · ${job.status ?: "PROCESSING"}")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun MergedContent(job: MultiSourceJobDto) {
    val summary: MultiSourceSummaryDto = job.mergedSummary ?: return
    val files = job.sourceFiles.orEmpty()
    fun sourceName(id: String?): String = files.find { it.sourceFileId == id }
        ?.let { "${it.sourceLabel ?: "Source"}: ${it.fileName ?: "Unknown file"}" } ?: "Source unresolved"
    val cards = summary.flashcards.orEmpty()
    if (job.outputType == "REVIEW_MATERIAL" && cards.isNotEmpty()) {
        var index by remember(job.jobId) { mutableIntStateOf(0) }
        var flipped by remember(job.jobId, index) { mutableStateOf(false) }
        val card = cards[index.coerceIn(0, cards.lastIndex)]
        Text("Flashcard ${index + 1} of ${cards.size}", style = MaterialTheme.typography.titleSmall)
        Text(card.front.orEmpty())
        OutlinedButton(onClick = { flipped = !flipped }) { Text(if (flipped) "Hide answer" else "Flip to answer") }
        if (flipped) Text(card.back.orEmpty())
        Text(if (card.sourceSegmentId.isNullOrBlank()) "Supporting evidence unresolved" else sourceName(card.sourceFileId),
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { index-- }, enabled = index > 0) { Text("Previous") }
            OutlinedButton(onClick = { index++ }, enabled = index < cards.lastIndex) { Text("Next") }
        }
    } else {
        MarkdownText(markdown = summary.formattedSummaryText.orEmpty(), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth())
    }
    summary.summaryKeyPoints.orEmpty().forEach { point ->
        val contributors = job.sourceAttributions.orEmpty().filter { it.keyPointId == point.keyPointId }
        val labels = if (contributors.isEmpty()) listOf("Supporting evidence unresolved") else contributors.map {
            sourceName(it.sourceFileId) + if (it.attributionStatus == "UNRESOLVED") " (evidence unresolved)" else ""
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(point.text.orEmpty())
                Text(labels.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                point.claimEvidence.orEmpty().forEach { evidence ->
                    Text("${sourceName(evidence.sourceFileId)}: “${evidence.quote.orEmpty()}”", style = MaterialTheme.typography.bodySmall)
                }
                if (!point.sourceStartTime.isNullOrBlank() && !point.sourceEndTime.isNullOrBlank())
                    Text("${point.sourceStartTime}–${point.sourceEndTime}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
