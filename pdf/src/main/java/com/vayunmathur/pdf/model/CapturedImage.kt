package com.vayunmathur.pdf.model

import android.net.Uri
import androidx.compose.ui.geometry.Rect

data class CapturedImage(
    val uri: Uri,
    val cropRect: Rect? = null
)
