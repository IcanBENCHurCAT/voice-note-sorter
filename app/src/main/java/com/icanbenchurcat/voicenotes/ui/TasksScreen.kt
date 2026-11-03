// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.icanbenchurcat.voicenotes.R
import com.icanbenchurcat.voicenotes.data.ActionItemWithNote
import java.text.DateFormat
import java.util.Date

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
    modifier: Modifier = Modifier
) {
    var selectedFilter by rememberSaveable { mutableStateOf(TaskFilter.ALL) }

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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedFilter == TaskFilter.ALL,
                onClick = { selectedFilter = TaskFilter.ALL },
                label = { Text("${stringResource(R.string.tasks_filter_all)} (${tasks.size})") },
                modifier = Modifier.semantics { contentDescription = "Filter all tasks" }
            )
            FilterChip(
                selected = selectedFilter == TaskFilter.ACTIVE,
                onClick = { selectedFilter = TaskFilter.ACTIVE },
                label = { Text("${stringResource(R.string.tasks_filter_active)} ($activeCount)") },
                modifier = Modifier.semantics { contentDescription = "Filter active tasks" }
            )
            FilterChip(
                selected = selectedFilter == TaskFilter.COMPLETED,
                onClick = { selectedFilter = TaskFilter.COMPLETED },
                label = { Text("${stringResource(R.string.tasks_filter_completed)} ($completedCount)") },
                modifier = Modifier.semantics { contentDescription = "Filter completed tasks" }
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(displayedTasks, key = { it.id }) { item ->
                    TaskCard(
                        item = item,
                        onToggleDone = { onToggleDone(item.id, !item.done) },
                        onOpenNote = { onOpenNote(item.noteId) }
                    )
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
                .padding(12.dp),
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
                    .padding(start = 8.dp)
            ) {
                Text(
                    text = item.text,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (item.done) TextDecoration.LineThrough else TextDecoration.None
                )

                if (item.dueAtEpochMs != null) {
                    val formattedDate = DateFormat.getDateInstance(DateFormat.MEDIUM)
                        .format(Date(item.dueAtEpochMs))
                    Text(
                        text = "Due: $formattedDate",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                val parentTitle = item.noteTitle.ifBlank { "Note #${item.noteId}" }
                Text(
                    text = "From: $parentTitle",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clickable(onClick = onOpenNote)
                        .semantics { contentDescription = "From note $parentTitle" }
                )
            }
        }
    }
}
