// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.icanbenchurcat.voicenotes.ai.CapabilityProbe
import com.icanbenchurcat.voicenotes.ai.CapabilityStatus
import com.icanbenchurcat.voicenotes.ai.Tier
import com.icanbenchurcat.voicenotes.data.ActionItem
import com.icanbenchurcat.voicenotes.data.ActionItemWithNote
import com.icanbenchurcat.voicenotes.data.AppDatabase
import com.icanbenchurcat.voicenotes.data.Bucket
import com.icanbenchurcat.voicenotes.data.BucketManager
import com.icanbenchurcat.voicenotes.data.FtsQuerySanitizer
import com.icanbenchurcat.voicenotes.data.Note
import com.icanbenchurcat.voicenotes.data.NoteDao
import com.icanbenchurcat.voicenotes.data.NoteDeletionCoordinator
import com.icanbenchurcat.voicenotes.data.NoteStatus
import com.icanbenchurcat.voicenotes.data.NoteTag
import com.icanbenchurcat.voicenotes.data.StorageRetentionManager
import com.icanbenchurcat.voicenotes.data.StorageStats
import com.icanbenchurcat.voicenotes.data.Tag
import com.icanbenchurcat.voicenotes.debug.DebugSeedToolbar
import com.icanbenchurcat.voicenotes.ui.BucketManagementDialog
import com.icanbenchurcat.voicenotes.ui.CapabilitySettingsUiState
import com.icanbenchurcat.voicenotes.ui.NoteDetailScreen
import com.icanbenchurcat.voicenotes.ui.NotesListScreen
import com.icanbenchurcat.voicenotes.ui.SettingsScreen
import com.icanbenchurcat.voicenotes.ui.TasksScreen
import com.icanbenchurcat.voicenotes.ui.theme.VoiceNoteSorterTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data object NotesListKey : NavKey

@Serializable
data object TasksKey : NavKey

@Serializable
data object SettingsKey : NavKey

@Serializable
data class NoteDetailKey(val noteId: Long) : NavKey

@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModel(private val dao: NoteDao) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val notes: Flow<List<Note>> = _searchQuery
        .flatMapLatest { query ->
            val sanitized = FtsQuerySanitizer.sanitize(query)
            if (sanitized != null) {
                dao.search(sanitized)
            } else if (query.isNotBlank()) {
                flowOf(emptyList())
            } else {
                dao.observeAll()
            }
        }

    val tasks: Flow<List<ActionItemWithNote>> = dao.observeAllActions()
    val allTags: Flow<List<Tag>> = dao.observeAllTags()
    val allNoteTags: Flow<List<NoteTag>> = dao.observeAllNoteTags()

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.get(this)
        val dao = db.noteDao()
        val deletionCoordinator = NoteDeletionCoordinator.fromContext(this)
        val capabilityProbe = CapabilityProbe.get(this)
        val storageManager = StorageRetentionManager.fromContext(this)
        enableEdgeToEdge()
        // TODO(capture): EXTRA_START_RECORDING from the QS tile / widget
        // deep-links straight into the recorder once capture lands.
        setContent {
            VoiceNoteSorterTheme {
                val backStack = rememberNavBackStack(NotesListKey)
                val vm = remember { NotesViewModel(dao) }
                val notes by vm.notes.collectAsState(initial = emptyList())
                val buckets by dao.observeAllBuckets().collectAsState(initial = emptyList())
                val bucketManager = remember { BucketManager(dao) }
                var showBucketManagementDialog by remember { mutableStateOf(false) }
                val tasks by vm.tasks.collectAsState(initial = emptyList())
                val allTags by vm.allTags.collectAsState(initial = emptyList())
                val allNoteTags by vm.allNoteTags.collectAsState(initial = emptyList())
                var deviceTier by remember { mutableStateOf<Tier?>(null) }
                var transcriberStatus by remember { mutableStateOf(CapabilityStatus.UNAVAILABLE) }
                var summarizerStatus by remember { mutableStateOf(CapabilityStatus.UNAVAILABLE) }
                var actionExtractorStatus by remember { mutableStateOf(CapabilityStatus.UNAVAILABLE) }
                var isProbingCapabilities by remember { mutableStateOf(false) }
                var storageStats by remember { mutableStateOf(StorageStats(0L, 0L, 0)) }
                val isAutoDeleteOldAudioEnabled by storageManager.isAutoDeleteOldAudioEnabled.collectAsState(initial = false)
                val scope = rememberCoroutineScope()

                fun refreshStorageStats() {
                    scope.launch {
                        storageStats = storageManager.getStorageStats()
                    }
                }

                fun probeAllCapabilities() {
                    scope.launch {
                        isProbingCapabilities = true
                        transcriberStatus = capabilityProbe.transcriberStatus()
                        summarizerStatus = capabilityProbe.summarizerStatus()
                        actionExtractorStatus = capabilityProbe.actionExtractorStatus()
                        deviceTier = capabilityProbe.resolveDeviceTier()
                        isProbingCapabilities = false
                    }
                }

                LaunchedEffect(Unit) {
                    probeAllCapabilities()
                    refreshStorageStats()
                    // Run retention purge opportunistically on app launch if enabled
                    storageManager.runRetentionPolicyIfEnabled()
                }

                if (showBucketManagementDialog) {
                    BucketManagementDialog(
                        buckets = buckets,
                        onDismissRequest = { showBucketManagementDialog = false },
                        onRenameBucket = { kind, newName ->
                            scope.launch {
                                bucketManager.renameBucket(kind, newName)
                            }
                        },
                        onAddBucket = { kind, name ->
                            scope.launch {
                                try {
                                    bucketManager.addCustomBucket(kind, name)
                                } catch (e: Exception) {
                                    // Logged / prevented by validation
                                }
                            }
                        }
                    )
                }

                Scaffold(
                    floatingActionButton = {
                        val currentDestination = backStack.lastOrNull()
                        val isRootTab = currentDestination is NotesListKey || currentDestination is TasksKey
                        if (isRootTab) {
                            val recordDesc = stringResource(R.string.cd_record_fab)
                            FloatingActionButton(
                                onClick = { /* TODO(capture): recorder */ },
                                modifier = Modifier.semantics { contentDescription = recordDesc }
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_tile_record),
                                    contentDescription = null
                                )
                            }
                        }
                    }
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                    ) {
                        DebugSeedToolbar(
                            db = db,
                            onReset = {
                                while (backStack.size > 1) {
                                    backStack.removeLastOrNull()
                                }
                            }
                        )

                        // Top tab navigation for root destinations
                        val currentDestination = backStack.lastOrNull()
                        val isRootTab = currentDestination is NotesListKey || currentDestination is TasksKey
                        if (isRootTab) {
                            val selectedTabIndex = if (currentDestination is TasksKey) 1 else 0
                            PrimaryTabRow(selectedTabIndex = selectedTabIndex) {
                                Tab(
                                    selected = selectedTabIndex == 0,
                                    onClick = {
                                        if (currentDestination !is NotesListKey) {
                                            while (backStack.isNotEmpty()) {
                                                backStack.removeLastOrNull()
                                            }
                                            backStack.add(NotesListKey)
                                        }
                                    },
                                    text = { Text(stringResource(R.string.tab_notes)) }
                                )
                                Tab(
                                    selected = selectedTabIndex == 1,
                                    onClick = {
                                        if (currentDestination !is TasksKey) {
                                            while (backStack.isNotEmpty()) {
                                                backStack.removeLastOrNull()
                                            }
                                            backStack.add(TasksKey)
                                        }
                                    },
                                    text = {
                                        val pendingCount = tasks.count { !it.done }
                                        val label = if (pendingCount > 0) {
                                            "${stringResource(R.string.tab_tasks)} ($pendingCount)"
                                        } else {
                                            stringResource(R.string.tab_tasks)
                                        }
                                        Text(label)
                                    }
                                )
                            }
                        }

                        Box(modifier = Modifier.fillMaxSize()) {
                            NavDisplay(
                                backStack = backStack,
                                onBack = { backStack.removeLastOrNull() },
                                entryProvider = entryProvider {
                                    entry<NotesListKey> {
                                        val searchQuery by vm.searchQuery.collectAsState()
                                        NotesListScreen(
                                            notes = notes,
                                            searchQuery = searchQuery,
                                            onSearchQueryChange = vm::onSearchQueryChange,
                                            deviceTier = deviceTier,
                                            buckets = buckets,
                                            tasks = tasks,
                                            tags = allTags.map { it.name },
                                            noteTags = allNoteTags,
                                            onManageBuckets = { showBucketManagementDialog = true },
                                            onOpenSettings = { backStack.add(SettingsKey) }
                                        ) { note ->
                                            backStack.add(NoteDetailKey(note.id))
                                        }
                                    }
                                    entry<SettingsKey> {
                                        SettingsScreen(
                                            uiState = CapabilitySettingsUiState(
                                                tier = deviceTier,
                                                transcriberStatus = transcriberStatus,
                                                summarizerStatus = summarizerStatus,
                                                actionExtractorStatus = actionExtractorStatus,
                                                isLoading = isProbingCapabilities,
                                                storageStats = storageStats,
                                                isAutoDeleteOldAudioEnabled = isAutoDeleteOldAudioEnabled
                                            ),
                                            onBack = { backStack.removeLastOrNull() },
                                            onRefreshProbe = {
                                                scope.launch {
                                                    capabilityProbe.invalidate()
                                                    probeAllCapabilities()
                                                    refreshStorageStats()
                                                }
                                            },
                                            onToggleAutoDeleteOldAudio = { enabled ->
                                                scope.launch {
                                                    storageManager.setAutoDeleteOldAudioEnabled(enabled)
                                                    if (enabled) {
                                                        storageManager.runRetentionPolicyIfEnabled()
                                                        refreshStorageStats()
                                                    }
                                                }
                                            }
                                        )
                                    }
                                    entry<TasksKey> {
                                        TasksScreen(
                                            tasks = tasks,
                                            onToggleDone = { id, done ->
                                                scope.launch {
                                                    dao.updateActionDone(id, done)
                                                }
                                            },
                                            onOpenNote = { noteId ->
                                                backStack.add(NoteDetailKey(noteId))
                                            }
                                        )
                                    }
                                    entry<NoteDetailKey> { key ->
                                        val note = notes.firstOrNull { it.id == key.noteId }
                                        if (note != null) {
                                            val noteActions by dao.observeActionsFor(note.id).collectAsState(initial = emptyList())
                                            val noteTagsList by dao.observeTagsFor(note.id).collectAsState(initial = emptyList())
                                            NoteDetailScreen(
                                                note = note,
                                                actions = noteActions,
                                                tags = noteTagsList,
                                                transcriberStatus = transcriberStatus,
                                                buckets = buckets,
                                                onBack = {
                                                    if (backStack.lastOrNull() == key) {
                                                        backStack.removeLastOrNull()
                                                    }
                                                },
                                                onConfirmOrChangeBucket = { bucketKind ->
                                                    scope.launch {
                                                        dao.confirmOrChangeBucket(
                                                            noteId = note.id,
                                                            bucketKind = bucketKind,
                                                            status = NoteStatus.SORTED.name
                                                        )
                                                    }
                                                },
                                                onToggleActionDone = { actionId, done ->
                                                    scope.launch {
                                                        dao.updateActionDone(actionId, done)
                                                    }
                                                },
                                                onDeleteNote = {
                                                    scope.launch {
                                                        deletionCoordinator.deleteNote(note.id)
                                                        if (backStack.lastOrNull() == key) {
                                                            backStack.removeLastOrNull()
                                                        }
                                                    }
                                                },
                                                onManageBuckets = { showBucketManagementDialog = true }
                                            )
                                        } else {
                                            LaunchedEffect(key.noteId) {
                                                if (backStack.lastOrNull() == key) {
                                                    backStack.removeLastOrNull()
                                                }
                                            }
                                            Text("Note not found")
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_START_RECORDING = "extra_start_recording"
    }
}
