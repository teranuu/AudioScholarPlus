package edu.cit.audioscholar.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import edu.cit.audioscholar.data.remote.dto.QualityIssueDto
import edu.cit.audioscholar.data.remote.dto.QualityReportDto

@Composable
fun QualityReportSection(
    report: QualityReportDto?,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Audio Quality", style = MaterialTheme.typography.titleMedium)
        when {
            isLoading -> QualityStatusCard(
                icon = { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) },
                title = "Checking recording quality",
                body = "Noise and clarity report will appear here."
            )
            report == null || report.status == "UNAVAILABLE" -> QualityStatusCard(
                icon = { Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                title = "Quality report unavailable",
                body = report?.message ?: "Audio quality details are not available yet."
            )
            report.issues.isNullOrEmpty() || report.status == "ALL_CLEAR" -> QualityStatusCard(
                icon = { Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32)) },
                title = "No major audio issues detected",
                body = "The recording quality looks suitable for summarization."
            )
            else -> {
                QualityStatusCard(
                    icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFB26A00)) },
                    title = "${report.issues.size} audio quality issue${if (report.issues.size == 1) "" else "s"} found",
                    body = "Review affected time ranges before relying on generated notes."
                )
                report.issues.forEach { issue ->
                    QualityIssueCard(issue = issue)
                }
            }
        }
    }
}

@Composable
private fun QualityStatusCard(
    icon: @Composable () -> Unit,
    title: String,
    body: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            icon()
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun QualityIssueCard(issue: QualityIssueDto, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(formatIssueType(issue.issueType), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                issue.severity?.let {
                    SuggestionChip(onClick = {}, label = { Text(it.lowercase().replaceFirstChar { char -> char.titlecase() }) })
                }
            }
            Text(
                text = "${issue.startTime ?: "Start"} - ${issue.endTime ?: "End"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = issue.recommendedAction ?: "Consider reviewing this segment manually.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun formatIssueType(type: String?): String {
    return type
        ?.lowercase()
        ?.split("_")
        ?.joinToString(" ") { it.replaceFirstChar { char -> char.titlecase() } }
        ?: "Audio Quality Issue"
}
