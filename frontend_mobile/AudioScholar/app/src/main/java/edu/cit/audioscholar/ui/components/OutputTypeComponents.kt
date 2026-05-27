package edu.cit.audioscholar.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class OutputTypeOption(
    val apiValue: String,
    val label: String,
    val description: String,
    val icon: ImageVector
) {
    NOTES("NOTES", "Notes", "Concise class notes and key points.", Icons.Filled.Article),
    STUDY_MATERIAL("STUDY_MATERIAL", "Study Material", "Structured material for learning the topic.", Icons.Filled.MenuBook),
    REVIEW_MATERIAL("REVIEW_MATERIAL", "Review Material", "Exam-focused review and recall aids.", Icons.Filled.School);

    companion object {
        fun fromApiValue(value: String?): OutputTypeOption? {
            val normalized = value?.trim()?.uppercase()?.replace("-", "_")?.replace(" ", "_")
            return values().firstOrNull { it.apiValue == normalized }
        }
    }
}

@Composable
fun OutputTypeSelector(
    selectedValue: String?,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutputTypeOption.values().forEach { option ->
            OutputTypeCard(
                option = option,
                selected = option.apiValue == selectedValue,
                onClick = { onSelected(option.apiValue) }
            )
        }
    }
}

@Composable
private fun OutputTypeCard(
    option: OutputTypeOption,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = CardDefaults.cardColors(
        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    )
    val border = if (selected) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = colors,
        border = border
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = option.icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(option.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(option.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun OutputTypeBadge(outputType: String?, modifier: Modifier = Modifier) {
    val option = OutputTypeOption.fromApiValue(outputType) ?: return
    AssistChip(
        onClick = {},
        label = { Text(option.label) },
        leadingIcon = {
            Icon(option.icon, contentDescription = null, modifier = Modifier.size(18.dp))
        },
        modifier = modifier,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ),
        border = null
    )
}
