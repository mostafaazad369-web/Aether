package studio.cluvex.aether.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.core.PingMonitor
import studio.cluvex.aether.core.PingStats

@Composable
internal fun PingDetailsDialog(onDismiss: () -> Unit) {
    val samples by PingMonitor.history.collectAsState()
    val summary = PingStats.of(samples)
    fun ms(value: Long) = if (value < 0L) "—" else "$value ms"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ping_details_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DetailLine(stringResource(R.string.ping_details_samples), summary.samples.toString())
                DetailLine(stringResource(R.string.ping_details_answered), summary.answered.toString())
                DetailLine(stringResource(R.string.ping_details_last), ms(summary.lastMs))
                DetailLine(stringResource(R.string.ping_details_min), ms(summary.minMs))
                DetailLine(stringResource(R.string.ping_details_max), ms(summary.maxMs))
                DetailLine(stringResource(R.string.ping_details_avg), ms(summary.avgMs))
                DetailLine(stringResource(R.string.ping_details_jitter), ms(summary.jitterMs))
                DetailLine(stringResource(R.string.ping_details_loss), "${summary.lossPercent}%")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ping_details_close)) }
        },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelLarge)
    }
}
