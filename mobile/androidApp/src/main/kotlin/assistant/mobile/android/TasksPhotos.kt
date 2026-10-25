package assistant.mobile.android

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import assistant.mobile.design.ui.ProgressBar
import assistant.mobile.design.ui.ActionIconButton
import assistant.mobile.core.files.ExportFile
import assistant.mobile.core.files.FileExportDestination
import assistant.mobile.core.photos.PrivateConversationImages
import assistant.mobile.core.photos.PrivateImage
import assistant.mobile.core.photos.PrivateImageResult
import assistant.mobile.core.tasks.TaskImage
import assistant.mobile.design.R as DesignR
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Image descriptors never become URLs; this view only asks the authenticated task transport. */
@Composable
internal fun TaskFieldPhotos(taskId: String?, photos: List<TaskImage>, images: PrivateConversationImages,
    editable: Boolean, field: String, onRemove: (String) -> Unit, onOpen: (String) -> Unit) {
    if (photos.isEmpty()) return
    val fieldLabel = stringResource(when (field) {
        "title" -> DesignR.string.tasks_name
        "description" -> DesignR.string.tasks_description
        else -> error("Unsupported task image field: $field")
    })
    val description = stringResource(DesignR.string.tasks_images_label, fieldLabel)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        .semantics { contentDescription = description }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        photos.forEachIndexed { index, photo ->
            Box {
                TaskPrivatePhoto(taskId, photo.id, images, { onOpen(photo.id) }, Modifier.size(104.dp))
                if (editable) ActionIconButton(
                    icon = DesignR.drawable.lucide_x,
                    contentDescription = stringResource(DesignR.string.tasks_images_remove, (index + 1).toString()),
                    onClick = { onRemove(photo.id) },
                    testTag = "task_photo_remove_${photo.id}",
                    modifier = Modifier.align(Alignment.TopEnd).background(MaterialTheme.colorScheme.surface, CircleShape),
                )
            }
        }
    }
}

@Composable
internal fun TaskImageButton(field: String, enabled: Boolean, onClick: () -> Unit) {
    val fieldLabel = stringResource(when (field) {
        "title" -> DesignR.string.tasks_name
        "description" -> DesignR.string.tasks_description
        else -> error("Unsupported task image field: $field")
    })
    ActionIconButton(
        icon = DesignR.drawable.lucide_image_plus,
        contentDescription = "${stringResource(DesignR.string.tasks_images_add)}: $fieldLabel",
        onClick = onClick,
        enabled = enabled,
        testTag = "task_photo_add_$field",
    )
}

@Composable
internal fun TaskPrivatePhoto(taskId: String?, id: String, images: PrivateConversationImages, onOpen: () -> Unit, modifier: Modifier) {
    var photo by remember(images, taskId, id) { mutableStateOf<PrivateImage?>(null) }
    var failed by remember(images, taskId, id) { mutableStateOf(false) }
    var attempt by remember(images, taskId, id) { mutableStateOf(0) }
    LaunchedEffect(images, taskId, id, attempt) {
        failed = false
        if (taskId == null) {
            photo = images.uploadedImage(id)
            failed = photo == null
        } else when (val result = images.taskImage(taskId, id, preview = true)) {
            is PrivateImageResult.Success -> photo = result.image
            is PrivateImageResult.Failure -> failed = true
        }
    }
    val loaded = photo
    if (loaded != null) TaskPhotoPixels(loaded.bytes, modifier.clickable(onClick = onOpen).testTag("task_photo_$id"))
    else if (failed) TextButton(onClick = { attempt++ }, modifier = Modifier.heightIn(min = 48.dp).testTag("task_photo_retry_$id")) {
        Text(stringResource(DesignR.string.tasks_images_unavailable))
    } else ProgressBar(modifier = modifier.testTag("task_photo_loading_$id"))
}

@Composable
internal fun TaskPhotoViewer(taskId: String?, id: String, images: PrivateConversationImages, export: FileExportBridge, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var photo by remember(images, taskId, id) { mutableStateOf<PrivateImage?>(null) }
    var failed by remember(images, taskId, id) { mutableStateOf(false) }
    var attempt by remember(images, taskId, id) { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(images, taskId, id, attempt) {
        failed = false
        if (taskId == null) {
            photo = images.uploadedImage(id)
            failed = photo == null
        } else when (val result = images.taskImage(taskId, id)) {
            is PrivateImageResult.Success -> photo = result.image
            is PrivateImageResult.Failure -> failed = true
        }
    }
    fun save(destination: FileExportDestination) {
        val selected = photo ?: return
        scope.launch {
            busy = true
            try {
                val extension = when (selected.contentType) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
                export.export(ExportFile("photo.$extension", selected.contentType, selected.bytes), destination)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { failed = true
            } finally { busy = false }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp).testTag("task_photo_viewer"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(DesignR.string.tasks_images_viewer), style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("task_photo_close")) {
                        Text(stringResource(DesignR.string.common_close))
                    }
                    TextButton(onClick = { save(FileExportDestination.SAVE) }, enabled = photo != null && !busy,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("task_photo_save")) { Text(stringResource(DesignR.string.common_save)) }
                    TextButton(onClick = { save(FileExportDestination.SHARE) }, enabled = photo != null && !busy,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("task_photo_share")) { Text(stringResource(DesignR.string.native_photos_share)) }
                }
                photo?.let { TaskPhotoPixels(it.bytes, Modifier.weight(1f).fillMaxWidth().testTag("task_photo_full")) }
                if (photo == null && !failed || busy) ProgressBar(modifier = Modifier.fillMaxWidth())
                if (failed) TextButton(onClick = { attempt++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(DesignR.string.common_retry)) }
            }
        }
    }
}

@Composable
private fun TaskPhotoPixels(bytes: ByteArray, modifier: Modifier) {
    var pixels by remember(bytes) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(bytes) {
        pixels = withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600 && sample < (1 shl 28)) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }
    }
    pixels?.let { Image(it, contentDescription = stringResource(DesignR.string.tasks_images_viewer),
        modifier = modifier, contentScale = ContentScale.Fit) }
}
