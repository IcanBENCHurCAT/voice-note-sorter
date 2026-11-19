// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.icanbenchurcat.voicenotes.R
import com.icanbenchurcat.voicenotes.data.ActionItemWithNote

enum class TaskFilter {
    ALL,
    ACTIVE,
    COMPLETED
}

@Composable
fun TasksScreen(
    tasks: List<ActionItemWithNote>,
    onToggleDone: (Long, Boolean) -> Unit,
    onOpenNote: (Long) -> Unit,
    onUpdateTask: (Long, String, Long?) -> Unit = { _, _, _ -> },
    onDeleteTask: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedFilter by rememberSaveable { mutableStateOf(TaskFilter.ALL) }
    var editingTaskId by rememberSaveable { mutableStateOf<Long?>(null) }

    val activeCount = tasks.count { !it.done }
    val completedCount = tasks.count { it.done }

    val displayedTasks = when (selectedFilter) {
        TaskFilter.ALL -> tasks
        TaskFilter.ACTIVE -> tasks.filter { !it.done }
        TaskFilter.COMPLETED -> tasks.filter { it.done }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedFilter == TaskFilter.ALL,
                onClick = { selectedFilter = TaskFilter.ALL },
                label = { Text("${stringResource(R.string.tasks_filter_all)} (${tasks.size})") },
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selectedFilter == TaskFilter.ALL,
                    borderColor = MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier.semantics { contentDescription = "Filter all tasks" }
            )
            FilterChip(
                selected = selectedFilter == TaskFilter.ACTIVE,
                onClick = { selectedFilter = TaskFilter.ACTIVE },
                label = { Text("${stringResource(R.string.tasks_filter_active)} ($activeCount)") },
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selectedFilter == TaskFilter.ACTIVE,
                    borderColor = MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier.semantics { contentDescription = "Filter active tasks" }
            )
            FilterChip(
                selected = selectedFilter == TaskFilter.COMPLETED,
                onClick = { selectedFilter = TaskFilter.COMPLETED },
                label = { Text("${stringResource(R.string.tasks_filter_completed)} ($completedCount)") },
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selectedFilter == TaskFilter.COMPLETED,
                    borderColor = MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier.semantics { contentDescription = "Filter completed tasks" }
            )
        }

        if (tasks.isNotEmpty()) {
            Text(
                text = stringResource(R.string.tasks_summary_counts, activeCount, completedCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

        if (displayedTasks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.tasks_empty_state),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 80.dp + navBarBottom),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(displayedTasks, key = { it.id }) { item ->
                    if (editingTaskId == item.id) {
                        val parentTitle = item.noteTitle.ifBlank { "Note #${item.noteId}" }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "From: $parentTitle",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.secondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                InlineActionItemEditor(
                                    editorKey = "task_${item.id}",
                                    initialText = item.text,
                                    initialDueAtEpochMs = item.dueAtEpochMs,
                                    confirmLabel = stringResource(R.string.action_save_note),
                                    onSave = { newText, newDue ->
                                        onUpdateTask(item.id, newText, newDue)
                                        editingTaskId = null
                                    },
                                    onCancel = { editingTaskId = null },
                                    onDelete = {
                                        onDeleteTask(item.id)
                                        editingTaskId = null
                                    }
                                )
                            }
                        }
                    } else {
                        TaskCard(
                            item = item,
                            onToggleDone = { onToggleDone(item.id, !item.done) },
                            onOpenNote = { onOpenNote(item.noteId) },
                            onEdit = { editingTaskId = item.id },
                            onDelete = { onDeleteTask(item.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TaskCard(
    item: ActionItemWithNote,
    onToggleDone: () -> Unit,
    onOpenNote: () -> Unit,
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onOpenNote)
            .semantics { contentDescription = "Task: ${item.text}" }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Interactive Checkbox with >= 48dp minimum touch target
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .clickable(
                        role = Role.Checkbox,
                        onClick = onToggleDone
                    ),
                contentAlignment = Alignment.Center
            ) {
                Checkbox(
                    checked = item.done,
                    onCheckedChange = null, // Event handled on touch target container
                    modifier = Modifier.semantics {
                        contentDescription = "Task completion: ${item.text}"
                    }
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
            ) {
                Text(
                    text = item.text,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (item.done) FontWeight.Normal else FontWeight.Medium,
                    color = if (item.done) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textDecoration = if (item.done) TextDecoration.LineThrough else TextDecoration.None,
                    modifier = Modifier.testTag("task_title_${item.id}")
                )

                ActionDueDateBadge(
                    dueAtEpochMs = item.dueAtEpochMs,
                    onClick = onEdit
                )

                val parentTitle = item.noteTitle.ifBlank { "Note #${item.noteId}" }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .clickable(
                            role = Role.Button,
                            onClick = onOpenNote
                        )
                        .padding(top = 2.dp)
                        .semantics { contentDescription = "From note $parentTitle, open note" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "From: $parentTitle",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(18.dp)
                            .testTag("task_source_chevron_${item.id}")
                    )
                }
            }

            IconButton(
                onClick = onEdit,
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = "Edit task: ${item.text}" }
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = "Delete task: ${item.text}" }
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null
                )
            }
        }
    }
}
