package assistant.mobile.android

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.photos.ImageAcquisition
import assistant.mobile.platform.AndroidPhotoLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** System launchers live with this visible sheet; a fake acquisition drives the same UI in CI. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoAcquisitionSheet(
    onChosen: (String) -> Unit,
    onDismiss: () -> Unit,
    acquisition: ImageAcquisition? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentChosen by rememberUpdatedState(onChosen)
    val currentDismiss by rememberUpdatedState(onDismiss)
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var launching by remember { mutableStateOf(false) }
    val access = remember(revision) {
        if (BuildConfig.PLAY_STORE) AndroidPhotoLibrary.Access.NONE else AndroidPhotoLibrary.access(context)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        revision++
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val file = cameraPath?.let(::File)
        cameraPath = null
        launching = false
        if (result.resultCode == Activity.RESULT_OK && file != null && file.length() > 0) {
            currentChosen(FileProvider.getUriForFile(context, "${context.packageName}.files", file).toString())
        } else file?.delete()
        currentDismiss()
    }
    val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        launching = false
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let { currentChosen(it.toString()) }
        currentDismiss()
    }
    val photos by produceState<List<Uri>>(emptyList(), access, revision) {
        value = if (access == AndroidPhotoLibrary.Access.NONE) emptyList() else withContext(Dispatchers.IO) {
            runCatching { AndroidPhotoLibrary.recent(context) }.getOrDefault(emptyList())
        }
    }
    // This opening owns a bounded native thumbnail cache. No photo is normalized until selected.
    val thumbnails = remember {
        object : LruCache<Uri, Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: Uri, value: Bitmap): Int = value.allocationByteCount
        }
    }
    DisposableEffect(thumbnails) { onDispose { thumbnails.evictAll() } }

    fun launchFake(camera: Boolean) {
        if (launching) return
        launching = true
        scope.launch {
            try {
                val source = if (camera) acquisition?.camera() else acquisition?.picker()
                source?.let(currentChosen)
            } finally {
                launching = false
                currentDismiss()
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().testTag("photo_sheet")) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(localized(GeneratedStrings.NativePhotos.title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(enabled = !launching, modifier = Modifier.heightIn(min = 48.dp).testTag("photo_all"),
                    onClick = {
                        if (acquisition != null) launchFake(camera = false)
                        else {
                            val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            val intent = ActivityResultContracts.PickVisualMedia().createIntent(context, request)
                            launching = true
                            try { pickerLauncher.launch(intent) }
                            catch (_: ActivityNotFoundException) {
                                try {
                                    pickerLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT)
                                        .addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"))
                                } catch (_: ActivityNotFoundException) { launching = false; currentDismiss() }
                            }
                        }
                    }) { Text(localized(GeneratedStrings.NativePhotos.all)) }
            }
            // A plain system action, without an explanatory banner or permissions for picking.
            if (!BuildConfig.PLAY_STORE && access != AndroidPhotoLibrary.Access.ALL) TextButton(
                modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp).testTag("photo_grid_access"),
                onClick = { permissionLauncher.launch(AndroidPhotoLibrary.permissions()) },
            ) { Text(localized(if (access == AndroidPhotoLibrary.Access.SELECTED)
                GeneratedStrings.NativePhotos.selectMore else GeneratedStrings.NativePhotos.gridAccess)) }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item(key = "camera") {
                    CameraTile(enabled = !launching) {
                        if (acquisition != null) launchFake(camera = true)
                        else {
                            val directory = File(context.cacheDir, "photo-camera").apply { mkdirs() }
                            val staleBefore = System.currentTimeMillis() - 60 * 60 * 1000L
                            directory.listFiles()?.filter { it.lastModified() < staleBefore }?.forEach { it.delete() }
                            val file = File(directory, "${UUID.randomUUID()}.jpg")
                            val output = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                            cameraPath = file.absolutePath
                            launching = true
                            try {
                                cameraLauncher.launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                                    .putExtra(MediaStore.EXTRA_OUTPUT, output)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                    .apply { clipData = ClipData.newRawUri(null, output) })
                            } catch (_: ActivityNotFoundException) {
                                file.delete(); cameraPath = null; launching = false; currentDismiss()
                            }
                        }
                    }
                }
                itemsIndexed(photos, key = { _, photo -> photo.toString() }) { index, photo ->
                    RecentPhotoTile(photo, index, thumbnails, enabled = !launching) {
                        currentChosen(photo.toString())
                        currentDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraTile(enabled: Boolean, onClick: () -> Unit) {
    val foreground = MaterialTheme.colorScheme.onPrimaryContainer
    Column(Modifier.heightIn(min = 48.dp).aspectRatio(1f).background(MaterialTheme.colorScheme.primaryContainer)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).testTag("photo_camera"),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(32.dp)) {
            val stroke = Stroke(width = 2.dp.toPx())
            drawRoundRect(foreground, topLeft = Offset(size.width * .06f, size.height * .27f),
                size = Size(size.width * .88f, size.height * .61f), cornerRadius = CornerRadius(3.dp.toPx()), style = stroke)
            drawCircle(foreground, radius = size.width * .17f,
                center = Offset(size.width * .5f, size.height * .57f), style = stroke)
            drawLine(foreground, Offset(size.width * .32f, size.height * .13f),
                Offset(size.width * .68f, size.height * .13f), strokeWidth = 2.dp.toPx())
        }
        Text(localized(GeneratedStrings.NativePhotos.camera), style = MaterialTheme.typography.bodyMedium,
            color = foreground, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun RecentPhotoTile(photo: Uri, index: Int, thumbnails: LruCache<Uri, Bitmap>, enabled: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val side = with(LocalDensity.current) { 128.dp.roundToPx() }
    val thumbnail by produceState(thumbnails.get(photo), photo, side) {
        if (value == null) value = withContext(Dispatchers.IO) {
            runCatching { AndroidPhotoLibrary.thumbnail(context, photo, side) }.getOrNull()
        }?.also { thumbnails.put(photo, it) }
    }
    androidx.compose.foundation.layout.Box(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).testTag("photo_recent_$index")) {
        thumbnail?.let { Image(it.asImageBitmap(), contentDescription = localized(GeneratedStrings.Assistant.photoPreview),
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}
