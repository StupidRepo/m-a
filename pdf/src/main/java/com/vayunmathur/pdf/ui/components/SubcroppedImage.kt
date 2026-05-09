package com.vayunmathur.pdf.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.vayunmathur.pdf.model.CapturedImage

@Composable
fun SubcroppedImage(
    image: CapturedImage,
    modifier: Modifier = Modifier
) {
    var painterSize by remember(image.uri) { mutableStateOf<Size?>(null) }
    
    val crop = image.cropRect ?: Rect(0f, 0f, 1f, 1f)
    val finalModifier = if (painterSize != null && painterSize!!.width > 0f && painterSize!!.height > 0f) {
        val aspect = (painterSize!!.width * crop.width) / (painterSize!!.height * crop.height)
        modifier.aspectRatio(aspect)
    } else {
        modifier
    }

    Box(finalModifier.clipToBounds()) {
        AsyncImage(
            model = image.uri,
            contentDescription = null,
            onState = { state ->
                if (state is AsyncImagePainter.State.Success) {
                    painterSize = state.painter.intrinsicSize
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val sX = 1f / crop.width
                    val sY = 1f / crop.height
                    scaleX = sX
                    scaleY = sY
                    translationX = -crop.left * size.width * sX
                    translationY = -crop.top * size.height * sY
                    transformOrigin = TransformOrigin(0f, 0f)
                },
            contentScale = ContentScale.FillBounds
        )
    }
}
