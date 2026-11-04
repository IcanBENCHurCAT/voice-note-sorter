// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.icanbenchurcat.voicenotes.R
import com.icanbenchurcat.voicenotes.capture.CaptureSessionState

@Composable
fun ActiveRecordingBanner(
    state: CaptureSessionState.Recording,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val minutes = state.elapsedSeconds / 60
    val seconds = state.elapsedSeconds % 60
    val timeFormatted = String.format("%02d:%02d", minutes, seconds)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .semantics { contentDescription = "Active recording banner" },
        colors = CardDefaults.cardColors(
            containerColor = if (state.showWarning4Min) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (state.isPaused) stringResource(R.string.notif_recording_paused_title) else stringResource(R.string.notif_recording_active_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = timeFormatted,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { contentDescription = "Recording elapsed time $timeFormatted" }
                )
            }

            if (state.showWarning4Min) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.recording_limit_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.isPaused) {
                    FilledTonalButton(
                        onClick = onResume,
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Resume recording" }
                    ) {
                        Text(stringResource(R.string.action_resume_recording))
                    }
                } else {
                    FilledTonalButton(
                        onClick = onPause,
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Pause recording" }
                    ) {
                        Text(stringResource(R.string.action_pause_recording))
                    }
                }

                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Stop recording" },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(stringResource(R.string.action_stop_recording))
                }

                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.semantics { contentDescription = "Cancel recording" }
                ) {
                    Text(stringResource(R.string.action_cancel_recording))
                }
            }
        }
    }
}
