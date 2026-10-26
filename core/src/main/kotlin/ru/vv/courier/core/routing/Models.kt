package ru.vv.courier.core.routing

/** All absolute times are epoch seconds; durations are seconds, lengths are metres. */
data class DeliveryOrder(
    val id: String,
    val address: String,
    val windowStart: Long,
    val deadline: Long,
    val serviceSeconds: Long = 420,
    val rewardRub: Double = 0.0,
    val weightKg: Double = 0.0,
    val originalPosition: Int = 0,
    val pinnedPosition: Int? = null,
    val completed: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/** A complete, directed ROAD matrix. Synthetic fixtures must carry [isDemo]. */
data class TravelMatrix(
    val nodeIds: List<String>,
    val seconds: List<List<Long>>,
    val meters: List<List<Long>>,
    val capturedAt: Long,
    val source: String,
    val isDemo: Boolean = false,
) {
    private val indices = nodeIds.withIndex().associate { it.value to it.index }

    init {
        require(nodeIds.isNotEmpty() && nodeIds.all { it.isNotBlank() }) { "Матрица должна содержать идентификаторы точек." }
        require(indices.size == nodeIds.size) { "Идентификаторы точек матрицы повторяются." }
        require(capturedAt in 0..253_402_300_799L) { "Проверьте абсолютное время получения дорожной матрицы." }
        require(source.isNotBlank()) { "Укажите источник дорожной матрицы." }
        require(seconds.size == nodeIds.size && meters.size == nodeIds.size) { "Неполная матрица: нужны время и метры для каждой пары точек." }
        require(seconds.all { row -> row.size == nodeIds.size && row.all { it in 0..315_360_000L } }) { "Время матрицы должно быть неотрицательным числом секунд для каждой пары точек." }
        require(meters.all { row -> row.size == nodeIds.size && row.all { it in 0..1_000_000_000L } }) { "Расстояния матрицы должны быть неотрицательным числом метров для каждой пары точек." }
        require(nodeIds.indices.all { seconds[it][it] == 0L && meters[it][it] == 0L }) { "Диагональ дорожной матрицы должна быть нулевой." }
    }

    fun hasNode(id: String): Boolean = id in indices
    fun seconds(from: String, to: String): Long = seconds[index(from)][index(to)]
    fun meters(from: String, to: String): Long = meters[index(from)][index(to)]
    private fun index(id: String): Int = requireNotNull(indices[id]) { "В дорожной матрице нет точки $id." }
}

data class RouteRequest(
    val orders: List<DeliveryOrder>,
    val matrix: TravelMatrix,
    val startNodeId: String,
    val startTime: Long,
    val returnNodeId: String? = null,
    val allowEarlyService: Boolean = false,
    val preserveCompanyOrder: Boolean = false,
)

data class RouteStop(
    val order: DeliveryOrder,
    val arrival: Long,
    val serviceStart: Long,
    val completion: Long,
    val slackSeconds: Long,
    val lateSeconds: Long,
)

data class RoutePlan(
    val stops: List<RouteStop>,
    val distanceMeters: Long,
    val finishTime: Long,
    val lateCount: Int,
    val totalLateSeconds: Long,
    val maxLateSeconds: Long,
    /** True only if all permissible sequences were searched or safely bounded. */
    val exhaustive: Boolean,
    val warnings: List<String>,
)

data class RouteComparison(val original: RoutePlan, val optimized: RoutePlan)
