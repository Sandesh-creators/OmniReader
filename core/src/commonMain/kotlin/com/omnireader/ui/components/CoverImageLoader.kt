package com.omnireader.ui.components

import androidx.compose.ui.graphics.ImageBitmap

expect suspend fun loadCoverImage(source: String): ImageBitmap?
