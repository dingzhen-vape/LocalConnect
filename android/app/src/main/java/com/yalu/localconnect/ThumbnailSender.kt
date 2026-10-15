// ThumbnailSender.kt
package com.yalu.localconnect

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Size
import java.io.ByteArrayOutputStream


fun getRecent(context: Context, limit: Int): List<PhotoItem> {
    val result = mutableListOf<PhotoItem>()

    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.DATE_ADDED,
    )
    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection,
        null,
        null,
        "${MediaStore.Images.Media.DATE_ADDED} DESC",
    )?.use { cursor ->

        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

        while (cursor.moveToNext() && result.size < limit) {
            result += PhotoItem(
                id = cursor.getLong(idCol),
                name = cursor.getString(nameCol) ?: "unknown",
                dateAdded = cursor.getLong(dateCol),
            )
        }
    }
    return result
}

// ---------- 缩略图 ----------

/** 把一张图片解码成 JPEG 字节。图片不存在或已损坏时会抛异常。 */
fun loadThumbnails(context: Context, photos: List<PhotoItem>, size: Int, jpegQuality: Int): List<ThumbnailItem> {
    val list = mutableListOf<ThumbnailItem>()

    for (photo in photos) {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photo.id)

        val bitmap = context.contentResolver.loadThumbnail(uri, Size(size, size), null)

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
        bitmap.recycle()
        list.add(ThumbnailItem(photo.id.toString(), photo.name, photo.dateAdded, out.toByteArray()))
    }

    return list
}


/** 扫描相册最近的图片，把缩略图发给电脑。权限、连接、界面由调用方负责。 */
class ThumbnailSender(
    private val context: Context,
    private val messageHandler: MessageHandler,
    private val callbacks: MessageHandler.Callbacks,
) {
    companion object {
        const val THUMB_SIZE = 200   // 缩略图边长（px）
        const val JPEG_QUALITY = 70  // 压缩质量 0~100
    }

    /**
     * 查询、生成、发送三合一。查询与解码都会阻塞，调用方应放在协程里。
     * 尺寸和质量有默认值，一般不用传。
     */
    suspend fun sendRecent(
        limit: Int,
        size: Int = THUMB_SIZE,
        jpegQuality: Int = JPEG_QUALITY,
    ) {
        callbacks.onLog("准备缩略图（最多 $limit 张）")

        val photos = getRecent(context, limit)
        callbacks.onLog("  查到 ${photos.size} 张图片")

        val thumbnails = loadThumbnails(context, photos, size, jpegQuality)
        callbacks.onLog("  生成 ${thumbnails.size} 张缩略图")

        messageHandler.sendThumb(thumbnails, callbacks)
    }
}

data class PhotoItem(
    val id: Long,
    val name: String,
    val dateAdded: Long,   // 秒
)

data class ThumbnailItem(
    val id: String,
    val name: String,
    val dateAdded: Long,
    val bitmap: ByteArray,
)