package ru.vv.courier.routing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.vv.courier.core.routing.DeliveryOrder
import ru.vv.courier.core.routing.TravelMatrix
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.ceil

/** Road costs are supplied explicitly. This class never substitutes straight-line estimates. */
object RoadMatrices {
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_NODES = 22 // depot + up to 20 active orders + last completed start point
    private const val MAX_SECONDS = 315_360_000L
    private const val MAX_METERS = 1_000_000_000L

    fun decode(json: String): TravelMatrix {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Файл дорожной матрицы слишком большой (максимум 2 МБ)." }
        try {
            val root = JSONObject(json)
            require(!root.has("version") || integral(root.get("version"), "Версия матрицы", 1L) == 1L) { "Неподдерживаемая версия матрицы." }
            val ids = root.getJSONArray("nodeIds")
            require(ids.length() in 1..MAX_NODES) { "Матрица должна содержать от 1 до 22 точек." }
            val nodeIds = (0 until ids.length()).map { index ->
                val value = ids.get(index)
                require(value is String && value.isNotBlank() && value.length <= 128) { "Некорректный идентификатор точки матрицы." }
                value
            }
            val isDemo = root.get("isDemo")
            require(isDemo is Boolean) { "Укажите isDemo: true для синтетических данных или false для дорожной матрицы." }
            val source = root.get("source")
            require(source is String && source.isNotBlank() && source.length <= 300) { "Укажите короткое название источника дорожных данных." }
            val capturedAt = integral(root.get("capturedAt"), "Время получения матрицы", 253_402_300_799L)
            return TravelMatrix(
                nodeIds = nodeIds,
                seconds = readCosts(root.getJSONArray("seconds"), nodeIds.size, MAX_SECONDS),
                meters = readCosts(root.getJSONArray("meters"), nodeIds.size, MAX_METERS),
                capturedAt = capturedAt,
                source = source,
                isDemo = isDemo,
            )
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (_: Exception) {
            throw IllegalArgumentException("Некорректный JSON матрицы. Нужны nodeIds, seconds, meters, capturedAt, source и isDemo.")
        }
    }

    fun encode(matrix: TravelMatrix): String = JSONObject().apply {
        put("version", 1)
        put("nodeIds", JSONArray(matrix.nodeIds))
        put("seconds", JSONArray(matrix.seconds.map { JSONArray(it) }))
        put("meters", JSONArray(matrix.meters.map { JSONArray(it) }))
        put("capturedAt", matrix.capturedAt)
        put("source", matrix.source)
        put("isDemo", matrix.isDemo)
    }.toString()

    /**
     * Explicit user action only. GraphHopper is an external, potentially paid service.
     * Sends coordinates, never customer names, phone numbers, addresses or order IDs.
     * The key stays in memory and is never put into exceptions or logs.
     */
    suspend fun fetchGraphHopper(
        orders: List<DeliveryOrder>,
        depotLat: Double,
        depotLon: Double,
        apiKey: String,
    ): TravelMatrix = withContext(Dispatchers.IO) {
        require(orders.size <= 21 && orders.count { !it.completed } <= 20) {
            "MVP поддерживает 20 активных заказов и последнюю завершённую точку старта."
        }
        require(orders.map { it.id }.distinct().size == orders.size && orders.none { it.id == "depot" || it.id.isBlank() }) {
            "Идентификаторы заказов должны быть уникальными; depot зарезервирован для даркстора."
        }
        require(apiKey.isNotBlank() && apiKey.length <= 512) { "Введите ключ GraphHopper Matrix API." }
        validateCoordinates(depotLat, depotLon)
        val points = JSONArray().put(JSONArray().put(depotLon).put(depotLat))
        orders.forEach { order ->
            val latitude = requireNotNull(order.latitude) { "Укажите координаты всех заказов перед запросом дорожной матрицы." }
            val longitude = requireNotNull(order.longitude) { "Укажите координаты всех заказов перед запросом дорожной матрицы." }
            validateCoordinates(latitude, longitude)
            points.put(JSONArray().put(longitude).put(latitude))
        }
        val request = JSONObject().apply {
            put("profile", "car")
            put("points", points)
            put("out_arrays", JSONArray().put("times").put("distances"))
            put("fail_fast", true)
        }.toString().toByteArray(Charsets.UTF_8)
        var connection: HttpURLConnection? = null
        try {
            val key = URLEncoder.encode(apiKey.trim(), "UTF-8")
            connection = URL("https://graphhopper.com/api/1/matrix?key=$key").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false // Never forward a key to a redirected host.
            connection.connectTimeout = 20_000
            connection.readTimeout = 40_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setFixedLengthStreamingMode(request.size)
            connection.outputStream.use { it.write(request) }
            val status = connection.responseCode
            check(status in 200..299) {
                when (status) {
                    401, 403 -> "GraphHopper: проверьте ключ и доступ к Matrix API."
                    429 -> "GraphHopper: превышен лимит API. Проверьте тариф и повторите позже."
                    400 -> "GraphHopper не принял запрос. Проверьте координаты и доступность профиля car."
                    else -> "GraphHopper недоступен (HTTP $status). Дорожная матрица не обновлена."
                }
            }
            val response = connection.inputStream.use { readBounded(it) }
            val root = JSONObject(response)
            val size = orders.size + 1
            val times = readCosts(root.getJSONArray("times"), size, MAX_SECONDS)
            val distances = readCosts(root.getJSONArray("distances"), size, MAX_METERS)
            TravelMatrix(
                nodeIds = listOf("depot") + orders.map { it.id },
                seconds = times,
                meters = distances,
                capturedAt = System.currentTimeMillis() / 1000,
                source = "GraphHopper Matrix, автомобиль, без данных о пробках",
                isDemo = false,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: IllegalStateException) {
            throw error
        } catch (_: Exception) {
            // Do not include a network exception/cause: it may contain the API key URL.
            throw IllegalStateException("Не удалось получить полную дорожную матрицу GraphHopper. Проверьте сеть, координаты и тариф API.")
        } finally {
            connection?.disconnect()
        }
    }

    private fun validateCoordinates(latitude: Double, longitude: Double) {
        require(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0) {
            "Проверьте координаты: широта от −90 до 90, долгота от −180 до 180."
        }
    }

    private fun integral(value: Any, label: String, maximum: Long): Long {
        require(value is Number) { "$label должно быть числом." }
        val number = value.toDouble()
        require(number.isFinite() && number >= 0 && number <= maximum.toDouble() && number == number.toLong().toDouble()) {
            "$label должно быть целым неотрицательным числом в допустимом диапазоне."
        }
        return number.toLong()
    }

    private fun readCosts(rows: JSONArray, size: Int, maximum: Long): List<List<Long>> {
        require(rows.length() == size) { "Неполная дорожная матрица: нет всех пар точек." }
        return (0 until size).map { from ->
            val row = rows.getJSONArray(from)
            require(row.length() == size) { "Неполная дорожная матрица: нет всех пар точек." }
            (0 until size).map { to ->
                val value = row.get(to)
                require(value is Number) { "Есть недоступная пара точек или отсутствующее дорожное значение. Исправьте координаты и обновите матрицу." }
                val number = value.toDouble()
                require(number.isFinite() && number >= 0 && number <= maximum.toDouble()) {
                    "Дорожные время и расстояние должны быть конечными неотрицательными числами."
                }
                require(from != to || number == 0.0) { "Диагональ дорожной матрицы должна быть нулевой." }
                // Conservative rounding: never round an API duration down to arrive earlier.
                ceil(number).toLong()
            }
        }
    }

    private fun readBounded(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(bytes.size() + count <= MAX_BYTES) { "Ответ дорожного сервиса слишком большой." }
            bytes.write(buffer, 0, count)
        }
        return bytes.toString("UTF-8")
    }
}
