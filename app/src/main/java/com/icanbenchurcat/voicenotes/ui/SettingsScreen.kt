// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.icanbenchurcat.voicenotes.R
import com.icanbenchurcat.voicenotes.ai.CapabilityStatus
import com.icanbenchurcat.voicenotes.ai.Tier
import com.icanbenchurcat.voicenotes.data.StorageStats

/**
 * UI model representing device capability status and storage metrics for the settings screen.
 */
data class CapabilitySettingsUiState(
    val tier: Tier? = null,
    val transcriberStatus: CapabilityStatus = CapabilityStatus.UNAVAILABLE,
    val summarizerStatus: CapabilityStatus = CapabilityStatus.UNAVAILABLE,
    val actionExtractorStatus: CapabilityStatus = CapabilityStatus.UNAVAILABLE,
    val isLoading: Boolean = false,
    val storageStats: StorageStats = StorageStats(0L, 0L, 0),
    val isAutoDeleteOldAudioEnabled: Boolean = false,
    val isDeveloperModeEnabled: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: CapabilitySettingsUiState,
    onBack: () -> Unit,
    onRefreshProbe: () -> Unit = {},
    onToggleAutoDeleteOldAudio: (Boolean) -> Unit = {},
    onToggleDeveloperMode: (Boolean) -> Unit = {},
    onDeleteAllNotes: () -> Unit = {}
) {
    var showEnableRetentionDialog by remember { mutableStateOf(false) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }

    if (showEnableRetentionDialog) {
        AlertDialog(
            onDismissRequest = { showEnableRetentionDialog = false },
            title = { Text(stringResource(R.string.dialog_enable_retention_title)) },
            text = { Text(stringResource(R.string.dialog_enable_retention_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEnableRetentionDialog = false
                        onToggleAutoDeleteOldAudio(true)
                    }
                ) {
                    Text(stringResource(R.string.action_enable))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEnableRetentionDialog = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        )
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text(stringResource(R.string.dialog_delete_all_title)) },
            text = { Text(stringResource(R.string.dialog_delete_all_message)) },
            confirmButton = {
                val confirmDeleteAllDesc = stringResource(R.string.cd_confirm_delete_all_notes)
                TextButton(
                    onClick = {
                        showDeleteAllDialog = false
                        onDeleteAllNotes()
                    },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.semantics { contentDescription = confirmDeleteAllDesc }
                ) {
                    Text(stringResource(R.string.dialog_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    val backDesc = stringResource(R.string.settings_back)
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = backDesc }
                    ) {
                        Text("←", style = MaterialTheme.typography.titleLarge)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .testTag("settings_list"),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                DeviceTierCard(
                    tier = uiState.tier,
                    isLoading = uiState.isLoading
                )
            }

            item {
                Text(
                    text = stringResource(R.string.capabilities_heading),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }

            item {
                CapabilityItemCard(
                    name = stringResource(R.string.cap_transcription_name),
                    status = uiState.transcriberStatus,
                    description = stringResource(R.string.cap_transcription_desc),
                    unavailableReason = stringResource(R.string.cap_transcription_unavailable_reason)
                )
            }

            item {
                CapabilityItemCard(
                    name = stringResource(R.string.cap_summarization_name),
                    status = uiState.summarizerStatus,
                    description = stringResource(R.string.cap_summarization_desc),
                    unavailableReason = stringResource(R.string.cap_summarization_unavailable_reason)
                )
            }

            item {
                CapabilityItemCard(
                    name = stringResource(R.string.cap_action_extraction_name),
                    status = uiState.actionExtractorStatus,
                    description = stringResource(R.string.cap_action_extraction_desc),
                    unavailableReason = stringResource(R.string.cap_action_extraction_unavailable_reason)
                )
            }

            item {
                OutlinedButton(
                    onClick = onRefreshProbe,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    enabled = !uiState.isLoading
                ) {
                    Text(stringResource(R.string.action_refresh_capabilities))
                }
            }

            item {
                Text(
                    text = stringResource(R.string.storage_heading),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )
            }

            item {
                StorageStatsCard(
                    storageStats = uiState.storageStats,
                    isAutoDeleteEnabled = uiState.isAutoDeleteOldAudioEnabled,
                    onToggleAutoDelete = { enable ->
                        if (enable) {
                            showEnableRetentionDialog = true
                        } else {
                            onToggleAutoDeleteOldAudio(false)
                        }
                    },
                    onDeleteAllNotes = { showDeleteAllDialog = true }
                )
            }

            item {
                Text(
                    text = stringResource(R.string.settings_developer_mode_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )
            }

            item {
                DeveloperModeCard(
                    isDeveloperModeEnabled = uiState.isDeveloperModeEnabled,
                    onToggleDeveloperMode = onToggleDeveloperMode
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun DeveloperModeCard(
    isDeveloperModeEnabled: Boolean,
    onToggleDeveloperMode: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(
                    text = stringResource(R.string.settings_developer_mode_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_developer_mode_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Switch(
                checked = isDeveloperModeEnabled,
                onCheckedChange = onToggleDeveloperMode,
                modifier = Modifier.semantics {
                    contentDescription = "Toggle developer mode"
                }
            )
        }
    }
}

@Composable
fun DeviceTierCard(
    tier: Tier?,
    isLoading: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.active_capability_tier),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val (tierBadgeText, tierExplanation) = when (tier) {
                Tier.FULL -> Pair(
                    stringResource(R.string.tier_badge_full),
                    stringResource(R.string.tier_explanation_full)
                )
                Tier.CORE -> Pair(
                    stringResource(R.string.tier_badge_core),
                    stringResource(R.string.tier_explanation_core)
                )
                Tier.MINIMAL, null -> Pair(
                    stringResource(R.string.tier_badge_basic),
                    stringResource(R.string.tier_explanation_basic)
                )
            }

            Text(
                text = tierBadgeText,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = tierExplanation,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun CapabilityItemCard(
    name: String,
    status: CapabilityStatus,
    description: String,
    unavailableReason: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                StatusChip(status = status)
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (status != CapabilityStatus.AVAILABLE) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = unavailableReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
fun StatusChip(status: CapabilityStatus) {
    val (label, chipColors) = when (status) {
        CapabilityStatus.AVAILABLE -> Pair(
            stringResource(R.string.status_available),
            SuggestionChipDefaults.suggestionChipColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                labelColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        )
        CapabilityStatus.DOWNLOADABLE -> Pair(
            stringResource(R.string.status_downloadable),
            SuggestionChipDefaults.suggestionChipColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                labelColor = MaterialTheme.colorScheme.onTertiaryContainer
            )
        )
        CapabilityStatus.BUSY -> Pair(
            stringResource(R.string.status_busy),
            SuggestionChipDefaults.suggestionChipColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                labelColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        )
        CapabilityStatus.UNAVAILABLE -> Pair(
            stringResource(R.string.status_unavailable),
            SuggestionChipDefaults.suggestionChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                labelColor = MaterialTheme.colorScheme.outline
            )
        )
    }

    SuggestionChip(
        onClick = {},
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = chipColors,
        border = null
    )
}

@Composable
fun StorageStatsCard(
    storageStats: StorageStats,
    isAutoDeleteEnabled: Boolean,
    onToggleAutoDelete: (Boolean) -> Unit,
    onDeleteAllNotes: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.storage_audio_usage),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = formatFileSize(storageStats.audioSizeBytes),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.storage_database_usage),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "${formatFileSize(storageStats.databaseSizeBytes)} (${stringResource(R.string.storage_note_count, storageStats.noteCount)})",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(
                        text = stringResource(R.string.storage_auto_delete_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.storage_auto_delete_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Switch(
                    checked = isAutoDeleteEnabled,
                    onCheckedChange = onToggleAutoDelete,
                    modifier = Modifier.semantics {
                        contentDescription = "Toggle auto-delete audio older than 90 days"
                    }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            val deleteAllDesc = stringResource(R.string.cd_delete_all_notes)
            OutlinedButton(
                onClick = onDeleteAllNotes,
                enabled = storageStats.noteCount > 0 || storageStats.audioSizeBytes > 0L,
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = deleteAllDesc }
            ) {
                Text(stringResource(R.string.action_delete_all_notes))
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(java.util.Locale.US, "%.1f GB", gb)
        mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
        kb >= 1.0 -> String.format(java.util.Locale.US, "%.1f KB", kb)
        else -> "$bytes B"
    }
}
