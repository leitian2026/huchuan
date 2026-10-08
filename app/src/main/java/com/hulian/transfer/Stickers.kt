package com.hulian.transfer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.LruCache
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")

/** 按文件名判断是不是图片（聊天里直接显示缩略图 / 动图） */
fun isImageName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in IMAGE_EXT

/** 这条消息是不是该直接显示成图片：文件消息、扩展名是图片、已经有文件可读 */
fun isInlineImage(m: Msg): Boolean {
    if (m.kind != Kind.FILE) return false
    if (!isImageName(m.file.ifEmpty { m.name })) return false
    if (m.uri.isEmpty()) return false
    // 接收中还没有完整文件，先用文件卡片显示进度
    return !(m.state == MsgState.RECEIVING)
}

/** 解码结果：静态图是 Bitmap，动图是 Drawable（AnimatedImageDrawable） */
class Decoded(val drawable: Drawable?, val bitmap: Bitmap?, val w: Int, val h: Int) {
    val animated: Boolean get() = drawable is AnimatedImageDrawable
}

object Thumb {
    /** 聊天里最长边最多解码到这么多像素：动图每一帧都在内存里，不能解成原尺寸 */
    private const val MAX_PX = 560

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun thumbFile(ctx: Context, id: String) = File(File(ctx.filesDir, "thumbs"), "$id.jpg")

    private fun scaleTo(maxPx: Int): ImageDecoder.OnHeaderDecodedListener = ImageDecoder.OnHeaderDecodedListener { d, info, _ ->
        val w = info.size.width
        val h = info.size.height
        val big = maxOf(w, h)
        if (big > maxPx) {
            val s = maxPx.toFloat() / big
            d.setTargetSize(maxOf(1, (w * s).toInt()), maxOf(1, (h * s).toInt()))
        }
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }

    /** 静态位图（表情面板的格子、缩略图缓存都用这个）。动图只取第一帧 */
    fun bitmap(ctx: Context, uri: Uri, maxPx: Int = MAX_PX): Bitmap? {
        val key = "$uri@$maxPx"
        cache.get(key)?.let { return it }
        return try {
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri), scaleTo(maxPx))
            cache.put(key, bmp)
            bmp
        } catch (e: Throwable) { null }
    }

    /** 聊天里显示用：动图返回可播放的 Drawable。读不到原文件时退回磁盘上缓存的第一帧 */
    fun load(ctx: Context, msgId: String, uri: Uri): Decoded? {
        try {
            val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ctx.contentResolver, uri), scaleTo(MAX_PX))
            val w = d.intrinsicWidth
            val h = d.intrinsicHeight
            if (w <= 0 || h <= 0) return null
            saveThumb(ctx, msgId, d, w, h)
            return if (d is AnimatedImageDrawable) Decoded(d, null, w, h)
            else Decoded(null, bitmapOf(d, w, h), w, h)
        } catch (e: Throwable) {
            // 原文件没了（相册临时授权过期、被删除）：用之前存下的缩略图
            val f = thumbFile(ctx, msgId)
            if (f.exists()) {
                try {
                    val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(f), scaleTo(MAX_PX))
                    return Decoded(null, bmp, bmp.width, bmp.height)
                } catch (e2: Throwable) { Hub.log("读取缩略图", e2) }
            }
            return null
        }
    }

    private fun bitmapOf(d: Drawable, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, w, h)
        d.draw(c)
        return bmp
    }

    /** 第一次成功显示时存一张小图：以后原文件读不到了聊天里仍然有图 */
    private fun saveThumb(ctx: Context, id: String, d: Drawable, w: Int, h: Int) {
        try {
            val f = thumbFile(ctx, id)
            if (f.exists()) return
            f.parentFile?.mkdirs()
            // 动图还没开始播放时画出来的是第一帧；背景填白，JPEG 不带透明
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(android.graphics.Color.WHITE)
            d.setBounds(0, 0, w, h)
            d.draw(c)
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        } catch (e: Throwable) { Hub.log("保存缩略图", e) }
    }
}

/**
 * 聊天里的图片 / 动图。小图（常见表情包）显示得小一些，照片大一些。
 * 动图用系统 AnimatedImageDrawable 播放，不需要额外的库。
 * 读不到图时调用 onFail，由上层退回文件卡片。
 */
@Composable
fun ChatImage(m: Msg, modifier: Modifier = Modifier, onFail: () -> Unit) {
    val ctx = LocalContext.current
    var dec by remember(m.id, m.uri) { mutableStateOf<Decoded?>(null) }
    var failed by remember(m.id, m.uri) { mutableStateOf(false) }
    LaunchedEffect(m.id, m.uri) {
        val r = withContext(Dispatchers.IO) { Thumb.load(ctx, m.id, Uri.parse(m.uri)) }
        if (r == null) failed = true else dec = r
    }
    LaunchedEffect(failed) { if (failed) onFail() }

    val d = dec
    if (d == null) {
        Box(modifier.size(120.dp).background(Color(0xFFE3E7EE)))
        return
    }
    // 小图当表情包处理：最长边 150dp；否则 240dp
    val sticker = maxOf(d.w, d.h) <= 512
    val limit: Dp = if (sticker) 150.dp else 240.dp
    val density = LocalDensity.current
    val limitPx = with(density) { limit.toPx() }
    val scale = limitPx / maxOf(d.w, d.h)
    val wDp = with(density) { (d.w * scale).toDp() }
    val hDp = with(density) { (d.h * scale).toDp() }

    Box(modifier.size(wDp, hDp), contentAlignment = Alignment.Center) {
        if (d.animated) {
            val drawable = d.drawable as AnimatedImageDrawable
            AndroidView(
                factory = { c ->
                    ImageView(c).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setImageDrawable(drawable)
                        drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                        drawable.start()
                    }
                },
                onRelease = { v -> (v.drawable as? AnimatedImageDrawable)?.stop() },
                modifier = Modifier.size(wDp, hDp)
            )
        } else {
            Image(d.bitmap!!.asImageBitmap(), null, Modifier.size(wDp, hDp), contentScale = ContentScale.Fit)
        }
    }
}

/** 一张格子里的静态预览（表情面板用） */
@Composable
fun StickerThumb(f: File, size: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var bmp by remember(f.path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(f.path) {
        bmp = withContext(Dispatchers.IO) { Thumb.bitmap(ctx, Uri.fromFile(f), 240) }
    }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), null, modifier.size(size), contentScale = ContentScale.Fit)
    else Box(modifier.size(size).background(Color(0xFFE3E7EE)))
}

/** 本机收藏的表情：存在应用私有目录，点一下就发 */
object Stickers {
    private const val MAX_BYTES = 15L * 1024 * 1024
    private val _list = MutableStateFlow<List<File>>(emptyList())
    val list: StateFlow<List<File>> = _list.asStateFlow()

    private fun dir(ctx: Context) = File(ctx.filesDir, "stickers").apply { mkdirs() }

    fun reload(ctx: Context) {
        // 文件名以时间戳开头，倒序 = 最近添加的在前
        _list.value = dir(ctx).listFiles { f -> f.isFile && !f.name.endsWith(".tmp") }?.sortedByDescending { it.name } ?: emptyList()
    }

    enum class Result { ADDED, EXISTS, TOO_BIG, FAILED }

    /** 复制一张图进表情库；内容相同的不重复添加 */
    fun add(ctx: Context, uri: Uri, nameHint: String): Result {
        return try {
            val d = dir(ctx)
            val tmp = File(d, "${now()}.tmp")
            val md = MessageDigest.getInstance("SHA-256")
            var total = 0L
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) { tmp.delete(); return Result.TOO_BIG }
                        md.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            } ?: return Result.FAILED
            val hash = md.digest().joinToString("") { "%02x".format(it) }.take(12)
            if (d.listFiles()?.any { it.name.contains("_$hash.") } == true) { tmp.delete(); return Result.EXISTS }
            val ext = nameHint.substringAfterLast('.', "").lowercase().takeIf { it in IMAGE_EXT }
                ?: when (ctx.contentResolver.getType(uri)) {
                    "image/gif" -> "gif"
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    else -> "jpg"
                }
            // 确认真的是能解码的图，别把任意文件存进来
            if (try { ImageDecoder.decodeBitmap(ImageDecoder.createSource(tmp), scaleTo1()); false } catch (e: Throwable) { true }) {
                tmp.delete(); return Result.FAILED
            }
            val dest = File(d, "${now()}_$hash.$ext")
            if (!tmp.renameTo(dest)) { tmp.delete(); return Result.FAILED }
            reload(ctx)
            Result.ADDED
        } catch (e: Throwable) {
            Hub.log("添加表情", e)
            Result.FAILED
        }
    }

    private fun scaleTo1() = ImageDecoder.OnHeaderDecodedListener { dec, _, _ -> dec.setTargetSize(1, 1) }

    fun remove(ctx: Context, f: File) {
        f.delete()
        reload(ctx)
    }
}
