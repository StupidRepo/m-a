package com.vayunmathur.library.util

import android.net.Uri
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent

/**
 * A modifier that handles external drag and drop of files.
 */
fun Modifier.onFileDrop(
    onFilesDropped: (List<Uri>) -> Unit
): Modifier = this.dragAndDropTarget(
    shouldHandleDragAndDrop = { event ->
        val dragEvent = event.toAndroidDragEvent()
        dragEvent.clipData != null
    },
    target = object : DragAndDropTarget {
        override fun onDrop(event: DragAndDropEvent): Boolean {
            val dragEvent = event.toAndroidDragEvent()
            val clipData = dragEvent.clipData ?: return false
            val uris = mutableListOf<Uri>()
            for (i in 0 until clipData.itemCount) {
                clipData.getItemAt(i).uri?.let { uris.add(it) }
            }
            if (uris.isNotEmpty()) {
                onFilesDropped(uris)
                return true
            }
            return false
        }
    }
)
