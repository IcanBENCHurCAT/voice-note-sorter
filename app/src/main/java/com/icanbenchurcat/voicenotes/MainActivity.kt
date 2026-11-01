// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.icanbenchurcat.voicenotes.data.AppDatabase
import com.icanbenchurcat.voicenotes.data.Note
import com.icanbenchurcat.voicenotes.ui.NoteDetailScreen
import com.icanbenchurcat.voicenotes.ui.NotesListScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
data object NotesListKey : NavKey

@Serializable
data class NoteDetailKey(val noteId: Long) : NavKey

class NotesViewModel(private val notesFlow: Flow<List<Note>>) : ViewModel() {
    val notes: Flow<List<Note>> = notesFlow
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = AppDatabase.get(this).noteDao()
        // TODO(capture): EXTRA_START_RECORDING from the QS tile / widget
        // deep-links straight into the recorder once capture lands.
        setContent {
            MaterialTheme {
                val backStack = rememberNavBackStack(NotesListKey)
                val vm = remember { NotesViewModel(dao.observeAll()) }
                val notes by vm.notes.collectAsState(initial = emptyList())
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
                    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                        NavDisplay(
                            backStack = backStack,
                            onBack = { backStack.removeLastOrNull() },
                            entryProvider = entryProvider {
                                entry<NotesListKey> {
                                    NotesListScreen(notes) { note ->
                                        backStack.add(NoteDetailKey(note.id))
                                    }
                                }
                                entry<NoteDetailKey> { key ->
                                    val note = notes.firstOrNull { it.id == key.noteId }
                                    if (note != null) {
                                        NoteDetailScreen(note)
                                    } else {
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

    companion object {
        const val EXTRA_START_RECORDING = "extra_start_recording"
    }
}
