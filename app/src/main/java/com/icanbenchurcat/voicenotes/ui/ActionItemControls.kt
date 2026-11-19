// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.icanbenchurcat.voicenotes.R
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Shared due date/time badge using [NoteCardFormatting.formatDueDate] so
 * NoteDetailScreen and TasksScreen always display the identical date and time.
 */
@Composable
fun ActionDueDateBadge(
    dueAtEpochMs: Long?,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (dueAtEpochMs == null || dueAtEpochMs <= 0L) return
    val formattedDue = NoteCardFormatting.formatDueDate(dueAtEpochMs)
    if (formattedDue.isBlank()) return

    if (onClick != null) {
        Box(
            modifier = modifier
                .defaultMinSize(minHeight = 48.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = "Edit due date: $formattedDue" },
            contentAlignment = Alignment.CenterStart
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DateRange,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Due: $formattedDue",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    } else {
        Text(
            text = "Due: $formattedDue",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = modifier.padding(top = 2.dp)
        )
    }
}

/**
 * Inline editor for creating or modifying an action item's description and
 * optional due date/time using Material 3 date and time pickers.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun InlineActionItemEditor(
    editorKey: Any,
    initialText: String,
    initialDueAtEpochMs: Long?,
    confirmLabel: String,
    onSave: (text: String, dueAtEpochMs: Long?) -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var text by rememberSaveable(editorKey) { mutableStateOf(initialText) }
    var dueAtEpochMs by rememberSaveable(editorKey) { mutableStateOf(initialDueAtEpochMs) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showEmptyError by remember { mutableStateOf(false) }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = NoteCardFormatting.toDatePickerInitialUtcMillis(dueAtEpochMs)
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val pickedUtcMillis = datePickerState.selectedDateMillis
                        if (pickedUtcMillis != null) {
                            dueAtEpochMs = NoteCardFormatting.mergePickedDate(
                                existingDueAtEpochMs = dueAtEpochMs,
                                utcSelectedDateMillis = pickedUtcMillis
                            )
                        }
                        showDatePicker = false
                    },
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .semantics { contentDescription = "Confirm due date" }
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDatePicker = false },
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .semantics { contentDescription = "Cancel due date picker" }
                ) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val zone = ZoneId.systemDefault()
        val initialTime = dueAtEpochMs
            ?.takeIf { it > 0L }
            ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
            ?: LocalTime.of(9, 0)
        val timePickerState = rememberTimePickerState(
            initialHour = initialTime.hour,
            initialMinute = initialTime.minute
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text(stringResource(R.string.dialog_pick_due_time)) },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center
                ) {
                    TimePicker(state = timePickerState)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        dueAtEpochMs = NoteCardFormatting.mergePickedTime(
                            existingDueAtEpochMs = dueAtEpochMs,
                            hour = timePickerState.hour,
                            minute = timePickerState.minute
                        )
                        showTimePicker = false
                    },
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .semantics { contentDescription = "Confirm due time" }
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showTimePicker = false },
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .semantics { contentDescription = "Cancel due time picker" }
                ) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    if (it.isNotBlank()) showEmptyError = false
                },
                label = { Text(stringResource(R.string.action_item_text_label)) },
                isError = showEmptyError,
                supportingText = if (showEmptyError) {
                    { Text(stringResource(R.string.error_empty_action_item)) }
                } else {
                    null
                },
                maxLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Action item text input" }
            )

            val activeDue = dueAtEpochMs?.takeIf { it > 0L }
            if (activeDue != null) {
                Text(
                    text = "Due: ${NoteCardFormatting.formatDueDate(activeDue)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .semantics { contentDescription = "Pick due date" }
                ) {
                    Icon(
                        imageVector = Icons.Default.DateRange,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = activeDue?.let { NoteCardFormatting.formatDueDateOnly(it) }
                            ?: stringResource(R.string.action_item_set_date)
                    )
                }

                OutlinedButton(
                    onClick = { showTimePicker = true },
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .semantics { contentDescription = "Pick due time" }
                ) {
                    Text(
                        text = activeDue?.let { NoteCardFormatting.formatDueTimeOnly(it) }
                            ?: stringResource(R.string.action_item_set_time)
                    )
                }

                if (activeDue != null) {
                    val clearDesc = stringResource(R.string.action_item_clear_due)
                    IconButton(
                        onClick = { dueAtEpochMs = null },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics { contentDescription = clearDesc }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = null
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onDelete != null) {
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .semantics { contentDescription = "Remove action item" }
                    ) {
                        Text(stringResource(R.string.dialog_delete_confirm))
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .semantics { contentDescription = "Cancel action item edit" }
                ) {
                    Text(stringResource(R.string.dialog_cancel))
                }
                Button(
                    onClick = {
                        if (text.isBlank()) {
                            showEmptyError = true
                        } else {
                            onSave(text.trim(), dueAtEpochMs)
                        }
                    },
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .semantics { contentDescription = "Save action item" }
                ) {
                    Text(confirmLabel)
                }
            }
        }
    }
}
