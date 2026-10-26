package ru.vv.courier.core.routing

import org.junit.Assert.*
import org.junit.Test

class RouteOptimizerTest {
    private val now = 1_800_000_000L
    private val optimizer = RouteOptimizer()

    @Test fun tenOrderScenarioIsFeasibleAndReorderedWithoutLosingOrders() {
        val request = DemoData.scenario(now)
        val result = optimizer.compare(request)
        assertEquals(10, result.optimized.stops.size)
        assertEquals(request.orders.map { it.id }.toSet(), result.optimized.stops.map { it.order.id }.toSet())
        assertEquals(0, result.optimized.lateCount)
        assertTrue(result.optimized.stops.all { it.completion <= it.order.deadline })
        assertTrue(result.original.lateCount > 0)
        assertNotEquals(result.original.stops.map { it.order.id }, result.optimized.stops.map { it.order.id })
        assertTrue(result.optimized.exhaustive)
        assertTrue(result.optimized.warnings.any { it.contains("синтетическая") })
    }

    @Test fun deadlineIncludesAllServiceRatherThanArrivalOnly() {
        val request = fixture(1).let { it.copy(orders = listOf(it.orders.single().copy(deadline = now + 200, serviceSeconds = 300))) }
        val stop = optimizer.optimize(request).stops.single()
        assertEquals(now + 60, stop.arrival)
        assertEquals(now + 360, stop.completion)
        assertEquals(160L, stop.lateSeconds)
        assertEquals(-160L, stop.slackSeconds)
    }

    @Test fun prohibitedEarlyDeliveryWaitsUntilWindowAndCountsWaiting() {
        val request = fixture(1).let { it.copy(orders = listOf(it.orders.single().copy(windowStart = now + 600, serviceSeconds = 120))) }
        val waiting = optimizer.optimize(request).stops.single()
        assertEquals(now + 60, waiting.arrival)
        assertEquals(now + 600, waiting.serviceStart)
        assertEquals(now + 720, waiting.completion)
        val early = optimizer.optimize(request.copy(allowEarlyService = true)).stops.single()
        assertEquals(now + 60, early.serviceStart)
        assertEquals(now + 180, early.completion)
    }

    @Test fun twoOrdersAtOneAddressRetainSeparateIdentifiersAndServices() {
        val request = fixture(2).let { request -> request.copy(
            orders = request.orders.map { it.copy(address = "Один условный адрес", serviceSeconds = 180) },
            matrix = request.matrix.copy(
                seconds = listOf(listOf(0L, 60L, 60L), listOf(60L, 0L, 0L), listOf(60L, 0L, 0L)),
                meters = listOf(listOf(0L, 600L, 600L), listOf(600L, 0L, 0L), listOf(600L, 0L, 0L)),
            ),
        ) }
        val result = optimizer.optimize(request)
        assertEquals(2, result.stops.size)
        assertEquals(2, result.stops.map { it.order.id }.distinct().size)
        assertEquals(360L, result.stops.sumOf { it.completion - it.serviceStart })
        assertEquals(result.stops[0].completion, result.stops[1].arrival)
        assertEquals(600L, result.distanceMeters)
    }

    @Test fun impossibleDeadlinesAreReportedWithExactProofOnSmallInput() {
        val request = fixture(2).let { it.copy(orders = it.orders.map { order -> order.copy(deadline = now + 10, serviceSeconds = 120) }) }
        val result = optimizer.optimize(request)
        assertEquals(2, result.lateCount)
        assertTrue(result.totalLateSeconds > 0)
        assertTrue(result.exhaustive)
        assertTrue(result.warnings.any { it.contains("маршрут без опозданий отсутствует") })
    }

    @Test fun fewerLateOrdersTakePriorityOverTotalLatenessAndDistance() {
        val base = fixture(2)
        val matrix = base.matrix.copy(
            seconds = listOf(listOf(0L, 10L, 100L), listOf(10L, 0L, 1_000L), listOf(10L, 10L, 0L)),
            meters = listOf(listOf(0L, 10L, 10L), listOf(10L, 0L, 1_000L), listOf(10L, 1L, 0L)),
        )
        val request = base.copy(matrix = matrix, orders = listOf(
            base.orders[0].copy(deadline = now + 20, serviceSeconds = 0),
            base.orders[1].copy(deadline = now + 50, serviceSeconds = 0),
        ))
        val result = optimizer.optimize(request)
        assertEquals(listOf("o1", "o2"), result.stops.map { it.order.id })
        assertEquals(1, result.lateCount)
        assertEquals(960L, result.totalLateSeconds)
    }

    @Test fun totalLatenessTakesPriorityOverMileageWhenLateCountsTie() {
        val base = fixture(2)
        val request = base.copy(
            orders = base.orders.map { it.copy(deadline = now, serviceSeconds = 0) },
            matrix = base.matrix.copy(
                seconds = listOf(listOf(0L, 10L, 100L), listOf(10L, 0L, 1_000L), listOf(10L, 10L, 0L)),
                meters = listOf(listOf(0L, 1L, 100L), listOf(1L, 0L, 1L), listOf(100L, 100L, 0L)),
            ),
        )
        val result = optimizer.optimize(request)
        assertEquals(listOf("o2", "o1"), result.stops.map { it.order.id })
        assertEquals(2, result.lateCount)
        assertEquals(210L, result.totalLateSeconds)
        assertEquals(200L, result.distanceMeters)
    }

    @Test fun maximumLatenessBreaksEqualCountAndTotalBeforeFinishTime() {
        val base = fixture(3)
        val request = base.copy(
            orders = base.orders.mapIndexed { index, order ->
                order.copy(deadline = now + listOf(71L, 20L, 84L)[index], serviceSeconds = 0)
            },
            matrix = base.matrix.copy(seconds = listOf(
                listOf(0L, 44L, 43L, 75L),
                listOf(27L, 0L, 39L, 47L),
                listOf(30L, 61L, 0L, 10L),
                listOf(70L, 67L, 28L, 0L),
            )),
        )
        val result = optimizer.optimize(request)
        assertEquals(listOf("o2", "o3", "o1"), result.stops.map { it.order.id })
        assertEquals(2, result.lateCount)
        assertEquals(72L, result.totalLateSeconds)
        assertEquals(49L, result.maxLateSeconds)
        assertEquals(now + 120L, result.finishTime)
    }

    @Test fun directedRoadMatrixAndReturnLegBothInfluenceChoice() {
        val base = fixture(2)
        val costs = listOf(listOf(0L, 10L, 10L), listOf(1L, 0L, 100L), listOf(100L, 1L, 0L))
        val request = base.copy(matrix = base.matrix.copy(seconds = costs, meters = costs), returnNodeId = "depot")
        val result = optimizer.optimize(request)
        assertEquals(listOf("o2", "o1"), result.stops.map { it.order.id })
        assertEquals(12L, result.distanceMeters)
        assertEquals(now + 72, result.finishTime) // two 30-second services and all three legs
    }

    @Test fun feasibleDistanceHasPriorityOverFinishTime() {
        val base = fixture(2)
        val seconds = listOf(listOf(0L, 10L, 10L), listOf(10L, 0L, 100L), listOf(10L, 1L, 0L))
        val meters = listOf(listOf(0L, 10L, 10L), listOf(10L, 0L, 1L), listOf(10L, 100L, 0L))
        val result = optimizer.optimize(base.copy(matrix = base.matrix.copy(seconds = seconds, meters = meters)))
        assertEquals(0, result.lateCount)
        assertEquals(listOf("o1", "o2"), result.stops.map { it.order.id })
        assertEquals(11L, result.distanceMeters)
    }

    @Test fun finishTimeBreaksEqualDistanceTies() {
        val base = fixture(2)
        val seconds = listOf(listOf(0L, 10L, 10L), listOf(10L, 0L, 100L), listOf(10L, 1L, 0L))
        val result = optimizer.optimize(base.copy(matrix = base.matrix.copy(seconds = seconds)))
        assertEquals(listOf("o2", "o1"), result.stops.map { it.order.id })
        assertEquals(now + 71, result.finishTime)
    }

    @Test fun pinnedPositionIsRespectedAndCompanyOrderCanBeEnforced() {
        val base = fixture(3)
        val pinned = base.copy(orders = base.orders.map { if (it.id == "o3") it.copy(pinnedPosition = 1) else it })
        assertEquals("o3", optimizer.optimize(pinned).stops.first().order.id)
        assertEquals(listOf("o1", "o2", "o3"), optimizer.optimize(base.copy(preserveCompanyOrder = true)).stops.map { it.order.id })
    }

    @Test(expected = IllegalArgumentException::class) fun conflictingPinsAreRejected() {
        val base = fixture(2)
        optimizer.optimize(base.copy(orders = base.orders.map { it.copy(pinnedPosition = 1) }))
    }

    @Test(expected = IllegalArgumentException::class) fun companyRestrictionAndConflictingPinAreRejected() {
        val base = fixture(2)
        optimizer.optimize(base.copy(preserveCompanyOrder = true, orders = listOf(base.orders[0].copy(pinnedPosition = 2), base.orders[1])))
    }

    @Test fun completingDeliveryRecalculatesFromCurrentPointAndKeepsReturn() {
        val base = fixture(3)
        val request = base.copy(
            orders = base.orders.map { if (it.id == "o1") it.copy(completed = true) else it },
            startNodeId = "o1", startTime = now + 200, returnNodeId = "depot",
        )
        val result = optimizer.optimize(request)
        assertEquals(setOf("o2", "o3"), result.stops.map { it.order.id }.toSet())
        assertEquals(now + 260, result.stops.first().arrival)
        assertEquals(1_800L, result.distanceMeters)
        assertEquals(now + 440, result.finishTime)
    }

    @Test fun twentyOrdersArePreservedAndHeuristicLimitIsDisclosed() {
        val request = fixture(20)
        val result = optimizer.optimize(request)
        assertEquals(20, result.stops.size)
        assertEquals(request.orders.map { it.id }.toSet(), result.stops.map { it.order.id }.toSet())
        assertFalse(result.exhaustive)
        assertTrue(result.warnings.any { it.contains("эвристика") })
        assertEquals(0, result.lateCount)
    }

    @Test fun heuristicLatenessDoesNotClaimAProofAndStillHonorsLastPositionPin() {
        val base = fixture(20)
        val request = base.copy(orders = base.orders.map {
            it.copy(deadline = now + 1, pinnedPosition = if (it.id == "o1") 20 else null)
        })
        val result = optimizer.optimize(request)
        assertEquals("o1", result.stops.last().order.id)
        assertEquals(20, result.stops.map { it.order.id }.toSet().size)
        assertEquals(20, result.lateCount)
        assertFalse(result.exhaustive)
        assertTrue(result.warnings.any { it.contains("невозможность выполнить сроки не доказана") })
        assertFalse(result.warnings.any { it.contains("маршрут без опозданий отсутствует") })
    }

    @Test fun completedRouteStillIncludesRequiredReturnToDepot() {
        val base = fixture(1)
        val result = optimizer.optimize(base.copy(orders = base.orders.map { it.copy(completed = true) }, startNodeId = "o1", returnNodeId = "depot"))
        assertTrue(result.stops.isEmpty())
        assertEquals(600L, result.distanceMeters)
        assertEquals(now + 60, result.finishTime)
    }

    @Test fun staleRoadMatrixIsExplicitlyFlagged() {
        val base = fixture(1)
        val result = optimizer.optimize(base.copy(matrix = base.matrix.copy(capturedAt = now - 901)))
        assertTrue(result.warnings.any { it.contains("старше 15 минут") })
    }

    @Test(expected = IllegalArgumentException::class) fun incompleteMatrixDoesNotInventMissingRoadCosts() {
        val base = fixture(1)
        optimizer.optimize(base.copy(matrix = base.matrix.copy(nodeIds = listOf("depot"), seconds = listOf(listOf(0)), meters = listOf(listOf(0)))))
    }

    @Test fun exactResultMatchesIndependentEnumerationForDirectedSevenOrderCase() {
        val base = fixture(7)
        val seconds = base.matrix.nodeIds.indices.map { from -> base.matrix.nodeIds.indices.map { to -> if (from == to) 0L else (1 + (from * 73 + to * 31) % 47).toLong() } }
        val meters = base.matrix.nodeIds.indices.map { from -> base.matrix.nodeIds.indices.map { to -> if (from == to) 0L else (1 + (from * 97 + to * 59) % 103).toLong() } }
        val request = base.copy(matrix = base.matrix.copy(seconds = seconds, meters = meters), returnNodeId = "depot", orders = base.orders.mapIndexed { index, order ->
            order.copy(windowStart = now + (index % 3) * 45, deadline = now + 70 + (index * 53) % 240, serviceSeconds = 15L + index * 3)
        })
        fun score(sequence: List<DeliveryOrder>): List<Long> {
            var time = now; var node = "depot"; var distance = 0L; var count = 0L; var total = 0L; var maximum = 0L
            for (order in sequence) {
                time = maxOf(time + request.matrix.seconds(node, order.id), order.windowStart) + order.serviceSeconds
                distance += request.matrix.meters(node, order.id)
                val late = maxOf(0L, time - order.deadline)
                if (late > 0) count++
                total += late; maximum = maxOf(maximum, late); node = order.id
            }
            distance += request.matrix.meters(node, "depot"); time += request.matrix.seconds(node, "depot")
            return listOf(count, total, maximum, distance, time)
        }
        val comparator = Comparator<List<Long>> { a, b -> a.zip(b).firstOrNull { it.first != it.second }?.let { it.first.compareTo(it.second) } ?: 0 }
        var best: List<Long>? = null
        fun enumerate(prefix: List<DeliveryOrder>, remaining: List<DeliveryOrder>) {
            if (remaining.isEmpty()) {
                val candidate = score(prefix)
                if (best == null || comparator.compare(candidate, best!!) < 0) best = candidate
            } else for (order in remaining) enumerate(prefix + order, remaining - order)
        }
        enumerate(emptyList(), request.orders)
        val result = optimizer.optimize(request)
        assertEquals(best, listOf(result.lateCount.toLong(), result.totalLateSeconds, result.maxLateSeconds, result.distanceMeters, result.finishTime))
    }

    private fun fixture(count: Int): RouteRequest {
        val orders = (1..count).map { index -> DeliveryOrder("o$index", "Условный адрес $index", now, now + 7_200, serviceSeconds = 30, originalPosition = index) }
        val nodes = listOf("depot") + orders.map { it.id }
        val seconds = nodes.indices.map { from -> nodes.indices.map { to -> if (from == to) 0L else 60L } }
        val meters = nodes.indices.map { from -> nodes.indices.map { to -> if (from == to) 0L else 600L } }
        return RouteRequest(orders, TravelMatrix(nodes, seconds, meters, now, "Обезличенная тестовая матрица", true), "depot", now)
    }
}
