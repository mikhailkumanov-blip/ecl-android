package ru.vv.courier.core.routing

/** Entirely artificial fixture: no customer records, no real road/traffic claims. */
object DemoData {
    fun scenario(now: Long): RouteRequest {
        val ids = (1..10).map { "DEMO-%02d".format(it) }
        val depot = "depot"
        val desired = listOf(ids[2], ids[0], ids[4], ids[5], ids[1], ids[3], ids[7], ids[6], ids[9], ids[8])
        val nodes = listOf(depot) + ids
        val ring = listOf(depot) + desired
        fun steps(from: String, to: String): Int = (ring.indexOf(to) - ring.indexOf(from) + ring.size) % ring.size
        val seconds = nodes.map { from -> nodes.map { to ->
            when {
                from == to -> 0L
                setOf(from, to) == setOf(ids[4], ids[5]) -> 0L // two distinct deliveries at one address
                else -> steps(from, to) * 75L + 30L
            }
        } }
        val meters = nodes.map { from -> nodes.map { to ->
            when {
                from == to -> 0L
                setOf(from, to) == setOf(ids[4], ids[5]) -> 0L
                else -> steps(from, to) * 450L
            }
        } }
        val matrix = TravelMatrix(nodes, seconds, meters, now, "Синтетическая учебная матрица, не дорожные данные", isDemo = true)
        var completion = now
        var previous = depot
        val deadlines = mutableMapOf<String, Long>()
        val starts = mutableMapOf<String, Long>()
        for ((position, id) in desired.withIndex()) {
            val service = 300L + (ids.indexOf(id) % 3) * 60L
            val earliest = if (position == 3) now + 30 * 60 else now
            completion = maxOf(completion + matrix.seconds(previous, id), earliest) + service
            starts[id] = earliest
            deadlines[id] = completion + 120L
            previous = id
        }
        val orders = ids.mapIndexed { index, id ->
            val addressNumber = if (index == 5) 5 else index + 1
            DeliveryOrder(
                id = id,
                address = "Демо-адрес $addressNumber, Люблино (условный)",
                windowStart = starts.getValue(id), deadline = deadlines.getValue(id),
                serviceSeconds = 300L + (index % 3) * 60L,
                rewardRub = 220.0 + index * 15,
                weightKg = 2.5 + index * 0.7,
                originalPosition = index + 1,
            )
        }
        return RouteRequest(orders, matrix, depot, now, returnNodeId = depot)
    }
}
