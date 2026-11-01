// SPDX-License-Identifier: Apache-2.0

package com.icanbenchurcat.voicenotes.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.compose.ui.unit.dp

/**
 * Glance capture widget for home and lock screens. Glance plus
 * RemoteCompose are the single widget model now: this same code targets
 * home screen, keyguard, and (later) Wear surfaces. The real button
 * wires to the capture action; the scaffold renders the surface.
 */
class RecordWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                Row(
                    modifier = GlanceModifier.fillMaxSize().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "●  Tap to record a note")
                }
            }
        }
    }
}

class RecordWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RecordWidget()
}
