package com.hulian.transfer

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.OutputStream

/** 接收文件的保存：优先用户选定的目录，否则 下载/互传 */
object Saver {
    class Out(val uri: Uri, val stream: OutputStream)

    fun sanitize(name: String): String {
        val s = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim()
        return if (s.isEmpty() || s == "." || s == "..") "file" else s.take(120)
    }

    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    fun create(ctx: Context, rawName: String): Out {
        val name = sanitize(rawName)
        val mime = mimeOf(name)
        val dir = Store.saveDir
        if (dir != null) {
            val tree = DocumentFile.fromTreeUri(ctx, Uri.parse(dir))
            if (tree != null && tree.canWrite()) {
                val f = tree.createFile(mime, name) ?: throw java.io.IOException("无法在所选文件夹创建文件")
                val os = ctx.contentResolver.openOutputStream(f.uri) ?: throw java.io.IOException("无法写入所选文件夹")
                return Out(f.uri, os)
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/互传")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw java.io.IOException("无法创建文件")
        val os = ctx.contentResolver.openOutputStream(uri) ?: throw java.io.IOException("无法写入文件")
        return Out(uri, os)
    }

    fun delete(ctx: Context, uri: Uri) {
        try {
            DocumentsContract.deleteDocument(ctx.contentResolver, uri)
        } catch (e: Exception) {
            try { ctx.contentResolver.delete(uri, null, null) } catch (e: Exception) { Hub.log("Saver", e) }
        }
    }

    fun queryMeta(ctx: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "file"
        var size = 0L
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0) c.getString(ni)?.let { name = it }
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (e: Exception) { Hub.log("Saver", e) }
        return name to size
    }

    fun open(ctx: Context, uri: Uri, name: String) {
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(name))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Hub.report("无法打开文件", e, "可能没有可以打开 ${name} 的应用，或文件已被移动 / 删除")
        }
    }
}
