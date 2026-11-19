// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.icanbenchurcat.voicenotes.R
import com.icanbenchurcat.voicenotes.ai.CapabilityStatus
import com.icanbenchurcat.voicenotes.ai.DefaultBucketProfileProvider
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.graphics.Color
import com.icanbenchurcat.voicenotes.data.ActionItem
import com.icanbenchurcat.voicenotes.data.ActionItemWithNote
import com.icanbenchurcat.voicenotes.data.Bucket
import com.icanbenchurcat.voicenotes.data.Note
import com.icanbenchurcat.voicenotes.data.NoteStatus
import com.icanbenchurcat.voicenotes.data.NoteTag
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.icanbenchurcat.voicenotes.audio.AudioPlaybackState
import com.icanbenchurcat.voicenotes.audio.PlaybackStatus
import com.icanbenchurcat.voicenotes.audio.rememberAudioPlayerState
import kotlinx.coroutines.launch

/**
 * Top app bar for root tabs (Notes / Tasks), separating structural actions such as
 * bucket management and settings from the scrollable filter chip strip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootTopAppBar(
    onManageBuckets: () -> Unit = {},
    onOpenSettings: (() -> Unit)? = null,
    developerActions: @Composable RowScope.() -> Unit = {}
) {
    var showOverflowMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        actions = {
            developerActions()
            if (onOpenSettings != null) {
                val settingsDesc = stringResource(R.string.action_settings)
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = settingsDesc }
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null
                    )
                }
            }
            Box {
                IconButton(
                    onClick = { showOverflowMenu = true },
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp, minWidth = 48.dp)
                        .semantics { contentDescription = "More options" }
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = null
                    )
                }
                DropdownMenu(
                    expanded = showOverflowMenu,
                    onDismissRequest = { showOverflowMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_manage_buckets)) },
                        onClick = {
                            showOverflowMenu = false
                            onManageBuckets()
                        },
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .semantics { contentDescription = "Manage buckets" }
                    )
                    if (onOpenSettings != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_settings)) },
                            onClick = {
                                showOverflowMenu = false
                                onOpenSettings()
                            },
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .semantics { contentDescription = "Open settings" }
                        )
                    }
                }
            }
        }
    )
}

internal fun needsReviewBadgeColorTokens(colorScheme: ColorScheme): Pair<Color, Color> =
    colorScheme.tertiaryContainer to colorScheme.onTertiaryContainer

@Composable
private fun NoteFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    labelText: String,
    contentDesc: String
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        leadingIcon = if (selected) {
            {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier
                        .size(FilterChipDefaults.IconSize)
                        .testTag("filter_chip_selected_icon")
                )
            }
        } else {
            null
        },
        label = { Text(labelText) },
        modifier = Modifier
            .defaultMinSize(minHeight = 48.dp)
            .semantics { contentDescription = contentDesc }
    )
}

/** Notes list: filed cards, newest first. The bucket chip and the tier
 *  marker are shown on purpose - the demo value of this app is that the
 *  user (and the developer reading the code) can see what the ladder did. */
@Composable
fun NotesListScreen(
    notes: List<Note>,
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    buckets: List<Bucket> = emptyList(),
    tasks: List<ActionItemWithNote> = emptyList(),
    tags: List<String> = emptyList(),
    noteTags: List<NoteTag> = emptyList(),
    onOpen: (Note) -> Unit
) {
    var selectedBucketKind by rememberSaveable { mutableStateOf<String?>(null) }
    var filterNeedsReviewOnly by rememberSaveable { mutableStateOf(false) }
    var selectedTag by rememberSaveable { mutableStateOf<String?>(null) }

    val needsReviewCount = notes.count { it.status == NoteStatus.NEEDS_REVIEW.name }

    val displayedNotes = notes.filter { note ->
        val matchesReview = !filterNeedsReviewOnly || note.status == NoteStatus.NEEDS_REVIEW.name
        val matchesBucket = selectedBucketKind == null || note.bucketKind == selectedBucketKind
        val matchesTag = selectedTag == null || noteTags.any { it.noteId == note.id && it.tagName == selectedTag }
        matchesReview && matchesBucket && matchesTag
    }

    val isSearching = searchQuery.isNotBlank()
    val isLibraryEmpty = notes.isEmpty() && !isSearching

    // Precalculate task counts per note (done / total)
    val taskCountsByNote = remember(tasks) {
        tasks.groupBy { it.noteId }.mapValues { (_, items) ->
            val doneCount = items.count { it.done }
            Pair(doneCount, items.size)
        }
    }

    // Precalculate tags per note
    val tagsByNote = remember(noteTags) {
        noteTags.groupBy { it.noteId }.mapValues { (_, items) ->
            items.map { it.tagName }.sorted()
        }
    }

    // Active buckets list (fallback to defaults if DB buckets not loaded yet)
    val effectiveBuckets = if (buckets.isNotEmpty()) {
        buckets
    } else {
        DefaultBucketProfileProvider.profile().buckets.map { Bucket(kind = it.kind, name = it.name) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        if (isLibraryEmpty) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 88.dp + navBarBottom),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(64.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_tile_record),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.empty_notes_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.empty_notes_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
            return
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { contentDescription = "Search notes" },
            placeholder = { Text(stringResource(R.string.search_hint)) },
            singleLine = true,
            trailingIcon = {
                if (isSearching) {
                    TextButton(
                        onClick = { onSearchQueryChange("") },
                        modifier = Modifier.semantics {
                            contentDescription = "Clear search"
                        }
                    ) {
                        Text("✕")
                    }
                }
            }
        )

        // Horizontal filter bar: All, Needs review, and bucket chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val isAllSelected = selectedBucketKind == null && !filterNeedsReviewOnly && selectedTag == null
            NoteFilterChip(
                selected = isAllSelected,
                onClick = {
                    selectedBucketKind = null
                    filterNeedsReviewOnly = false
                    selectedTag = null
                },
                labelText = "All (${notes.size})",
                contentDesc = "Filter all notes"
            )
            NoteFilterChip(
                selected = filterNeedsReviewOnly,
                onClick = {
                    filterNeedsReviewOnly = !filterNeedsReviewOnly
                    if (filterNeedsReviewOnly) {
                        selectedBucketKind = null
                        selectedTag = null
                    }
                },
                labelText = "Needs review ($needsReviewCount)",
                contentDesc = "Filter Needs Review notes"
            )
            effectiveBuckets.forEach { b ->
                val bucketCount = notes.count { it.bucketKind == b.kind }
                val isBucketSelected = selectedBucketKind == b.kind
                NoteFilterChip(
                    selected = isBucketSelected,
                    onClick = {
                        selectedBucketKind = if (isBucketSelected) null else b.kind
                        if (selectedBucketKind != null) {
                            filterNeedsReviewOnly = false
                        }
                    },
                    labelText = "${b.name} ($bucketCount)",
                    contentDesc = "Filter by bucket ${b.name}"
                )
            }
        }

        // Tag filter chips (if tags exist in library)
        if (tags.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Tags:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                tags.forEach { tagName ->
                    val isSelected = selectedTag == tagName
                    NoteFilterChip(
                        selected = isSelected,
                        onClick = {
                            selectedTag = if (isSelected) null else tagName
                            if (selectedTag != null) {
                                filterNeedsReviewOnly = false
                            }
                        },
                        labelText = "#$tagName",
                        contentDesc = "Filter by tag $tagName"
                    )
                }
            }
        }

        if (displayedNotes.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                if (filterNeedsReviewOnly && !isSearching) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_check_circle),
                            contentDescription = stringResource(R.string.empty_needs_review_title),
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.empty_needs_review_title),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.empty_needs_review_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { filterNeedsReviewOnly = false },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) {
                            Text(stringResource(R.string.action_view_all_notes))
                        }
                    }
                } else {
                    Text(
                        text = if (isSearching) {
                            stringResource(R.string.search_no_matches)
                        } else if (selectedBucketKind != null) {
                            "No notes in this bucket."
                        } else if (selectedTag != null) {
                            "No notes with tag #$selectedTag."
                        } else {
                            "No notes found."
                        },
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
            return
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("notes_list"),
            contentPadding = PaddingValues(bottom = 80.dp + navBarBottom)
        ) {
            items(displayedNotes, key = { it.id }) { note ->
                val deduplicatedSummary = remember(note.title, note.summary) {
                    NoteCardFormatting.deduplicateSummary(note.title, note.summary)
                }
                val formattedTime = remember(note.createdAtEpochMs) {
                    NoteCardFormatting.formatTimestamp(note.createdAtEpochMs)
                }
                val noteTasks = taskCountsByNote[note.id]
                val noteTagList = tagsByNote[note.id] ?: emptyList()

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clickable { onOpen(note) }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = note.title.ifBlank { "Untitled note" },
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (formattedTime.isNotBlank()) {
                                Text(
                                    text = formattedTime,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }

                        if (deduplicatedSummary.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = deduplicatedSummary,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        val metaDesc = stringResource(R.string.cd_note_meta, note.bucketKind, note.tierUsed)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = note.bucketKind,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.semantics { contentDescription = metaDesc }
                            )

                            if (note.status == NoteStatus.NEEDS_REVIEW.name) {
                                val (badgeContainerColor, badgeLabelColor) = needsReviewBadgeColorTokens(MaterialTheme.colorScheme)
                                SuggestionChip(
                                    onClick = { onOpen(note) },
                                    label = {
                                        Text(
                                            text = "Needs review",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    },
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = badgeContainerColor,
                                        labelColor = badgeLabelColor
                                    ),
                                    border = null,
                                    modifier = Modifier.semantics { contentDescription = "Needs review badge" }
                                )
                            }

                            if (noteTasks != null && noteTasks.second > 0) {
                                val (doneCount, totalCount) = noteTasks
                                SuggestionChip(
                                    onClick = { onOpen(note) },
                                    label = {
                                        Text(
                                            text = "Tasks $doneCount/$totalCount",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    },
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    border = null,
                                    modifier = Modifier.semantics { contentDescription = "Tasks: $doneCount of $totalCount completed" }
                                )
                            }
                        }

                        if (noteTagList.isNotEmpty()) {
                            val (visibleTags, overflowCount) = remember(noteTagList) {
                                NoteCardFormatting.collapseTags(noteTagList, maxVisible = 3)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                visibleTags.forEach { tag ->
                                    Text(
                                        text = "#$tag",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(end = 4.dp)
                                    )
                                }
                                if (overflowCount > 0) {
                                    Text(
                                        text = "+$overflowCount",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.padding(end = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun NoteDetailScreen(
    note: Note,
    actions: List<ActionItem> = emptyList(),
    tags: List<String> = emptyList(),
    transcriberStatus: CapabilityStatus = CapabilityStatus.UNAVAILABLE,
    availableBuckets: List<String> = DefaultBucketProfileProvider.profile().buckets.map { it.kind },
    buckets: List<Bucket> = emptyList(),
    isDeveloperModeEnabled: Boolean = false,
    onBack: (() -> Unit)? = null,
    onConfirmOrChangeBucket: (String) -> Unit = {},
    onToggleActionDone: ((Long, Boolean) -> Unit)? = null,
    onAddAction: (text: String, dueAtEpochMs: Long?) -> Unit = { _, _ -> },
    onUpdateAction: (id: Long, text: String, dueAtEpochMs: Long?) -> Unit = { _, _, _ -> },
    onDeleteAction: (id: Long) -> Unit = {},
    onDeleteNote: () -> Unit = {},
    audioPlaybackState: AudioPlaybackState = rememberAudioPlayerState(note.audioPath),
    onSaveEdit: (title: String, transcript: String) -> Unit = { _, _ -> },
    onShareText: () -> Unit = {},
    onShareAudio: () -> Unit = {},
    hasAudioFile: Boolean = false,
    onManageBuckets: (() -> Unit)? = null,
    developerActions: @Composable RowScope.() -> Unit = {}
) {
    var isEditing by rememberSaveable { mutableStateOf(false) }
    var editTitle by rememberSaveable(note.id, isEditing) { mutableStateOf(note.title) }
    var editTranscript by rememberSaveable(note.id, isEditing) { mutableStateOf(note.transcript) }
    var editingActionId by rememberSaveable(note.id) { mutableStateOf<Long?>(null) }
    var isAddingAction by rememberSaveable(note.id) { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showShareMenu by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    val confirmFiling: (String, String) -> Unit = { bucketKind, bucketDisplayName ->
        onConfirmOrChangeBucket(bucketKind)
        val message = context.getString(R.string.snackbar_filing_confirmed, bucketDisplayName)
        coroutineScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
        }
    }

    BackHandler(enabled = isEditing || isAddingAction || editingActionId != null) {
        if (isEditing) {
            isEditing = false
            editTitle = note.title
            editTranscript = note.transcript
        } else {
            isAddingAction = false
            editingActionId = null
        }
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text(stringResource(R.string.dialog_delete_note_title)) },
            text = { Text(stringResource(R.string.dialog_delete_note_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        onDeleteNote()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.semantics { contentDescription = "Confirm delete note" }
                ) {
                    Text(stringResource(R.string.dialog_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirmDialog = false },
                    modifier = Modifier.semantics { contentDescription = "Cancel delete note" }
                ) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = note.title.ifBlank { "Untitled note" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.semantics { contentDescription = "Navigate back" }
                        ) {
                            Text("←", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            editTitle = note.title
                            editTranscript = note.transcript
                            isEditing = true
                        },
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp, minWidth = 48.dp)
                            .semantics { contentDescription = "Edit note" }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null
                        )
                    }
                    Box {
                        IconButton(
                            onClick = {
                                if (hasAudioFile) {
                                    showShareMenu = true
                                } else {
                                    onShareText()
                                }
                            },
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp, minWidth = 48.dp)
                                .semantics { contentDescription = "Share note" }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = null
                            )
                        }
                        if (hasAudioFile) {
                            DropdownMenu(
                                expanded = showShareMenu,
                                onDismissRequest = { showShareMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_share_text)) },
                                    onClick = {
                                        showShareMenu = false
                                        onShareText()
                                    },
                                    modifier = Modifier.semantics { contentDescription = "Share note text" }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_share_audio)) },
                                    onClick = {
                                        showShareMenu = false
                                        onShareAudio()
                                    },
                                    modifier = Modifier.semantics { contentDescription = "Share note audio" }
                                )
                            }
                        }
                    }
                    developerActions()
                    Box {
                        IconButton(
                            onClick = { showOverflowMenu = true },
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp, minWidth = 48.dp)
                                .semantics { contentDescription = "More options" }
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = null
                            )
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_delete_note)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    showDeleteConfirmDialog = true
                                },
                                modifier = Modifier.semantics { contentDescription = "Delete note" }
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .clipToBounds()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 16.dp + innerPadding.calculateStartPadding(layoutDirection),
                    end = 16.dp + innerPadding.calculateEndPadding(layoutDirection),
                    top = 16.dp,
                    bottom = 16.dp + maxOf(innerPadding.calculateBottomPadding(), navBarBottom)
                )
                .testTag("note_detail_content")
        ) {
        if (isEditing) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        isEditing = false
                        editTitle = note.title
                        editTranscript = note.transcript
                    },
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .semantics { contentDescription = "Cancel editing note" }
                ) {
                    Text(stringResource(R.string.action_cancel_edit))
                }
                OutlinedButton(
                    onClick = {
                        isEditing = false
                        onSaveEdit(editTitle, editTranscript)
                    },
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .semantics { contentDescription = "Save edited note" }
                ) {
                    Text(stringResource(R.string.action_save_note))
                }
            }
            OutlinedTextField(
                value = editTitle,
                onValueChange = { editTitle = it },
                label = { Text(stringResource(R.string.edit_title_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .semantics { contentDescription = "Edit title input" }
            )
            OutlinedTextField(
                value = editTranscript,
                onValueChange = { editTranscript = it },
                label = { Text(stringResource(R.string.edit_transcript_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .semantics { contentDescription = "Edit transcript input" },
                minLines = 4
            )
        }
        val effectiveBuckets = remember(availableBuckets, buckets) {
            if (buckets.isNotEmpty()) {
                buckets.map { it.kind to it.name }
            } else {
                availableBuckets.map { kind ->
                    kind to NoteCardFormatting.formatBucketDisplayName(kind)
                }
            }
        }
        if (effectiveBuckets.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                effectiveBuckets.forEach { (bucketKind, bucketName) ->
                    val isFiledBucket = note.bucketKind == bucketKind
                    val isNeedsReview = note.status == NoteStatus.NEEDS_REVIEW.name
                    val isSelected = isFiledBucket && !isNeedsReview
                    val isSuggested = isFiledBucket && isNeedsReview
                    val chipColors = if (isSuggested) {
                        FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            labelColor = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    } else {
                        FilterChipDefaults.filterChipColors()
                    }
                    val chipBorder = if (isSuggested) {
                        FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = false,
                            borderColor = MaterialTheme.colorScheme.tertiary
                        )
                    } else {
                        FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            // M3's default outlineVariant is ~1.5:1 on the background;
                            // `outline` keeps unselected pills at >= 3:1 (WCAG 1.4.11).
                            borderColor = MaterialTheme.colorScheme.outline
                        )
                    }
                    val chipDesc = if (isSuggested) {
                        stringResource(R.string.cd_suggested_bucket, bucketName)
                    } else {
                        stringResource(R.string.cd_file_in_bucket, bucketName)
                    }
                    FilterChip(
                        selected = isSelected,
                        onClick = { confirmFiling(bucketKind, bucketName) },
                        label = {
                            if (isSuggested) {
                                Text(stringResource(R.string.suggested_bucket_label, bucketName))
                            } else {
                                Text(bucketName)
                            }
                        },
                        colors = chipColors,
                        border = chipBorder,
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .then(if (isSuggested) Modifier.testTag("suggested_bucket_chip") else Modifier)
                            .semantics {
                                contentDescription = chipDesc
                            }
                    )
                }
                if (onManageBuckets != null) {
                    TextButton(
                        onClick = onManageBuckets,
                        modifier = Modifier.semantics { contentDescription = "Manage buckets" }
                    ) {
                        Text(stringResource(R.string.action_manage_buckets))
                    }
                }
            }
        }
        if (note.status == NoteStatus.NEEDS_REVIEW.name) {
            val currentBucketDisplayName = remember(note.bucketKind, buckets) {
                NoteCardFormatting.formatBucketDisplayName(note.bucketKind, buckets)
            }
            Button(
                onClick = { confirmFiling(note.bucketKind, currentBucketDisplayName) },
                modifier = Modifier
                    .padding(top = 8.dp)
                    .defaultMinSize(minHeight = 48.dp)
                    .semantics { contentDescription = "Confirm bucket filing" }
            ) {
                Text(stringResource(R.string.action_confirm_filing, currentBucketDisplayName))
            }
        }
        if (tags.isNotEmpty()) {
            FlowRow(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .testTag("note_detail_tags"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tags.forEach { tag ->
                    SuggestionChip(
                        onClick = {},
                        label = { Text("#$tag", style = MaterialTheme.typography.labelSmall) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        border = null,
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    )
                }
            }
        }
        if (note.transcript.isNotBlank()) {
            Text(
                text = note.transcript,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 16.dp)
            )
        } else {
            val emptyCopy = if (hasAudioFile) {
                "No transcript yet. Playback is available; this note was filed by hand (Tier 1)."
            } else {
                "No transcript yet. This note was filed by hand (Tier 1)."
            }
            Text(
                text = emptyCopy,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp)
            )
            if (transcriberStatus == CapabilityStatus.DOWNLOADABLE) {
                Text(
                    text = "On-device speech model is downloadable; note can be upgraded once downloaded.",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.action_items_heading),
                style = MaterialTheme.typography.titleSmall
            )
            TextButton(
                onClick = {
                    editingActionId = null
                    isAddingAction = true
                },
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .semantics { contentDescription = "Add action item" }
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.action_item_add))
            }
        }
        if (isAddingAction) {
            InlineActionItemEditor(
                editorKey = "add_${note.id}",
                initialText = "",
                initialDueAtEpochMs = null,
                confirmLabel = stringResource(R.string.action_item_add_confirm),
                onSave = { newText, newDueAtEpochMs ->
                    onAddAction(newText, newDueAtEpochMs)
                    isAddingAction = false
                },
                onCancel = { isAddingAction = false },
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        actions.forEach { item ->
            if (editingActionId == item.id) {
                InlineActionItemEditor(
                    editorKey = "edit_${note.id}_${item.id}",
                    initialText = item.text,
                    initialDueAtEpochMs = item.dueAtEpochMs,
                    confirmLabel = stringResource(R.string.action_save_note),
                    onSave = { updatedText, updatedDueAtEpochMs ->
                        onUpdateAction(item.id, updatedText, updatedDueAtEpochMs)
                        editingActionId = null
                    },
                    onCancel = { editingActionId = null },
                    onDelete = {
                        onDeleteAction(item.id)
                        editingActionId = null
                    },
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .clickable(enabled = onToggleActionDone != null) {
                            onToggleActionDone?.invoke(item.id, !item.done)
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = item.done,
                        onCheckedChange = { done ->
                            onToggleActionDone?.invoke(item.id, done)
                        },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics {
                                contentDescription = "Toggle action item: ${item.text}"
                            }
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp)
                    ) {
                        Text(
                            text = item.text,
                            style = MaterialTheme.typography.bodyMedium,
                            textDecoration = if (item.done) TextDecoration.LineThrough else null,
                            color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                        )
                        ActionDueDateBadge(
                            dueAtEpochMs = item.dueAtEpochMs,
                            onClick = {
                                isAddingAction = false
                                editingActionId = item.id
                            }
                        )
                    }
                    IconButton(
                        onClick = {
                            isAddingAction = false
                            editingActionId = item.id
                        },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics { contentDescription = "Edit action item: ${item.text}" }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null
                        )
                    }
                    IconButton(
                        onClick = { onDeleteAction(item.id) },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics { contentDescription = "Delete action item: ${item.text}" }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null
                        )
                    }
                }
            }
        }
        if (hasAudioFile || note.audioPath.isNotBlank()) {
            AudioPlaybackControl(
                audioPlaybackState = audioPlaybackState,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
        if (isDeveloperModeEnabled && note.stageStatus.isNotBlank()) {
            val stages = remember(note.stageStatus) {
                NoteCardFormatting.formatPipelineStages(note.stageStatus)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Processing status",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                stages.forEach { (stage, status) ->
                    val isSuccess = status == "OK"
                    val isSkipped = status == "SKIPPED" || status == "DEFAULT"
                    val (containerColor, labelColor) = when {
                        isSuccess -> Pair(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        isSkipped -> Pair(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.outline
                        )
                        else -> Pair(
                            MaterialTheme.colorScheme.errorContainer,
                            MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    SuggestionChip(
                        onClick = {},
                        label = { Text("$stage: $status", style = MaterialTheme.typography.labelSmall) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = containerColor,
                            labelColor = labelColor
                        ),
                        border = null,
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    )
                }
            }
        }
    }
}
}

@Composable
fun AudioPlaybackControl(
    audioPlaybackState: AudioPlaybackState,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.audio_playback_title),
                style = MaterialTheme.typography.titleSmall
            )

            if (!audioPlaybackState.isFileAvailable || audioPlaybackState.errorMessage != null) {
                Text(
                    text = audioPlaybackState.errorMessage ?: stringResource(R.string.audio_missing_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isPlaying = audioPlaybackState.status == PlaybackStatus.PLAYING
                    val playPauseDesc = if (isPlaying) {
                        stringResource(R.string.cd_pause_audio)
                    } else {
                        stringResource(R.string.cd_play_audio)
                    }

                    FilledTonalButton(
                        onClick = { audioPlaybackState.togglePlayPause() },
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .semantics { contentDescription = playPauseDesc }
                    ) {
                        Text(if (isPlaying) "Pause" else "Play")
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        LinearProgressIndicator(
                            progress = { audioPlaybackState.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = audioPlaybackState.elapsedFormatted,
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                text = audioPlaybackState.durationFormatted,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}
