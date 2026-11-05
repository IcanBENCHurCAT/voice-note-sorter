// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.icanbenchurcat.voicenotes.ai.CapabilityProbe
import com.icanbenchurcat.voicenotes.ai.CapabilityStatus
import com.icanbenchurcat.voicenotes.ai.Tier
import com.icanbenchurcat.voicenotes.capture.CaptureSessionState
import com.icanbenchurcat.voicenotes.capture.RecordingService
import com.icanbenchurcat.voicenotes.capture.StartCaptureUseCase
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
import com.icanbenchurcat.voicenotes.data.Tag
import com.icanbenchurcat.voicenotes.debug.DebugSeedToolbar
import com.icanbenchurcat.voicenotes.ui.ActiveRecordingBanner
import com.icanbenchurcat.voicenotes.ui.BucketManagementDialog
import com.icanbenchurcat.voicenotes.ui.CapabilitySettingsUiState
import com.icanbenchurcat.voicenotes.ui.NoteDetailScreen
import com.icanbenchurcat.voicenotes.ui.NoteShareHelper
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
        enableEdgeToEdge()
        val startRecordingFromIntent = intent?.getBooleanExtra(EXTRA_START_RECORDING, false) == true

        setContent {
            VoiceNoteSorterTheme {
                val context = LocalContext.current
                val captureUseCase = remember { StartCaptureUseCase(context) }
                val recordingSessionState by RecordingService.sessionState.collectAsState()

                var showMicRationaleDialog by remember { mutableStateOf(false) }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    val micGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
                    if (micGranted) {
                        captureUseCase.startRecording()
                    } else {
                        showMicRationaleDialog = true
                    }
                }

                fun triggerCapture() {
                    val hasMic = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED

                    val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                    } else true

                    if (hasMic) {
                        captureUseCase.startRecording()
                        if (!hasNotif && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                        }
                    } else {
                        val permsToRequest = mutableListOf(Manifest.permission.RECORD_AUDIO)
                        if (!hasNotif && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        permissionLauncher.launch(permsToRequest.toTypedArray())
                    }
                }

                LaunchedEffect(startRecordingFromIntent) {
                    if (startRecordingFromIntent) {
                        triggerCapture()
                    }
                }

                if (showMicRationaleDialog) {
                    AlertDialog(
                        onDismissRequest = { showMicRationaleDialog = false },
                        title = { Text(stringResource(R.string.app_name)) },
                        text = { Text(stringResource(R.string.permission_microphone_rationale)) },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showMicRationaleDialog = false
                                    permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                                }
                            ) {
                                Text(stringResource(R.string.permission_grant))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showMicRationaleDialog = false }) {
                                Text(stringResource(R.string.dialog_cancel))
                            }
                        }
                    )
                }
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
                val scope = rememberCoroutineScope()

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
                                onClick = { triggerCapture() },
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
                        (recordingSessionState as? CaptureSessionState.Recording)?.let { activeSession ->
                            ActiveRecordingBanner(
                                state = activeSession,
                                onPause = { captureUseCase.pauseRecording() },
                                onResume = { captureUseCase.resumeRecording() },
                                onStop = { captureUseCase.stopRecording() },
                                onCancel = { captureUseCase.cancelRecording() }
                            )
                        }

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
                                                isLoading = isProbingCapabilities
                                            ),
                                            onBack = { backStack.removeLastOrNull() },
                                            onRefreshProbe = {
                                                scope.launch {
                                                    capabilityProbe.invalidate()
                                                    probeAllCapabilities()
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
                                            val context = androidx.compose.ui.platform.LocalContext.current
                                            val hasAudio = remember(note.audioPath) {
                                                note.audioPath.isNotBlank() && java.io.File(note.audioPath).exists()
                                            }
                                            NoteDetailScreen(
                                                note = note,
                                                actions = noteActions,
                                                tags = noteTagsList,
                                                transcriberStatus = transcriberStatus,
                                                hasAudioFile = hasAudio,
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
                                                onSaveEdit = { newTitle, newTranscript ->
                                                    scope.launch {
                                                        dao.updateTitleAndTranscript(
                                                            noteId = note.id,
                                                            title = newTitle,
                                                            transcript = newTranscript
                                                        )
                                                    }
                                                },
                                                onShareText = {
                                                    val chooser = NoteShareHelper.createShareTextIntent(
                                                        note = note,
                                                        chooserTitle = context.getString(R.string.share_sheet_title)
                                                    )
                                                    context.startActivity(chooser)
                                                },
                                                onShareAudio = {
                                                    val chooser = NoteShareHelper.createShareAudioIntent(
                                                        context = context,
                                                        note = note,
                                                        chooserTitle = context.getString(R.string.share_sheet_title)
                                                    )
                                                    if (chooser != null) {
                                                        context.startActivity(chooser)
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
