package com.wang.sonovel.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        refresh()
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

        fun sanitize(name: String): String = name
            .replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_")
            .trim()
            .take(120)
            .ifEmpty { "book" }
    }
}
