// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.icanbenchurcat.voicenotes.R
import com.icanbenchurcat.voicenotes.data.Bucket
import com.icanbenchurcat.voicenotes.data.BucketManager

@Composable
fun BucketManagementDialog(
    buckets: List<Bucket>,
    onDismissRequest: () -> Unit,
    onRenameBucket: (kind: String, newName: String) -> Unit,
    onAddBucket: (kind: String, name: String) -> Unit
) {
    var editingBucket by remember { mutableStateOf<Bucket?>(null) }
    var isAddingBucket by remember { mutableStateOf(false) }

    val customCount = buckets.count { it.kind !in BucketManager.DEFAULT_BUCKET_KINDS }
    val canAddCustom = customCount < BucketManager.MAX_CUSTOM_BUCKETS

    if (editingBucket != null) {
        val target = editingBucket!!
        RenameBucketDialog(
            bucket = target,
            onDismiss = { editingBucket = null },
            onConfirm = { newName ->
                onRenameBucket(target.kind, newName)
                editingBucket = null
            }
        )
    }

    if (isAddingBucket) {
        AddBucketDialog(
            onDismiss = { isAddingBucket = false },
            onConfirm = { kind, name ->
                onAddBucket(kind, name)
                isAddingBucket = false
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(stringResource(R.string.dialog_manage_buckets_title))
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(buckets, key = { it.kind }) { bucket ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = bucket.name,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = bucket.kind,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedButton(
                                onClick = { editingBucket = bucket },
                                modifier = Modifier.semantics {
                                    contentDescription = "Rename ${bucket.name}"
                                }
                            ) {
                                Text(stringResource(R.string.action_rename))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (canAddCustom) {
                    OutlinedButton(
                        onClick = { isAddingBucket = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Add custom bucket" }
                    ) {
                        Text(stringResource(R.string.action_add_bucket))
                    }
                } else {
                    Text(
                        text = stringResource(R.string.max_custom_buckets_reached),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismissRequest,
                modifier = Modifier.semantics { contentDescription = "Close manage buckets dialog" }
            ) {
                Text(stringResource(R.string.dialog_cancel))
            }
        }
    )
}

@Composable
fun RenameBucketDialog(
    bucket: Bucket,
    onDismiss: () -> Unit,
    onConfirm: (newName: String) -> Unit
) {
    var name by remember { mutableStateOf(bucket.name) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_rename_bucket_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        if (it.isNotBlank()) error = null
                    },
                    label = { Text(stringResource(R.string.bucket_name_label)) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Bucket rename field" }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank()) {
                        error = "Bucket name cannot be empty"
                    } else {
                        onConfirm(name.trim())
                    }
                },
                modifier = Modifier.semantics { contentDescription = "Save bucket rename" }
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = "Cancel bucket rename" }
            ) {
                Text(stringResource(R.string.dialog_cancel))
            }
        }
    )
}

@Composable
fun AddBucketDialog(
    onDismiss: () -> Unit,
    onConfirm: (kind: String, name: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_add_bucket_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        if (it.isNotBlank()) error = null
                    },
                    label = { Text(stringResource(R.string.bucket_name_label)) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "New bucket name field" }
                )
                OutlinedTextField(
                    value = kind,
                    onValueChange = { kind = it },
                    label = { Text(stringResource(R.string.bucket_kind_label)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "New bucket identifier field" }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank()) {
                        error = "Bucket name cannot be empty"
                    } else {
                        onConfirm(kind.trim(), name.trim())
                    }
                },
                modifier = Modifier.semantics { contentDescription = "Confirm add bucket" }
            ) {
                Text(stringResource(R.string.action_add_bucket))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = "Cancel add bucket" }
            ) {
                Text(stringResource(R.string.dialog_cancel))
            }
        }
    )
}
