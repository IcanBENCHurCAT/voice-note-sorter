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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.icanbenchurcat.voicenotes.R
import com.icanbenchurcat.voicenotes.ai.CapabilityStatus
import com.icanbenchurcat.voicenotes.ai.Tier

/**
 * UI model representing device capability status for the settings screen.
 */
data class CapabilitySettingsUiState(
    val tier: Tier? = null,
    val transcriberStatus: CapabilityStatus = CapabilityStatus.UNAVAILABLE,
    val summarizerStatus: CapabilityStatus = CapabilityStatus.UNAVAILABLE,
    val actionExtractorStatus: CapabilityStatus = CapabilityStatus.UNAVAILABLE,
    val isLoading: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: CapabilitySettingsUiState,
    onBack: () -> Unit,
    onRefreshProbe: () -> Unit = {}
) {
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
                .padding(horizontal = 16.dp),
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
                Spacer(modifier = Modifier.height(16.dp))
            }
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
