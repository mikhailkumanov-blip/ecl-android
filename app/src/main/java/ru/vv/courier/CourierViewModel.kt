package ru.vv.courier

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.vv.courier.core.importing.ScreenshotParser
import ru.vv.courier.core.routing.*
import ru.vv.courier.data.CourierDatabase
import ru.vv.courier.data.MetadataEntity
import ru.vv.courier.data.OrderEntity
import ru.vv.courier.ocr.LocalOcr
import ru.vv.courier.routing.RoadMatrices
import ru.vv.courier.ui.*
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** All customer-related route data stays in the app's private Room database. */
class CourierViewModel(application: Application) : AndroidViewModel(application), UiActions {
    private val zone = ZoneId.of("Europe/Moscow")
    private val dateFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
    private val dateParser = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm[:ss]").withResolverStyle(ResolverStyle.STRICT)
    private val dao = CourierDatabase.get(application).courierDao()
    private val ocr = LocalOcr(application)
    private val parser = ScreenshotParser()
    private val optimizer = RouteOptimizer()
    private val persistence = Mutex()
    private val _state = MutableStateFlow(AppUiState(busy = true,
        settings = CourierSettings(capturedAt = formatTime(epochNow()))))
    val state: StateFlow<AppUiState> = _state.asStateFlow()
    private var matrix: TravelMatrix? = null
    private var startNodeId = "depot"
    private var revision = 0L
    private val draftOriginalIds = mutableListOf<String?>()
    private var importedDrafts = false

    init {
        viewModelScope.launch {
            try {
                val orders = dao.orders().map { it.toOrder() }
                val meta = dao.metadata().associate { it.key to it.value }
                matrix = meta["matrix"]?.let { RoadMatrices.decode(it) }
                startNodeId = meta["startNode"] ?: "depot"
                val settings = meta["settings"]?.let { settingsFromJson(JSONObject(it)) }
                    ?: _state.value.settings
                _state.value = _state.value.copy(orders = orders, settings = settings,
                    shiftStartedAt = meta["shiftStartedAt"]?.toLongOrNull() ?: epochNow(),
                    matrixLabel = matrix?.source, matrixIsDemo = matrix?.isDemo ?: false,
                    matrixCapturedAt = matrix?.capturedAt, busy = false)
                if (matrix != null && orders.isNotEmpty()) optimize()
            } catch (_: Exception) {
                _state.value = _state.value.copy(busy = false,
                    message = "Не удалось прочитать локальную базу. Данные не удалены; попробуйте перезапустить приложение.")
            }
        }
        viewModelScope.launch {
            var ticks = 0
            while (true) {
                delay(1000)
                _state.value = _state.value.copy(now = epochNow())
                if (++ticks % 60 == 0 && matrix != null && !_state.value.busy &&
                    _state.value.orders.any { !it.completed }) optimize()
            }
        }
    }

    fun importImages(uris: List<Uri>) {
        if (uris.isEmpty() || _state.value.busy) return
        if (_state.value.drafts.isNotEmpty()) {
            inform("Сначала сохраните или удалите текущие карточки проверки, затем импортируйте следующую партию."); return
        }
        val captureInput = _state.value.settings.capturedAt
        val capturedAt = runCatching { parseTime(captureInput) }.getOrElse {
            inform("Перед импортом укажите время получения скриншота: yyyy-MM-dd HH:mm[:ss], Москва."); return
        }
        if (capturedAt > epochNow() + 60) {
            inform("Время скриншота находится в будущем. Исправьте дату и время получения данных."); return
        }
        if (uris.size > 20) { inform("Импортируйте не более 20 изображений за раз."); return }
        work {
            val batches = uris.map { uri -> parser.parse(ocr.recognize(uri), capturedAt) }
            val merged = parser.merge(batches)
            if (_state.value.settings.capturedAt != captureInput) {
                inform("Время получения изменилось во время OCR. Импортируйте изображения заново."); return@work
            }
            val warnings = merged.warnings + buildList {
                add("Время информации: ${formatTime(capturedAt)}, Москва. Проверьте его: обратные таймеры привязаны именно к нему.")
                if (epochNow() - capturedAt > 300) add("Скриншот старше 5 минут: таймеры могли устареть, сверяйте с официальным приложением.")
                if (uris.size > 1) add("Для всех изображений использовано одно время. Если снимки получены в разное время, импортируйте их отдельно с верным временем каждого.")
            }
            val drafts = merged.candidates.map { candidate -> OrderDraft(
                id = candidate.id, address = candidate.address,
                windowStart = candidate.windowStart?.let(::formatTime).orEmpty(),
                deadline = candidate.deadline?.let(::formatTime).orEmpty(),
                rewardRub = candidate.rewardRub?.toString() ?: "0",
                weightKg = candidate.weightKg?.toString() ?: "0",
                warnings = candidate.warnings,
            ) }
            draftOriginalIds.clear()
            draftOriginalIds.addAll(List(drafts.size) { null })
            importedDrafts = true
            _state.value = _state.value.copy(drafts = drafts, importWarnings = warnings.distinct(),
                message = if (drafts.isEmpty()) "Заказы не распознаны. Проверьте вкладку и качество изображения или добавьте заказ вручную."
                    else "Распознано ${drafts.size} заказов. Проверьте все поля и подтвердите назначение каждого.")
        }
    }

    fun importMatrix(uri: Uri) {
        work {
            val text = withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytesLimited(2_000_000)
                    bytes.toString(Charsets.UTF_8)
                } ?: error("Не удалось открыть файл дорожной матрицы.")
            }
            val newMatrix = RoadMatrices.decode(text)
            validateMatrixCoverage(newMatrix)
            installMatrix(newMatrix)
            inform("Матрица загружена. Прогноз зависит от источника и времени дорожных данных.")
            persist()
        }
    }

    override fun updateDraft(index: Int, field: DraftField, value: String) {
        val previous = _state.value.drafts.getOrNull(index) ?: return
        val draft = when (field) {
            DraftField.ID -> previous.copy(id = value)
            DraftField.ADDRESS -> previous.copy(address = value)
            DraftField.WINDOW_START -> previous.copy(windowStart = value)
            DraftField.DEADLINE -> previous.copy(deadline = value)
            DraftField.REWARD -> previous.copy(rewardRub = value)
            DraftField.WEIGHT -> previous.copy(weightKg = value)
            DraftField.SERVICE_MINUTES -> previous.copy(serviceMinutes = value)
            DraftField.LATITUDE -> previous.copy(latitude = value)
            DraftField.LONGITUDE -> previous.copy(longitude = value)
        }
        _state.value = _state.value.copy(drafts = _state.value.drafts.toMutableList().also { it[index] = draft })
    }

    override fun confirmAssigned(index: Int, confirmed: Boolean) {
        val drafts = _state.value.drafts.toMutableList()
        val draft = drafts.getOrNull(index) ?: return
        drafts[index] = draft.copy(assignedConfirmed = confirmed)
        _state.value = _state.value.copy(drafts = drafts)
    }

    override fun removeDraft(index: Int) {
        if (index !in _state.value.drafts.indices) return
        _state.value = _state.value.copy(drafts = _state.value.drafts.toMutableList().also { it.removeAt(index) })
        if (index in draftOriginalIds.indices) draftOriginalIds.removeAt(index)
    }

    override fun acceptDrafts() {
        if (_state.value.busy || _state.value.drafts.isEmpty()) return
        runCatching {
            val current = _state.value
            require(current.drafts.all { it.assignedConfirmed }) { "Подтвердите, что каждый заказ назначен вам во вкладке «Мои»." }
            val capturedAt = runCatching { parseTime(current.settings.capturedAt) }.getOrDefault(epochNow())
            val editedIds = draftOriginalIds.filterNotNull().toSet()
            val kept = current.orders.filterNot { it.id in editedIds }.toMutableList()
            var nextPosition = (current.orders.maxOfOrNull { it.originalPosition } ?: 0) + 1
            val parsed = current.drafts.mapIndexed { index, draft ->
                val id = draft.id.trim()
                require(id.isNotBlank() && id.length <= 80 && id != "depot") { "Укажите уникальный номер заказа (не depot), до 80 символов." }
                val address = sanitizeAddress(draft.address)
                require(address.length in 3..240) { "Укажите улицу и дом без имени, телефона, квартиры и кодов доступа." }
                val start = parseTime(draft.windowStart, capturedAt)
                val end = parseTime(draft.deadline, capturedAt)
                require(start <= end) { "Заказ $id: начало окна позже дедлайна. Для перехода через полночь укажите полную дату." }
                val minutes = number(draft.serviceMinutes, "время обслуживания")
                require(minutes in 0.0..1440.0) { "Время обслуживания должно быть от 0 до 1440 минут." }
                val reward = number(draft.rewardRub, "вознаграждение")
                val weight = number(draft.weightKg, "вес")
                require(reward >= 0 && weight >= 0) { "Вес и вознаграждение не могут быть отрицательными." }
                val lat = coordinate(draft.latitude, 90.0)
                val lon = coordinate(draft.longitude, 180.0)
                require((lat == null) == (lon == null)) { "Укажите обе координаты или оставьте обе пустыми." }
                val original = draftOriginalIds.getOrNull(index)?.let { old -> current.orders.find { it.id == old } }
                DeliveryOrder(id, address, start, end, (minutes * 60).toLong(), reward, weight,
                    original?.originalPosition ?: nextPosition++, original?.pinnedPosition,
                    original?.completed ?: false, lat, lon)
            }
            require(parsed.map { it.id }.distinct().size == parsed.size) { "В проверяемых карточках повторяются номера. Исправьте номер или удалите дубликат." }
            parsed.forEach { order ->
                val known = kept.find { it.id == order.id }
                require(known == null) { "Заказ ${order.id} уже сохранён. Удалите дубликат из импорта; для исправления используйте «Редактировать» в маршруте." }
                kept += order
            }
            require(kept.count { !it.completed } <= 20) { "MVP поддерживает до 20 активных заказов." }
            val roadIdentityChanged = parsed.any { changed ->
                val old = current.orders.find { it.id == changed.id }
                old == null || old.address != changed.address || old.latitude != changed.latitude || old.longitude != changed.longitude
            } || editedIds.any { oldId -> parsed.none { it.id == oldId } }
            if (roadIdentityChanged) clearMatrix()
            revision++
            _state.value = _state.value.copy(orders = kept.sortedBy { it.originalPosition }, drafts = emptyList(),
                importWarnings = emptyList(), comparison = null,
                message = "Заказы сохранены локально.${if (roadIdentityChanged) " Загрузите актуальную дорожную матрицу." else ""}")
            draftOriginalIds.clear()
            importedDrafts = false
            if (startNodeId !in kept.map { it.id } && startNodeId != "depot") startNodeId = "depot"
            persist()
            if (matrix != null) optimize()
        }.onFailure { inform(it.message ?: "Проверьте поля заказа.") }
    }

    override fun addManualOrder() {
        if (_state.value.busy) return
        draftOriginalIds += null
        _state.value = _state.value.copy(drafts = _state.value.drafts + OrderDraft(
            windowStart = formatTime(epochNow()), deadline = formatTime(epochNow() + 3600),
            warnings = listOf("Введите данные и подтвердите назначение заказа.")))
    }

    override fun editOrder(id: String) {
        if (_state.value.busy) return
        val order = _state.value.orders.find { it.id == id } ?: return
        if (id in draftOriginalIds) { inform("Этот заказ уже открыт для проверки во вкладке «Импорт»."); return }
        draftOriginalIds += id
        _state.value = _state.value.copy(drafts = _state.value.drafts + OrderDraft(
            id = order.id, address = order.address, windowStart = formatTime(order.windowStart),
            deadline = formatTime(order.deadline), rewardRub = order.rewardRub.toString(),
            weightKg = order.weightKg.toString(), serviceMinutes = (order.serviceSeconds / 60.0).toString(),
            latitude = order.latitude?.toString().orEmpty(), longitude = order.longitude?.toString().orEmpty(),
            assignedConfirmed = true), message = "Заказ открыт для проверки во вкладке «Импорт».")
    }

    override fun loadDemo() {
        if (_state.value.busy) return
        val request = DemoData.scenario(epochNow())
        matrix = request.matrix
        startNodeId = request.startNodeId
        revision++
        draftOriginalIds.clear()
        importedDrafts = false
        _state.value = _state.value.copy(orders = request.orders, drafts = emptyList(),
            comparison = null, matrixLabel = request.matrix.source, matrixIsDemo = true,
            matrixCapturedAt = request.matrix.capturedAt, shiftStartedAt = epochNow(),
            settings = _state.value.settings.copy(returnToDepot = true, preserveCompanyOrder = false),
            importWarnings = emptyList(), message = "Загружены 10 учебных заказов. Расстояния и время синтетические.")
        persist()
        optimize()
    }

    override fun optimize() {
        if (_state.value.busy) return
        val roadMatrix = matrix ?: run { inform("Нет дорожной матрицы: пробег и время прибытия не рассчитываются."); return }
        val snapshot = _state.value
        val version = revision
        val request = RouteRequest(snapshot.orders, roadMatrix, startNodeId, epochNow(),
            if (snapshot.settings.returnToDepot) "depot" else null,
            snapshot.settings.allowEarlyService, snapshot.settings.preserveCompanyOrder)
        work {
            val comparison = withContext(Dispatchers.Default) { optimizer.compare(request) }
            if (version == revision) {
                val positionWarning = "Старт расчёта — ${if (startNodeId == "depot") "даркстор" else "последняя отмеченная доставка"}. GPS и текущие пробки не отслеживаются; при смене местоположения обновите исходные данные."
                _state.value = _state.value.copy(comparison = comparison.copy(
                    optimized = comparison.optimized.copy(warnings = comparison.optimized.warnings + positionWarning)))
            }
        }
    }

    override fun complete(id: String) {
        if (_state.value.busy) return
        val current = _state.value
        val order = current.orders.find { it.id == id && !it.completed } ?: return
        val routePosition = current.comparison?.optimized?.stops?.indexOfFirst { it.order.id == id }
            ?.takeIf { it >= 0 }?.plus(1)
        val orders = current.orders.map { item ->
            when {
                item.id == order.id -> item.copy(completed = true, pinnedPosition = null)
                item.pinnedPosition != null && routePosition != null && item.pinnedPosition > routePosition -> item.copy(pinnedPosition = item.pinnedPosition - 1)
                routePosition == null -> item.copy(pinnedPosition = null)
                else -> item
            }
        }
        startNodeId = id
        revision++
        _state.value = current.copy(orders = orders, comparison = null,
            message = "Доставка отмечена. Оставшийся маршрут пересчитывается из этой точки.")
        persist()
        if (matrix != null) optimize()
    }

    override fun pin(id: String, position: Int?) {
        if (_state.value.busy) return
        val current = _state.value
        val active = current.orders.filterNot { it.completed }.sortedBy { it.originalPosition }
        if (position != null && position !in 1..active.size) {
            inform("Позиция должна быть от 1 до ${active.size}."); return
        }
        if (position != null && active.any { it.id != id && it.pinnedPosition == position }) {
            inform("Эта позиция уже закреплена за другим заказом."); return
        }
        if (current.settings.preserveCompanyOrder && position != null &&
            active.indexOfFirst { it.id == id } + 1 != position) {
            inform("Закрепление противоречит запрету компании менять последовательность."); return
        }
        revision++
        _state.value = current.copy(orders = current.orders.map { if (it.id == id) it.copy(pinnedPosition = position) else it }, comparison = null)
        persist()
        if (matrix != null) optimize()
    }

    override fun moveOriginal(id: String, delta: Int) {
        if (_state.value.busy || delta !in listOf(-1, 1)) return
        val current = _state.value
        if (current.settings.preserveCompanyOrder) {
            inform("Порядок компании закреплён в настройках. Изменение отключено."); return
        }
        val active = current.orders.filterNot { it.completed }.sortedBy { it.originalPosition }.toMutableList()
        val index = active.indexOfFirst { it.id == id }
        val target = index + delta
        if (index < 0 || target !in active.indices) return
        val other = active[target]
        active[target] = active[index]
        active[index] = other
        val positions = active.mapIndexed { i, order -> order.id to i + 1 }.toMap()
        revision++
        _state.value = current.copy(orders = current.orders.map { order ->
            positions[order.id]?.let { order.copy(originalPosition = it) } ?: order
        }, comparison = null, message = "Исходный порядок исправлен. Сверьте его с маршрутом во вкладке «Мои».")
        persist()
        if (matrix != null) optimize()
    }

    override fun updateSetting(field: SettingField, value: String) {
        val s = _state.value.settings
        val updated = when (field) {
            SettingField.DEPOT_ADDRESS -> s.copy(depotAddress = value)
            SettingField.DEPOT_LATITUDE -> s.copy(depotLatitude = value)
            SettingField.DEPOT_LONGITUDE -> s.copy(depotLongitude = value)
            SettingField.RETURN_TO_DEPOT -> s.copy(returnToDepot = value.toBoolean())
            SettingField.ALLOW_EARLY_SERVICE -> s.copy(allowEarlyService = value.toBoolean())
            SettingField.PRESERVE_COMPANY_ORDER -> s.copy(preserveCompanyOrder = value.toBoolean())
            SettingField.VOICE_ENABLED -> s.copy(voiceEnabled = value.toBoolean())
            SettingField.API_KEY -> s.copy(apiKey = value)
            SettingField.CAPTURED_AT -> s.copy(capturedAt = value)
            SettingField.FUEL_LITERS_PER_100KM -> s.copy(fuelLitersPer100Km = value)
            SettingField.FUEL_PRICE_RUB -> s.copy(fuelPriceRub = value)
            SettingField.TOTAL_KM -> s.copy(totalKm = value)
            SettingField.EMPTY_KM -> s.copy(emptyKm = value)
        }
        _state.value = _state.value.copy(settings = updated)
        if (field in setOf(SettingField.DEPOT_ADDRESS, SettingField.DEPOT_LATITUDE, SettingField.DEPOT_LONGITUDE)) {
            clearMatrix()
            startNodeId = "depot"
            revision++
        }
        if (field == SettingField.CAPTURED_AT && importedDrafts && value != s.capturedAt) {
            draftOriginalIds.clear()
            importedDrafts = false
            _state.value = _state.value.copy(drafts = emptyList(), importWarnings = listOf(
                "Время получения изменилось. Импортируйте изображения заново: дедлайны обратных таймеров нужно пересчитать."))
        }
        if (field in setOf(SettingField.RETURN_TO_DEPOT, SettingField.ALLOW_EARLY_SERVICE, SettingField.PRESERVE_COMPANY_ORDER)) {
            revision++
            _state.value = _state.value.copy(comparison = null)
            if (matrix != null) optimize()
        }
        if (field != SettingField.API_KEY) persist()
    }

    override fun fetchMatrix() {
        if (_state.value.busy) return
        val snapshot = _state.value
        val lat = runCatching { coordinate(snapshot.settings.depotLatitude, 90.0) }.getOrNull()
        val lon = runCatching { coordinate(snapshot.settings.depotLongitude, 180.0) }.getOrNull()
        if (lat == null || lon == null) { inform("Введите координаты даркстора."); return }
        if (snapshot.settings.apiKey.isBlank()) { inform("Введите личный API-ключ GraphHopper. Возможна плата по тарифу сервиса."); return }
        val version = revision
        work {
            val neededOrders = snapshot.orders.filter { !it.completed || it.id == startNodeId }
            val newMatrix = RoadMatrices.fetchGraphHopper(neededOrders, lat, lon, snapshot.settings.apiKey)
            if (revision != version) { inform("Заказы изменились во время запроса. Запросите матрицу заново."); return@work }
            validateMatrixCoverage(newMatrix)
            installMatrix(newMatrix)
            inform("Дорожная матрица GraphHopper получена. Текущие пробки не учитываются.")
            persist()
        }
    }

    override fun clearData() {
        if (_state.value.busy) return
        revision++
        matrix = null
        startNodeId = "depot"
        draftOriginalIds.clear()
        importedDrafts = false
        _state.value = AppUiState(settings = CourierSettings(capturedAt = formatTime(epochNow())),
            message = "Локальные заказы, матрица и учёт смены удалены.")
        persist()
    }

    override fun dismissMessage() { _state.value = _state.value.copy(message = null) }
    override fun pickImages() { inform("Откройте выбор изображений через Android Photo Picker.") }
    override fun pickMatrix() { inform("Выберите JSON дорожной матрицы.") }
    override fun navigate(address: String) { /* Bound to an Android Intent by the Activity. */ }

    private fun installMatrix(value: TravelMatrix) {
        matrix = value
        revision++
        _state.value = _state.value.copy(matrixLabel = value.source, matrixIsDemo = value.isDemo,
            matrixCapturedAt = value.capturedAt, comparison = null)
    }

    private fun validateMatrixCoverage(value: TravelMatrix) {
        val required = _state.value.orders.filterNot { it.completed }.map { it.id } + startNodeId + "depot"
        require(required.all { value.hasNode(it) }) { "В матрице должны быть depot, текущая точка и все номера активных заказов." }
    }

    private fun clearMatrix() {
        matrix = null
        _state.value = _state.value.copy(matrixLabel = null, matrixIsDemo = false,
            matrixCapturedAt = null, comparison = null)
    }

    private fun work(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { inform(e.message ?: "Не удалось выполнить действие. Проверьте данные.") }
            finally { _state.value = _state.value.copy(busy = false) }
        }
    }

    private fun persist() {
        viewModelScope.launch {
            persistence.withLock {
                try {
                    val snapshot = _state.value
                    val metadata = listOfNotNull(
                        MetadataEntity("settings", settingsToJson(snapshot.settings).toString()),
                        MetadataEntity("startNode", startNodeId),
                        MetadataEntity("shiftStartedAt", snapshot.shiftStartedAt.toString()),
                        matrix?.let { MetadataEntity("matrix", RoadMatrices.encode(it)) },
                    )
                    dao.replace(snapshot.orders.map { OrderEntity.from(it) }, metadata)
                } catch (_: Exception) { inform("Не удалось сохранить изменения в локальную базу. Проверьте свободное место.") }
            }
        }
    }

    private fun settingsToJson(s: CourierSettings) = JSONObject().apply {
        put("depotAddress", s.depotAddress); put("depotLatitude", s.depotLatitude); put("depotLongitude", s.depotLongitude)
        put("returnToDepot", s.returnToDepot); put("allowEarlyService", s.allowEarlyService)
        put("preserveCompanyOrder", s.preserveCompanyOrder); put("voiceEnabled", s.voiceEnabled)
        put("fuelLitersPer100Km", s.fuelLitersPer100Km); put("fuelPriceRub", s.fuelPriceRub)
        put("totalKm", s.totalKm); put("emptyKm", s.emptyKm)
        // API key, screenshot timestamps, review drafts and OCR buffers are deliberately excluded.
    }
    private fun settingsFromJson(j: JSONObject) = CourierSettings(
        depotAddress = j.optString("depotAddress", "Даркстор"), depotLatitude = j.optString("depotLatitude"),
        depotLongitude = j.optString("depotLongitude"), returnToDepot = j.optBoolean("returnToDepot", true),
        allowEarlyService = j.optBoolean("allowEarlyService", false), preserveCompanyOrder = j.optBoolean("preserveCompanyOrder", false),
        voiceEnabled = j.optBoolean("voiceEnabled", false), capturedAt = formatTime(epochNow()),
        fuelLitersPer100Km = j.optString("fuelLitersPer100Km", "9"), fuelPriceRub = j.optString("fuelPriceRub", "65"),
        totalKm = j.optString("totalKm"), emptyKm = j.optString("emptyKm"))

    private fun inform(message: String) { _state.value = _state.value.copy(message = message) }
    private fun epochNow() = System.currentTimeMillis() / 1000
    private fun formatTime(time: Long): String = Instant.ofEpochSecond(time).atZone(zone).format(dateFormat)
    private fun parseTime(value: String, reference: Long = epochNow()): Long {
        val trimmed = value.trim()
        require(trimmed.isNotEmpty()) { "Заполните начало окна и дедлайн: yyyy-MM-dd HH:mm[:ss] (Москва)." }
        return runCatching {
            val local = if (Regex("\\d{1,2}:\\d{2}(?::\\d{2})?").matches(trimmed)) {
                val normalized = if (trimmed.substringBefore(':').length == 1) "0$trimmed" else trimmed
                val time = LocalTime.parse(normalized, DateTimeFormatter.ofPattern("HH:mm[:ss]").withResolverStyle(ResolverStyle.STRICT))
                Instant.ofEpochSecond(reference).atZone(zone).toLocalDate().atTime(time)
            } else LocalDateTime.parse(trimmed, dateParser)
            local.atZone(zone).toEpochSecond()
        }.getOrElse { error("Проверьте дату и время: yyyy-MM-dd HH:mm[:ss] (Москва); HH:mm использует дату получения данных.") }
    }
    private fun number(value: String, title: String): Double {
        val parsed = value.trim().replace(',', '.').toDoubleOrNull()
        require(parsed != null && parsed.isFinite()) { "Проверьте $title: требуется число." }
        return parsed
    }
    private fun coordinate(value: String, bound: Double): Double? {
        if (value.isBlank()) return null
        val parsed = number(value, "координаты")
        require(parsed in -bound..bound) { "Координата за допустимым диапазоном." }
        return parsed
    }
    private fun sanitizeAddress(value: String): String {
        require(!Regex("(?:\\+7|8)\\s*\\(?\\d{3}\\)?[\\s-]*\\d{3}[\\s-]*\\d{2}[\\s-]*\\d{2}").containsMatchIn(value)) {
            "Удалите телефон из адреса. Для маршрута нужны только улица и дом."
        }
        return value.trim().replace(Regex("(?:[,;]\\s*)?(?:квартира\\b|кв\\.|домофон\\b|код\\s+доступа\\b|подъезд\\b|этаж\\b).*$", RegexOption.IGNORE_CASE), "").trim().trimEnd(',', ';')
    }
    private fun java.io.InputStream.readBytesLimited(max: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val block = ByteArray(8192)
        while (true) {
            val count = read(block)
            if (count < 0) break
            require(buffer.size() + count <= max) { "Файл матрицы слишком большой (максимум 2 МБ)." }
            buffer.write(block, 0, count)
        }
        return buffer.toByteArray()
    }
}
