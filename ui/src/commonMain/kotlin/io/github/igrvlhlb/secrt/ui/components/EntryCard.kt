package io.github.igrvlhlb.secrt.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Attachment
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.igrvlhlb.secrt.model.EntrySummary
import io.github.igrvlhlb.secrt.model.Label

@Composable
fun EntryCard(entry: EntrySummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ElevatedCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp)) {
            Column(Modifier.widthIn(min = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(entry.diaryDate.day.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(entry.diaryDate.weekdayShort(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.title.ifBlank { "Untitled" },
                        style = MaterialTheme.typography.titleMedium,
                        fontStyle = if (entry.title.isBlank()) FontStyle.Italic else FontStyle.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (entry.hasConflict) {
                        Icon(Icons.Outlined.WarningAmber, "Has conflicting versions", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    }
                    if (entry.attachmentCount > 0) {
                        Icon(Icons.Outlined.Attachment, "Attachments", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(entry.attachmentCount.toString(), style = MaterialTheme.typography.labelMedium)
                    }
                }
                val preview = stripMarkdown(entry.preview)
                if (preview.isNotEmpty()) {
                    Text(preview, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (entry.labels.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        entry.labels.take(4).forEach { LabelPill(it) }
                    }
                }
            }
        }
    }
}

@Composable
fun LabelPill(label: Label) {
    Text(
        label.name,
        style = MaterialTheme.typography.labelSmall,
        color = Color.Black.copy(alpha = 0.8f),
        maxLines = 1,
        modifier = Modifier.background(Color(label.color).copy(alpha = 0.55f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
