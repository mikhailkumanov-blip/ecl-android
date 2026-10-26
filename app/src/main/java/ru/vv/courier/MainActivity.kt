package ru.vv.courier

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.vv.courier.ui.CourierApp
import ru.vv.courier.ui.UiActions
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val model: CourierViewModel by viewModels()
    private var voice: TextToSpeech? = null
    private var voiceReady = false
    private val lastWarning = mutableMapOf<String, Long>()
    private val images = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        enqueueImages(uris)
    }
    private val matrices = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { model.importMatrix(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voice = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = voice?.setLanguage(Locale("ru", "RU"))
                voiceReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
        val actions = object : UiActions by model {
            override fun pickImages() = images.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            override fun pickMatrix() = matrices.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            override fun navigate(address: String) = openNavigator(address)
        }
        setContent { CourierApp(model.state.collectAsStateWithLifecycle().value, actions) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    if (state.settings.voiceEnabled && voiceReady && !state.busy) {
                        val critical = state.comparison?.optimized?.stops?.filter { it.slackSeconds < 300 }
                            ?: emptyList()
                        val next = critical.firstOrNull { state.now - (lastWarning[it.order.id] ?: 0) >= 300 }
                        if (next != null) {
                            lastWarning[next.order.id] = state.now
                            voice?.speak(
                                if (next.lateSeconds > 0) "Внимание. В маршруте прогнозируется опоздание. Проверьте сроки доставки."
                                else "Внимание. У доставки осталось меньше пяти минут запаса.",
                                TextToSpeech.QUEUE_FLUSH, null, "route-warning",
                            )
                        }
                    }
                }
            }
        }
        if (savedInstanceState == null) handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    @Suppress("DEPRECATION")
    private fun handleShare(shared: Intent?) {
        if (shared?.action !in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return
        if (shared?.type?.startsWith("image/") != true) return
        val uris = when (shared.action) {
            Intent.ACTION_SEND -> listOfNotNull(shared.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            else -> shared.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }.ifEmpty {
            shared.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
        }
        enqueueImages(uris.distinct())
    }

    private fun enqueueImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            model.state.first { !it.busy }
            model.importImages(uris)
        }
    }

    private fun openNavigator(address: String) {
        val order = model.state.value.orders.firstOrNull { it.address == address && !it.completed }
        val settings = model.state.value.settings
        val latitude = order?.latitude ?: if (address == settings.depotAddress) settings.depotLatitude.replace(',', '.').toDoubleOrNull() else null
        val longitude = order?.longitude ?: if (address == settings.depotAddress) settings.depotLongitude.replace(',', '.').toDoubleOrNull() else null
        val deepLink = if (latitude != null && longitude != null)
            "yandexnavi://build_route_on_map?lat_to=$latitude&lon_to=$longitude"
        else "yandexnavi://map_search?text=${Uri.encode(address)}"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).setPackage("ru.yandex.yandexnavi"))
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://yandex.ru/maps/?rtext=~${Uri.encode(address)}&rtt=auto")))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "Установите Яндекс Навигатор или браузер.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        voice?.stop()
        voice?.shutdown()
        voice = null
        super.onDestroy()
    }
}
