package com.michele.luce.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class MediaItem(
    val uri: Uri,
    val name: String,
    val dateTaken: Long,
    val album: String,
    val width: Int,
    val height: Int
)

data class Album(val name: String, val count: Int, val cover: Uri)

object MediaRepository {

    fun queryImages(context: Context): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        try {
            context.contentResolver.query(collection, projection, null, null, sort)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val bucketCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val wCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val hCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val taken = c.getLong(takenCol).takeIf { it > 0 } ?: (c.getLong(addedCol) * 1000L)
                    out.add(
                        MediaItem(
                            uri = ContentUris.withAppendedId(collection, id),
                            name = c.getString(nameCol) ?: "",
                            dateTaken = taken,
                            album = c.getString(bucketCol) ?: "Altro",
                            width = c.getInt(wCol),
                            height = c.getInt(hCol)
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            // permesso non concesso: lista vuota
        }
        return out
    }

    fun albums(items: List<MediaItem>): List<Album> =
        items.groupBy { it.album }
            .map { (name, list) -> Album(name, list.size, list.first().uri) }
            .sortedByDescending { it.count }

    fun displayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val v = c.getString(0)
                    if (!v.isNullOrBlank()) return v
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Immagine"
    }
}
