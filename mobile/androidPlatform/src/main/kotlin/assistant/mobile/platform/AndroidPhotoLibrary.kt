package assistant.mobile.platform

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size

/** Read permission is used exclusively by the recent-photo grid, never by upload or picker. */
object AndroidPhotoLibrary {
    enum class Access { ALL, SELECTED, NONE }

    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun access(context: Context): Access {
        fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        return when {
            granted(permissions().first()) -> Access.ALL
            Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.SELECTED
            else -> Access.NONE
        }
    }

    fun recent(context: Context): List<Uri> {
        if (access(context) == Access.NONE) return emptyList()
        val collection = if (Build.VERSION.SDK_INT >= 29)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val photos = ArrayList<Uri>()
        context.contentResolver.query(collection, arrayOf(MediaStore.Images.Media._ID), null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cursor ->
            while (photos.size < RECENT_LIMIT && cursor.moveToNext())
                photos += ContentUris.withAppendedId(collection, cursor.getLong(0))
        }
        return photos
    }

    fun thumbnail(context: Context, uri: Uri, side: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) return context.contentResolver.loadThumbnail(uri, Size(side, side), null)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > side) sample *= 2
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }

    private const val RECENT_LIMIT = 1000
}
