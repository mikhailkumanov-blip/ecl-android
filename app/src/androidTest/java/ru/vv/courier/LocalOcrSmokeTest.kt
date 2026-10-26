package ru.vv.courier

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.vv.courier.ocr.LocalOcr
import java.io.File

/** Exercises the bundled native OCR and Russian model, rather than only the text parser. */
@RunWith(AndroidJUnit4::class)
class LocalOcrSmokeTest {
    @Test
    fun bundledRussianModelReadsSyntheticCardLocally() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val screenshot = File(context.cacheDir, "synthetic-ocr-smoke.png")
        val bitmap = Bitmap.createBitmap(1080, 1000, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 56f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
            listOf("Мои", "Заказ TEST-001", "улица Учебная, 10", "12:00–13:00", "250 руб", "5 кг")
                .forEachIndexed { index, line -> canvas.drawText(line, 64f, 100f + index * 120f, paint) }
            screenshot.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            val text = LocalOcr(context).recognize(Uri.fromFile(screenshot))
            assertTrue("The bundled Russian model must read Cyrillic street text", text.contains("Учебная", ignoreCase = true))
            assertTrue("The bundled Russian model must read a Russian address label", text.contains("улица", ignoreCase = true))
        } finally {
            bitmap.recycle()
            screenshot.delete()
        }
    }
}
