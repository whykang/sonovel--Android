package com.wang.sonovel.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.wang.sonovel.reader.readEpubMeta
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File

data class LocalBook(
    val file: File,
    val bookName: String,
    val author: String,
    val format: ExportFormat,
    val size: Long,
    val modified: Long,
    val cover: File?,
)

/**
 * 已下载书籍：保存在应用私有目录 files/books，并可复制到公共目录 Download/SoNovel
 */
class LibraryRepository(private val context: Context) {
    val booksDir = File(context.filesDir, "books").apply { mkdirs() }
    private val coversDir = File(booksDir, ".covers").apply { mkdirs() }
    private val _books = MutableStateFlow<List<LocalBook>>(emptyList())
    val books: StateFlow<List<LocalBook>> = _books.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _books.value = booksDir.listFiles { f -> f.isFile && !f.name.startsWith(".") && !f.name.endsWith(".part") }
            .orEmpty()
            .map { f ->
                val base = f.nameWithoutExtension
                val m = NAME_RE.matchEntire(base)
                LocalBook(
                    file = f,
                    bookName = m?.groupValues?.get(1) ?: base,
                    author = m?.groupValues?.get(2).orEmpty(),
                    format = if (f.extension.equals("zip", true)) ExportFormat.HTML else ExportFormat.of(f.extension),
                    size = f.length(),
                    modified = f.lastModified(),
                    cover = File(coversDir, "${f.name}.jpg").takeIf { it.exists() },
                )
            }
            .sortedByDescending { it.modified }
    }

    fun outputFile(book: BookInfo, format: ExportFormat): File {
        val ext = if (format == ExportFormat.HTML) "zip" else format.ext
        return File(booksDir, sanitize("${book.bookName}(${book.author})") + ".$ext")
    }

    fun saveCover(output: File, bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) return
        runCatching { File(coversDir, "${output.name}.jpg").writeBytes(bytes) }
    }

    fun delete(book: LocalBook) {
        book.file.delete()
        book.cover?.delete()
        File(coversDir, "${book.file.name}.nocover2").delete()
        refresh()
    }

    /**
     * 导入本地书籍（EPUB / TXT / PDF）到书架，返回书名。
     * EPUB 会读取内置的书名、作者和封面；其他格式使用文件名。
     */
    fun importBook(uri: Uri): String {
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "book"
        val ext = displayName.substringAfterLast('.', "").lowercase()
        require(ext in IMPORT_EXTENSIONS) { "不支持的格式：$displayName（支持 EPUB、TXT、PDF）" }

        val tmp = File(booksDir, ".import-${System.nanoTime()}.part")
        try {
            (resolver.openInputStream(uri) ?: throw IllegalStateException("无法读取文件")).use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            require(tmp.length() > 0) { "文件为空：$displayName" }

            var base = displayName.substringBeforeLast('.')
            var cover: ByteArray? = null
            if (ext == "epub") {
                val meta = runCatching { readEpubMeta(tmp) }.getOrNull()
                cover = meta?.cover
                val title = meta?.title
                if (!title.isNullOrBlank()) base = if (meta.author.isNullOrBlank()) title else "$title(${meta.author})"
            }
            base = sanitize(base)
            // 重名时加序号（放在书名后、作者前，避免被识别为作者）
            var target = File(booksDir, "$base.$ext")
            var n = 2
            while (target.exists()) {
                val m = NAME_RE.matchEntire(base)
                val name = if (m != null) "${m.groupValues[1]}_$n(${m.groupValues[2]})" else "${base}_$n"
                target = File(booksDir, "$name.$ext")
                n++
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
            }
            saveCover(target, cover ?: runCatching { extractCover(target) }.getOrNull())
            refresh()
            return NAME_RE.matchEntire(target.nameWithoutExtension)?.groupValues?.get(1) ?: target.nameWithoutExtension
        } finally {
            tmp.delete()
        }
    }

    /** 从书籍文件中提取封面：EPUB 取内置封面，PDF 取第一页 */
    private fun extractCover(file: File): ByteArray? = when (file.extension.lowercase()) {
        "epub" -> readEpubMeta(file).cover
        "pdf" -> pdfFirstPage(file)
        else -> null
    }

    private fun pdfFirstPage(file: File): ByteArray? =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (renderer.pageCount == 0) return null
                renderer.openPage(0).use { page ->
                    val w = 480
                    val h = (w.toFloat() / page.width * page.height).toInt().coerceIn(1, 2000)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                        .also { bmp.recycle() }
                }
            }
        }

    /**
     * 为书架上还没有封面的 EPUB / PDF 补提取封面（包括之前导入的书）。
     * 会读取文件，需在后台线程调用；提取不到的书会做标记，避免每次重复尝试。
     */
    fun fillMissingCovers() {
        var changed = false
        val files = booksDir.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("epub", "pdf") }.orEmpty()
        for (f in files) {
            val marker = File(coversDir, "${f.name}.nocover2")
            if (File(coversDir, "${f.name}.jpg").exists() || marker.exists()) continue
            val bytes = runCatching { extractCover(f) }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                saveCover(f, bytes)
                changed = true
            } else runCatching { marker.createNewFile() }
        }
        if (changed) refresh()
    }

    fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun mimeOf(file: File): String = when (file.extension.lowercase()) {
        "epub" -> "application/epub+zip"
        "txt" -> "text/plain"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    fun openIntent(file: File): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uriFor(file), mimeOf(file))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    fun shareIntent(file: File): Intent = Intent(Intent.ACTION_SEND)
        .setType(mimeOf(file))
        .putExtra(Intent.EXTRA_STREAM, uriFor(file))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /** 需要存储权限才能写公共目录（仅 Android 9 及以下） */
    val needsLegacyPermission: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** 复制到 公共下载目录/SoNovel，返回可读路径 */
    fun copyToPublic(file: File): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val relative = Environment.DIRECTORY_DOWNLOADS + "/SoNovel/"
            // 删除本应用之前写入的同名文件，避免生成 “xxx (1).epub”
            runCatching {
                resolver.delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(file.name, relative),
                )
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(file))
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法写入下载目录")
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "下载/SoNovel/${file.name}"
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SoNovel")
            dir.mkdirs()
            val target = File(dir, file.name)
            file.copyTo(target, overwrite = true)
            return target.absolutePath
        }
    }

    companion object {
        private val NAME_RE = Regex("^(.*)\\((.*)\\)$")
        val IMPORT_EXTENSIONS = setOf("epub", "txt", "pdf")

        fun sanitize(name: String): String = name
            .replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_")
            .trim()
            .take(120)
            .ifEmpty { "book" }
    }
}
