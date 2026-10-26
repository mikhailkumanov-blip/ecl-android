package ru.vv.courier.core.routing

import kotlin.math.max

/**
 * One courier, individual time windows, deterministic and directed matrix costs.
 * Completion (including parking/building/lift/handover service) is compared with deadline.
 * Exact branch-and-bound for up to 10 active orders, bounded beam search for 11–20.
 * Objective: late count, total lateness, maximum lateness, metres, finish time.
 */
class RouteOptimizer {
    fun compare(request: RouteRequest): RouteComparison {
        val orders = validate(request)
        val original = evaluate(request, orders, exhaustive = false, original = true)
        return RouteComparison(original, optimizeValidated(request, orders))
    }

    fun optimize(request: RouteRequest): RoutePlan = optimizeValidated(request, validate(request))

    private fun validate(request: RouteRequest): List<DeliveryOrder> {
        require(request.startTime in 0..253_402_300_799L) { "Проверьте абсолютное время начала маршрута." }
        require(request.orders.map { it.id }.distinct().size == request.orders.size) { "У заказов должны быть разные идентификаторы, даже если адрес совпадает." }
        require(request.orders.all { it.id.isNotBlank() && it.address.isNotBlank() }) { "Заполните идентификатор и адрес каждого заказа." }
        require(request.orders.all { it.windowStart in 0..253_402_300_799L && it.deadline in 0..253_402_300_799L && it.windowStart <= it.deadline && it.serviceSeconds in 0..86_400L }) { "Проверьте начало/конец окна и время обслуживания (0–1440 мин)." }
        require(request.orders.all { it.rewardRub.isFinite() && it.rewardRub >= 0 && it.weightKg.isFinite() && it.weightKg >= 0 }) { "Вознаграждение и вес должны быть неотрицательными числами." }
        val active = request.orders.filterNot { it.completed }.sortedBy { it.originalPosition }
        require(active.size <= 20) { "MVP рассчитывает до 20 активных заказов." }
        val needed = active.map { it.id } + request.startNodeId + listOfNotNull(request.returnNodeId)
        require(needed.all { request.matrix.hasNode(it) }) { "Дорожная матрица не содержит все заказы, текущую точку и точку возврата. Обновите матрицу." }
        val pins = active.mapNotNull { order -> order.pinnedPosition?.let { it to order.id } }
        require(pins.all { it.first in 1..active.size }) { "Закреплённая позиция должна быть от 1 до числа оставшихся заказов. Проверьте закрепления после доставки." }
        require(pins.map { it.first }.distinct().size == pins.size) { "На одну позицию закреплено несколько заказов." }
        if (request.preserveCompanyOrder) {
            require(active.withIndex().all { (index, order) -> order.pinnedPosition == null || order.pinnedPosition == index + 1 }) { "Закреплённая позиция противоречит запрету компании менять порядок." }
        }
        return active
    }

    private fun optimizeValidated(request: RouteRequest, orders: List<DeliveryOrder>): RoutePlan {
        if (request.preserveCompanyOrder || orders.isEmpty()) return evaluate(request, orders, exhaustive = true)
        val exact = orders.size <= EXACT_LIMIT
        val allMask = (1 shl orders.size) - 1
        val pinnedAt = orders.withIndex().filter { it.value.pinnedPosition != null }.associate { it.value.pinnedPosition!! - 1 to it.index }

        fun available(mask: Int, depth: Int): List<Int> {
            val fixed = pinnedAt[depth]
            if (fixed != null) return if (mask and (1 shl fixed) == 0) listOf(fixed) else emptyList()
            return orders.indices.filter { mask and (1 shl it) == 0 && orders[it].pinnedPosition == null }
        }

        fun append(state: State, index: Int): State {
            val order = orders[index]
            val arrival = Math.addExact(state.time, request.matrix.seconds(state.node, order.id))
            val start = if (request.allowEarlyService) arrival else max(arrival, order.windowStart)
            val completion = Math.addExact(start, order.serviceSeconds)
            val late = max(0L, completion - order.deadline)
            return State(
                mask = state.mask or (1 shl index),
                path = state.path + index,
                node = order.id,
                time = completion,
                distance = state.distance + request.matrix.meters(state.node, order.id),
                lateCount = state.lateCount + if (late > 0) 1 else 0,
                lateTotal = state.lateTotal + late,
                lateMax = max(state.lateMax, late),
            )
        }

        val initial = State(0, intArrayOf(), request.startNodeId, request.startTime, 0L, 0, 0L, 0L)
        fun finish(state: State): State = request.returnNodeId?.let { target ->
            state.copy(time = state.time + request.matrix.seconds(state.node, target), distance = state.distance + request.matrix.meters(state.node, target))
        } ?: state

        // Deterministic incumbents satisfy pins. Multiple starts help feasibility before exact search.
        var best: State? = null
        for (strategy in 0..2) {
            var state = initial
            while (state.mask != allMask) {
                val candidates = available(state.mask, state.path.size)
                val next = when (strategy) {
                    0 -> candidates.minBy { orders[it].deadline }
                    1 -> candidates.minWith(compareBy<Int> { request.matrix.meters(state.node, orders[it].id) }.thenBy { orders[it].deadline })
                    else -> candidates.minBy { orders[it].originalPosition }
                }
                state = append(state, next)
            }
            val candidate = finish(state)
            if (best == null || compare(candidate, best!!) < 0) best = candidate
        }

        if (exact) {
            fun visit(state: State) {
                val incumbent = best!!
                // Every component below is monotone. Only prune on a proven lower bound.
                if (compareLowerBound(state, incumbent) > 0) return
                if (state.mask == allMask) {
                    val candidate = finish(state)
                    if (compare(candidate, best!!) < 0) best = candidate
                    return
                }
                val next = available(state.mask, state.path.size).sortedWith(
                    compareBy<Int> { orders[it].deadline }.thenBy { request.matrix.meters(state.node, orders[it].id) }
                )
                for (index in next) visit(append(state, index))
            }
            visit(initial)
        } else {
            var beam = listOf(initial)
            repeat(orders.size) {
                val candidates = ArrayList<State>(beam.size * orders.size)
                for (state in beam) {
                    for (index in available(state.mask, state.path.size)) {
                        val next = append(state, index)
                        if (compareLowerBound(next, best!!) <= 0) candidates += next
                    }
                }
                // Path identity is kept: two states at the same node can have different
                // distance/time tradeoffs and cannot safely be collapsed into one.
                beam = candidates.sortedWith { a, b ->
                    val score = compare(a, b)
                    if (score != 0) score else a.path.joinToString(",").compareTo(b.path.joinToString(","))
                }.take(BEAM_WIDTH)
            }
            for (state in beam) {
                val candidate = finish(state)
                if (compare(candidate, best!!) < 0) best = candidate
            }
            // Improve complete incumbent by pair swaps; constraints remain enforced.
            var changed = true
            var rounds = 0
            while (changed && rounds++ < 8) {
                changed = false
                val currentPath = best!!.path
                for (i in currentPath.indices) for (j in i + 1 until currentPath.size) {
                    if (orders[currentPath[i]].pinnedPosition != null || orders[currentPath[j]].pinnedPosition != null) continue
                    val path = currentPath.copyOf()
                    val swap = path[i]; path[i] = path[j]; path[j] = swap
                    var state = initial
                    for (index in path) state = append(state, index)
                    val candidate = finish(state)
                    if (compare(candidate, best!!) < 0) { best = candidate; changed = true }
                }
            }
        }
        return evaluate(request, best!!.path.map { orders[it] }, exact)
    }

    private fun evaluate(request: RouteRequest, sequence: List<DeliveryOrder>, exhaustive: Boolean, original: Boolean = false): RoutePlan {
        var node = request.startNodeId
        var time = request.startTime
        var distance = 0L
        val stops = sequence.map { order ->
            val arrival = Math.addExact(time, request.matrix.seconds(node, order.id))
            val start = if (request.allowEarlyService) arrival else max(arrival, order.windowStart)
            val completion = Math.addExact(start, order.serviceSeconds)
            distance += request.matrix.meters(node, order.id)
            node = order.id
            time = completion
            RouteStop(order, arrival, start, completion, order.deadline - completion, max(0L, completion - order.deadline))
        }
        request.returnNodeId?.let { target ->
            distance += request.matrix.meters(node, target)
            time = Math.addExact(time, request.matrix.seconds(node, target))
        }
        val late = stops.filter { it.lateSeconds > 0 }
        val warnings = buildList {
            if (request.matrix.isDemo) add("ДЕМО: матрица синтетическая; это не дорожные расстояния, пробки или прогноз поездки по Москве.")
            if (request.startTime - request.matrix.capturedAt > 900) add("Дорожная матрица старше 15 минут. Обновите дорожные данные: прогноз мог устареть.")
            if (request.matrix.capturedAt > request.startTime + 60) add("Время дорожной матрицы находится в будущем. Проверьте часы и источник данных.")
            if (!original && !exhaustive) add("Для 11–20 заказов используется эвристика: минимум пробега и существование маршрута без опозданий не доказаны.")
            if (late.isNotEmpty()) {
                add(when {
                    original -> "В исходной последовательности прогнозируются опоздания."
                    exhaustive -> "С учётом заданных ограничений маршрут без опозданий отсутствует; показан минимум нарушений по числу, сумме и максимуму опозданий."
                    else -> "Прогнозируются опоздания. Для эвристического расчёта невозможность выполнить сроки не доказана."
                })
            }
            if (request.preserveCompanyOrder) add("Изменение последовательности запрещено: сохранён порядок компании.")
        }
        return RoutePlan(stops, distance, time, late.size, late.sumOf { it.lateSeconds }, late.maxOfOrNull { it.lateSeconds } ?: 0L, exhaustive, warnings)
    }

    private data class State(
        val mask: Int, val path: IntArray, val node: String, val time: Long,
        val distance: Long, val lateCount: Int, val lateTotal: Long, val lateMax: Long,
    )

    private fun compare(a: State, b: State): Int = compareValuesBy(a, b,
        { it.lateCount }, { it.lateTotal }, { it.lateMax }, { it.distance }, { it.time })

    private fun compareLowerBound(a: State, b: State): Int = compare(a, b)

    private companion object {
        const val EXACT_LIMIT = 10
        const val BEAM_WIDTH = 384
    }
}
