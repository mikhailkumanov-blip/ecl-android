package ru.vv.courier.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Fully local Cyrillic OCR. Selected images and the OCR buffer are never saved. */
class LocalOcr(context: Context) {
    private val app = context.applicationContext
    private val mutex = Mutex()
    private val dataDirectory = File(app.filesDir, "local-ocr")
    private val languages = listOf("rus", "eng")

    fun isReady(): Boolean = languages.all { language ->
        val file = File(dataDirectory, "tessdata/$language.traineddata")
        file.isFile && file.length() > 100_000 || runCatching {
            app.assets.open("tessdata/$language.traineddata").use { it.available() > 100_000 }
        }.getOrDefault(false)
    }

    suspend fun recognize(uri: Uri): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            installModels()
            val bitmap = decode(uri)
            val tess = TessBaseAPI()
            try {
                check(tess.init(dataDirectory.absolutePath, "rus+eng")) {
                    "Не удалось запустить локальный OCR rus+eng. Проверьте модели распознавания."
                }
                tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
                tess.setImage(bitmap)
                tess.utF8Text.orEmpty()
            } finally {
                // Tesseract native buffers can contain customer data; release them promptly.
                tess.recycle()
                bitmap.recycle()
            }
        }
    }

    private fun installModels() {
        val destination = File(dataDirectory, "tessdata")
        check(destination.isDirectory || destination.mkdirs()) { "Не удалось подготовить локальный OCR." }
        languages.forEach { language ->
            val file = File(destination, "$language.traineddata")
            if (!file.isFile || file.length() < 100_000) {
                val temporary = File(destination, "$language.traineddata.tmp")
                try {
                    app.assets.open("tessdata/$language.traineddata").use { input ->
                        temporary.outputStream().use { output -> input.copyTo(output) }
                    }
                    check(temporary.length() > 100_000 && temporary.renameTo(file)) {
                        "Не удалось установить модель OCR $language."
                    }
                } finally {
                    temporary.delete()
                }
            }
        }
    }

    private fun decode(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val stream = app.contentResolver.openInputStream(uri) ?: error("Не удалось открыть выбранный скриншот.")
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Файл не является поддерживаемым изображением." }
        // A POCO screenshot remains legible while even a huge image cannot exhaust native memory.
        var sample = 1
        while (bounds.outWidth.toLong() / sample * (bounds.outHeight.toLong() / sample) > 8_000_000L ||
            maxOf(bounds.outWidth, bounds.outHeight) / sample > 4096
        ) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Не удалось декодировать скриншот.")
    }
}
