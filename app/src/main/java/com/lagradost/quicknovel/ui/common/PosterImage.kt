package com.lagradost.quicknovel.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest

/**
 * A cover with the app's placeholder rules (docs/images.md): while loading, the Browse
 * placeholder (a surfaceVariant block of the poster's shape); when there is no cover or it fails
 * to load, the same block with the book's title on it, so a card never stays empty.
 */
@Composable
fun PosterBox(
    request: ImageRequest,
    model: PosterModel,
    title: String,
    titleSize: TextUnit,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = title,
) {
    var failed by remember(model) { mutableStateOf(model.isEmpty) }
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (!model.isEmpty) {
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = contentScale,
                onState = { state ->
                    when (state) {
                        is AsyncImagePainter.State.Error -> failed = true
                        is AsyncImagePainter.State.Success -> failed = false
                        else -> Unit
                    }
                },
                modifier = Modifier.matchParentSize(),
            )
        }
        if (failed) PosterTitle(title, titleSize)
    }
}

@Composable
private fun PosterTitle(title: String, titleSize: TextUnit) {
    Text(
        text = title,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = titleSize,
        lineHeight = titleSize * 1.15f,
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        maxLines = 6,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(6.dp),
    )
}
