package com.omnireader.ui.components

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnireader.util.platformIoDispatcher
import kotlinx.coroutines.withContext

@Composable
fun CoverImage(
    coverSource: String?,
    modifier: Modifier = Modifier,
    placeholderTitle: String? = null
) {
    var image by remember(coverSource) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(coverSource) {
        image = coverSource?.let { source ->
            withContext(platformIoDispatcher) {
                runCatching { loadCoverImage(source) }.getOrNull()
            }
        }
    }

    val showImage = image != null

    Box(modifier = modifier.clip(RoundedCornerShape(8.dp))) {
        AnimatedVisibility(
            visible = !showImage,
            enter = fadeIn(animationSpec = tween(250)),
            exit = fadeOut(animationSpec = tween(200))
        ) {
            CoverPlaceholder(
                title = placeholderTitle,
                modifier = Modifier.fillMaxSize()
            )
        }

        AnimatedVisibility(
            visible = showImage,
            enter = fadeIn(animationSpec = tween(250)),
            exit = fadeOut(animationSpec = tween(200))
        ) {
            image?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
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
