// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
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
import com.icanbenchurcat.voicenotes.data.Note
import com.icanbenchurcat.voicenotes.data.NoteDeletionCoordinator
import com.icanbenchurcat.voicenotes.data.NoteStatus
import com.icanbenchurcat.voicenotes.debug.DebugSeedToolbar
import com.icanbenchurcat.voicenotes.ui.BucketManagementDialog
import androidx.lifecycle.viewModelScope
import com.icanbenchurcat.voicenotes.data.FtsQuerySanitizer
import com.icanbenchurcat.voicenotes.data.NoteDao
import com.icanbenchurcat.voicenotes.ui.NoteDetailScreen
import com.icanbenchurcat.voicenotes.ui.NotesListScreen
import com.icanbenchurcat.voicenotes.ui.TasksScreen
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
        // TODO(capture): EXTRA_START_RECORDING from the QS tile / widget
        // deep-links straight into the recorder once capture lands.
        setContent {
            MaterialTheme {
                val backStack = rememberNavBackStack(NotesListKey)
                val vm = remember { NotesViewModel(dao) }
                val notes by vm.notes.collectAsState(initial = emptyList())
                val buckets by dao.observeAllBuckets().collectAsState(initial = emptyList())
                val bucketManager = remember { BucketManager(dao) }
                var showBucketManagementDialog by remember { mutableStateOf(false) }
                val tasks by vm.tasks.collectAsState(initial = emptyList())
                var deviceTier by remember { mutableStateOf<Tier?>(null) }
                var transcriberStatus by remember { mutableStateOf(CapabilityStatus.UNAVAILABLE) }
                val scope = rememberCoroutineScope()

                LaunchedEffect(Unit) {
                    transcriberStatus = capabilityProbe.transcriberStatus()
                    deviceTier = capabilityProbe.resolveDeviceTier()
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
                        val recordDesc = stringResource(R.string.cd_record_fab)
                        FloatingActionButton(
                            onClick = { /* TODO(capture): recorder */ },
                            modifier = Modifier.semantics { contentDescription = recordDesc }
                        ) {
                            Text(
                                "●",
                                modifier = Modifier.semantics { contentDescription = "" }
                            )
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
                                            onManageBuckets = { showBucketManagementDialog = true }
                                        ) { note ->
                                            backStack.add(NoteDetailKey(note.id))
                                        }
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
                                            var actions by remember(note.id) {
                                                mutableStateOf<List<ActionItem>>(emptyList())
                                            }
                                            var tags by remember(note.id) {
                                                mutableStateOf<List<String>>(emptyList())
                                            }
                                            LaunchedEffect(note.id) {
                                                actions = dao.actionsFor(note.id)
                                                tags = dao.tagsFor(note.id)
                                            }
                                            NoteDetailScreen(
                                                note = note,
                                                actions = actions,
                                                tags = tags,
                                                transcriberStatus = transcriberStatus,
                                                buckets = buckets,
                                                onConfirmOrChangeBucket = { bucketKind ->
                                                    scope.launch {
                                                        dao.confirmOrChangeBucket(
                                                            noteId = note.id,
                                                            bucketKind = bucketKind,
                                                            status = NoteStatus.SORTED.name
                                                        )
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
