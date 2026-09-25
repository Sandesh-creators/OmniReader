package com.omnireader.ui.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

private const val MAX_COVER_EDGE = 1024

actual suspend fun loadCoverImage(source: String): ImageBitmap? {
    val file = File(source.removePrefix("file://"))
    if (!file.isFile) return null

    val decoded = ImageIO.read(file) ?: return null
    val width = decoded.width
    val height = decoded.height
    val longest = maxOf(width, height)
    if (longest <= MAX_COVER_EDGE) return decoded.toComposeImageBitmap()

    val ratio = MAX_COVER_EDGE.toFloat() / longest
    val targetWidth = (width * ratio).toInt().coerceAtLeast(1)
    val targetHeight = (height * ratio).toInt().coerceAtLeast(1)

    val scaled = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB)
    val graphics = scaled.createGraphics() as Graphics2D
    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    graphics.drawImage(decoded, 0, 0, targetWidth, targetHeight, null)
    graphics.dispose()

    return scaled.toComposeImageBitmap()
}
