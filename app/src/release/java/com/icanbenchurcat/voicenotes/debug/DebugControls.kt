// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.debug

import androidx.compose.runtime.Composable
import com.icanbenchurcat.voicenotes.data.AppDatabase

/**
 * Release no-op: release builds contain no seed data and no seed UI entry point.
 */
@Composable
fun DebugSeedToolbar(db: AppDatabase) = Unit
