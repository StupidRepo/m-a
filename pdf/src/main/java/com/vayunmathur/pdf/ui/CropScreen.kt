package com.vayunmathur.pdf.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.pdf.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(
    uri: Uri,
    initialCrop: Rect?,
    onCropDone: (Rect) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var crop by remember { mutableStateOf(initialCrop ?: Rect(0f, 0f, 1f, 1f)) }
    var originalSize by remember { mutableStateOf<IntSize?>(null) }

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { 
                BitmapFactory.decodeStream(it, null, options)
            }
            originalSize = IntSize(options.outWidth, options.outHeight)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.crop_image)) },
                navigationIcon = {
                    IconNavigation(navBack = onBack)
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onCropDone(crop) },
                text = { Text(stringResource(R.string.continue_label)) },
                icon = { IconSave() }
            )
        },
        floatingActionButtonPosition = FabPosition.Center
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .padding(top = 32.dp, bottom = 64.dp, start = 48.dp, end = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            val maxWidth = constraints.maxWidth.toFloat()
            val maxHeight = constraints.maxHeight.toFloat()
            
            originalSize?.let { size ->
                val photoRatio = size.width.toFloat() / size.height.toFloat()
                val containerRatio = maxWidth / maxHeight
                val (viewportWidth, viewportHeight) = if (photoRatio > containerRatio) {
                    maxWidth to (maxWidth / photoRatio)
                } else {
                    (maxHeight * photoRatio) to maxHeight
                }

                Box(Modifier.size(with(LocalDensity.current) { viewportWidth.toDp() }, with(LocalDensity.current) { viewportHeight.toDp() })) {
                    AsyncImage(
                        model = uri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds
                    )
                    CropOverlay(cropRect = crop, onCropRectChange = { crop = it })
                }
            } ?: AsyncImage(
                model = uri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
fun CropOverlay(cropRect: Rect, onCropRectChange: (Rect) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        Canvas(modifier = Modifier.fillMaxSize()) {
            val rect = Rect(cropRect.left * width, cropRect.top * height, cropRect.right * width, cropRect.bottom * height)
            val path = Path().apply {
                addRect(Rect(0f, 0f, width, height))
                addRect(rect)
                fillType = PathFillType.EvenOdd
            }
            drawPath(path, Color.Black.copy(alpha = 0.5f))
            drawRect(color = Color.White, topLeft = Offset(rect.left, rect.top), size = androidx.compose.ui.geometry.Size(rect.width, rect.height), style = Stroke(width = 2.dp.toPx()))
        }
        
        // Draggable area for moving the whole crop
        Box(modifier = Modifier
            .offset { IntOffset((cropRect.left * width).roundToInt(), (cropRect.top * height).roundToInt()) }
            .size(width = with(LocalDensity.current) { (cropRect.width * width).toDp() }, height = with(LocalDensity.current) { (cropRect.height * height).toDp() })
            .pointerInput(cropRect) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val dx = dragAmount.x / width
                    val dy = dragAmount.y / height
                    val newLeft = (cropRect.left + dx).coerceIn(0f, 1f - cropRect.width)
                    val newTop = (cropRect.top + dy).coerceIn(0f, 1f - cropRect.height)
                    onCropRectChange(Rect(left = newLeft, top = newTop, right = newLeft + cropRect.width, bottom = newTop + cropRect.height))
                }
            }
        )

        // Corner handles
        CropHandle(offset = Offset(cropRect.left * width, cropRect.top * height)) { delta ->
            val newLeft = (cropRect.left + delta.x / width).coerceIn(0f, cropRect.right - 0.05f)
            val newTop = (cropRect.top + delta.y / height).coerceIn(0f, cropRect.bottom - 0.05f)
            onCropRectChange(cropRect.copy(left = newLeft, top = newTop))
        }
        CropHandle(offset = Offset(cropRect.right * width, cropRect.top * height)) { delta ->
            val newRight = (cropRect.right + delta.x / width).coerceIn(cropRect.left + 0.05f, 1f)
            val newTop = (cropRect.top + delta.y / height).coerceIn(0f, cropRect.bottom - 0.05f)
            onCropRectChange(cropRect.copy(right = newRight, top = newTop))
        }
        CropHandle(offset = Offset(cropRect.left * width, cropRect.bottom * height)) { delta ->
            val newLeft = (cropRect.left + delta.x / width).coerceIn(0f, cropRect.right - 0.05f)
            val newBottom = (cropRect.bottom + delta.y / height).coerceIn(cropRect.top + 0.05f, 1f)
            onCropRectChange(cropRect.copy(left = newLeft, bottom = newBottom))
        }
        CropHandle(offset = Offset(cropRect.right * width, cropRect.bottom * height)) { delta ->
            val newRight = (cropRect.right + delta.x / width).coerceIn(cropRect.left + 0.05f, 1f)
            val newBottom = (cropRect.bottom + delta.y / height).coerceIn(cropRect.top + 0.05f, 1f)
            onCropRectChange(cropRect.copy(right = newRight, bottom = newBottom))
        }
    }
}

@Composable
fun CropHandle(offset: Offset, onDrag: (Offset) -> Unit) {
    val density = LocalDensity.current
    val handleSize = 24.dp
    val handleRadiusPx = with(density) { (handleSize / 2).toPx() }
    val currentOnDrag by rememberUpdatedState(onDrag)
    Box(modifier = Modifier
        .offset { IntOffset((offset.x - handleRadiusPx).roundToInt(), (offset.y - handleRadiusPx).roundToInt()) }
        .size(handleSize)
        .pointerInput(Unit) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                currentOnDrag(dragAmount)
            }
        }
        .background(Color.White, androidx.compose.foundation.shape.CircleShape)
    )
}
