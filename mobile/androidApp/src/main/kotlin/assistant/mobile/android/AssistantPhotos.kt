package assistant.mobile.android

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import assistant.mobile.design.ui.ActionIconButton
import assistant.mobile.design.ui.ProgressBar
import assistant.mobile.design.R as DesignR
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.files.ExportFile
import assistant.mobile.core.files.FileExportDestination
import assistant.mobile.core.photos.PhotoAttachmentState
import assistant.mobile.core.photos.PhotoAttachmentStatus
import assistant.mobile.core.photos.PrivateConversationImages
import assistant.mobile.core.photos.PrivateImage
import assistant.mobile.core.photos.PrivateImageResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AssistantPhotoAttachment(
    photo: PhotoAttachmentState,
    restoredImageId: String?,
    images: PrivateConversationImages,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (String) -> Unit,
) {
    if (!photo.present && restoredImageId == null) return
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val bytes = photo.photo?.jpeg
        val preparing = photo.status == PhotoAttachmentStatus.Preparing
        val uploading = photo.status == PhotoAttachmentStatus.Uploading
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            if (bytes != null) {
                PhotoPixels(bytes, Modifier.fillMaxSize().testTag("assistant_photo_preview"), ContentScale.Crop)
            } else if (restoredImageId != null) {
                AssistantPrivatePhoto(restoredImageId, images, onClick = { onOpen(restoredImageId) },
                    modifier = Modifier.fillMaxSize().testTag("assistant_photo_preview"))
            }
            if (preparing || uploading) {
                val total = photo.totalBytes
                if (uploading && total != null && total > 0) {
                    CircularProgressIndicator(
                        progress = { (photo.uploadedBytes.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.size(24.dp).testTag("assistant_photo_uploading"), strokeWidth = 2.dp,
                    )
                } else {
                    CircularProgressIndicator(Modifier.size(24.dp).testTag("assistant_photo_uploading"), strokeWidth = 2.dp)
                }
            }
        }
        Column(Modifier.weight(1f)) {
            when (photo.status) {
                PhotoAttachmentStatus.Preparing -> Text(localized(GeneratedStrings.Assistant.photoPreparing),
                    style = MaterialTheme.typography.bodyMedium)
                PhotoAttachmentStatus.Uploading -> Text(localized(GeneratedStrings.Assistant.photoUploading),
                    style = MaterialTheme.typography.bodyMedium)
                PhotoAttachmentStatus.Error -> {
                    Text(localized(if (bytes == null) GeneratedStrings.Assistant.photoUnsupported else GeneratedStrings.Errors.server),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("assistant_photo_error"))
                    if (bytes != null) OutlinedButton(onClick = onRetry, modifier = Modifier.testTag("assistant_photo_retry")) {
                        Text(localized(GeneratedStrings.Common.retry))
                    }
                }
                PhotoAttachmentStatus.Ready, PhotoAttachmentStatus.Empty ->
                    Text(localized(GeneratedStrings.Assistant.photoPreview), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("assistant_photo_ready"))
            }
        }
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
            ActionIconButton(DesignR.drawable.lucide_x, localized(GeneratedStrings.Assistant.removePhoto), onRemove,
                testTag = "assistant_photo_remove")
        }
    }
}

/** The HTTP port constructs the private origin path from the id; no image-loader receives a URL. */
@Composable
internal fun AssistantPrivatePhoto(id: String, images: PrivateConversationImages, onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 240.dp).clip(RoundedCornerShape(12.dp))) {
    var image by remember(images, id) { mutableStateOf<PrivateImage?>(null) }
    var failed by remember(images, id) { mutableStateOf(false) }
    var attempt by remember(images, id) { mutableStateOf(0) }
    LaunchedEffect(images, id, attempt) {
        failed = false
        when (val result = images.image(id, preview = true)) {
            is PrivateImageResult.Success -> image = result.image
            is PrivateImageResult.Failure -> failed = true
        }
    }
    val loaded = image
    if (loaded != null) PhotoPixels(loaded.bytes,
        modifier
            .clickable(onClickLabel = localized(GeneratedStrings.Assistant.photoPreview), onClick = onClick)
            .testTag("assistant_image_$id"))
    else if (failed) TextButton(onClick = { attempt++ }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(localized(GeneratedStrings.Common.retry))
    } else ProgressBar(modifier = Modifier.fillMaxWidth().testTag("assistant_image_loading_$id"))
}

@Composable
private fun PhotoPixels(bytes: ByteArray, modifier: Modifier, contentScale: ContentScale = ContentScale.Fit) {
    var pixels by remember(bytes) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(bytes) {
        pixels = withContext(Dispatchers.IO) {
            // Private responses are byte-bounded as well. Sampling also protects this decode if a
            // remote image carries unexpected dimensions despite its small compressed body.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600 && sample < (1 shl 28)) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }
    }
    pixels?.let { Image(it, contentDescription = localized(GeneratedStrings.Assistant.photoPreview),
        modifier = modifier, contentScale = contentScale) }
}

@Composable
internal fun AssistantPhotoViewer(id: String, images: PrivateConversationImages, export: FileExportBridge, onDismiss: () -> Unit) {
    var image by remember(images, id) { mutableStateOf<PrivateImage?>(null) }
    var failed by remember(images, id) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(images, id, attempt) {
        failed = false
        when (val result = images.image(id)) {
            is PrivateImageResult.Success -> image = result.image
            is PrivateImageResult.Failure -> failed = true
        }
    }
    fun save(destination: FileExportDestination) {
        val selected = image ?: return
        scope.launch {
            busy = true
            try {
                val extension = if (selected.contentType == "image/png") "png" else "jpg"
                export.export(ExportFile("photo.$extension", selected.contentType, selected.bytes), destination)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { failed = true
            } finally { busy = false }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(localized(GeneratedStrings.Common.close))
                    }
                    TextButton(onClick = { save(FileExportDestination.SAVE) }, enabled = image != null && !busy,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("assistant_photo_save")) {
                        Text(localized(GeneratedStrings.Common.save))
                    }
                    TextButton(onClick = { save(FileExportDestination.SHARE) }, enabled = image != null && !busy,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("assistant_photo_share")) {
                        Text(localized(GeneratedStrings.NativePhotos.share))
                    }
                }
                image?.let { PhotoPixels(it.bytes, Modifier.weight(1f).fillMaxWidth().testTag("assistant_photo_full")) }
                if (image == null && !failed || busy) ProgressBar(modifier = Modifier.fillMaxWidth())
                if (failed) TextButton(onClick = { attempt++ }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(localized(GeneratedStrings.Common.retry))
                }
            }
        }
    }
}
