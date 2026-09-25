package com.omnireader.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CoverImage(
    coverUri: Uri?,
    modifier: Modifier = Modifier,
    placeholderTitle: String? = null
) {
    val context = LocalContext.current
    var bitmap by remember(coverUri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(coverUri) {
        bitmap = coverUri?.let { uri ->
            withContext(Dispatchers.IO) {
                runCatching {
                    when (uri.scheme) {
                        "file" -> {
                            decodeBitmapSafely { BitmapFactory.decodeFile(uri.path, it) }
                        }
                        "content" -> context.contentResolver.openInputStream(uri)?.use { input ->
                            decodeBitmapSafely { BitmapFactory.decodeStream(input, null, it) }
                        }
                        else -> null
                    }
                }.getOrNull()
            }
        }
    }

    // Crossfade between placeholder and loaded bitmap
    var showBitmap by remember(bitmap) { mutableStateOf(bitmap != null) }
    if (bitmap != null && !showBitmap) showBitmap = true

    Box(modifier = modifier.clip(RoundedCornerShape(8.dp))) {
        AnimatedVisibility(
            visible = !showBitmap,
            enter = fadeIn(animationSpec = tween(250)),
            exit = fadeOut(animationSpec = tween(200))
        ) {
            CoverPlaceholder(
                title = placeholderTitle,
                modifier = Modifier.fillMaxSize()
            )
        }

        AnimatedVisibility(
            visible = showBitmap && bitmap != null,
            enter = fadeIn(animationSpec = tween(250)),
            exit = fadeOut(animationSpec = tween(200))
        ) {
            bitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

private const val MAX_COVER_EDGE = 1024

private fun decodeBitmapSafely(decode: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
    // First read only the bounds, then compute an inSampleSize that keeps every
    // cover at or under MAX_COVER_EDGE on its longest side. Decoding at full
    // resolution (e.g. a 4000x6000 cover = ~90MB ARGB) blew up the Home grid.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    decode(bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    val longestSide = maxOf(bounds.outWidth, bounds.outHeight)
    while (longestSide / (sampleSize * 2) >= MAX_COVER_EDGE) sampleSize *= 2

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inJustDecodeBounds = false
    }
    return decode(options)
}

@Composable
fun CoverPlaceholder(
    title: String?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        val initials = remember(title) {
            title
                ?.split(Regex("\\s+"))
                ?.take(2)
                ?.mapNotNull { it.firstOrNull()?.toString() }
                ?.joinToString("")
        }

        if (initials.isNullOrEmpty()) {
            Icon(
                Icons.Filled.AutoStories,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
            )
        } else {
            Text(
                text = initials,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                ),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}

@Composable
fun CoverPlaceholderColor(title: String?): Color =
    MaterialTheme.colorScheme.primaryContainer