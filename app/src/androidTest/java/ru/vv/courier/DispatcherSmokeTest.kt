package ru.vv.courier

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Starts the real Activity, loads its fixture through UI, and completes a delivery through UI. */
@RunWith(AndroidJUnit4::class)
class DispatcherSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun demoLoadsTenOrdersAndDeliveryRecalculatesNineWithoutLosingAny() {
        lateinit var model: CourierViewModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[CourierViewModel::class.java] }
        compose.waitUntil(timeoutMillis = 30_000) { !model.state.value.busy }

        // Reset through the same confirmation flow used on the phone; this also permits reruns.
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("Удалить локальные данные смены").performScrollTo().performClick()
        compose.onNodeWithText("Удалить").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { model.state.value.orders.isEmpty() }
        compose.onNodeWithText("Маршрут").performClick()
        compose.onNodeWithText("Попробовать сценарий · 10 заказов").performScrollTo().performClick()

        compose.waitUntil(timeoutMillis = 60_000) {
            val state = model.state.value
            !state.busy && state.orders.size == 10 && state.comparison?.optimized?.stops?.size == 10
        }
        compose.onNodeWithText("10 в работе · 0 доставлено").performScrollTo().assertIsDisplayed()
        assertTrue(model.state.value.matrixIsDemo)
        assertEquals(0, model.state.value.comparison!!.optimized.lateCount)
        val ids = model.state.value.orders.map { it.id }.toSet()
        val deliveredId = model.state.value.comparison!!.optimized.stops.first().order.id

        compose.onAllNodesWithText("Доставлено · пересчитать")[0].performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 60_000) {
            val state = model.state.value
            !state.busy && state.orders.count { it.completed } == 1 && state.comparison?.optimized?.stops?.size == 9
        }
        compose.onNodeWithText("9 в работе · 1 доставлено").performScrollTo().assertIsDisplayed()
        val after = model.state.value
        assertEquals(ids, after.orders.map { it.id }.toSet())
        assertTrue(after.orders.single { it.id == deliveredId }.completed)
        assertEquals(ids - deliveredId, after.comparison!!.optimized.stops.map { it.order.id }.toSet())
    }
}
