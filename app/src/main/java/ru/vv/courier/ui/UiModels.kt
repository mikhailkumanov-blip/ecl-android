package ru.vv.courier.ui

import ru.vv.courier.core.routing.DeliveryOrder
import ru.vv.courier.core.routing.RouteComparison

/** Text fields stay editable until the user explicitly accepts the review. */
data class OrderDraft(
    val id: String = "",
    val address: String = "",
    val windowStart: String = "",
    val deadline: String = "",
    val rewardRub: String = "0",
    val weightKg: String = "0",
    val serviceMinutes: String = "7",
    val latitude: String = "",
    val longitude: String = "",
    val assignedConfirmed: Boolean = false,
    val warnings: List<String> = emptyList(),
)

enum class DraftField { ID, ADDRESS, WINDOW_START, DEADLINE, REWARD, WEIGHT, SERVICE_MINUTES, LATITUDE, LONGITUDE }

data class CourierSettings(
    val depotAddress: String = "Даркстор",
    val depotLatitude: String = "",
    val depotLongitude: String = "",
    val returnToDepot: Boolean = true,
    val allowEarlyService: Boolean = false,
    val preserveCompanyOrder: Boolean = false,
    val voiceEnabled: Boolean = false,
    val apiKey: String = "",
    val capturedAt: String = "",
    val fuelLitersPer100Km: String = "9",
    val fuelPriceRub: String = "65",
    val totalKm: String = "",
    val emptyKm: String = "",
)

enum class SettingField {
    DEPOT_ADDRESS, DEPOT_LATITUDE, DEPOT_LONGITUDE, RETURN_TO_DEPOT,
    ALLOW_EARLY_SERVICE, PRESERVE_COMPANY_ORDER, VOICE_ENABLED, API_KEY,
    CAPTURED_AT, FUEL_LITERS_PER_100KM, FUEL_PRICE_RUB, TOTAL_KM, EMPTY_KM,
}

data class AppUiState(
    val orders: List<DeliveryOrder> = emptyList(),
    val comparison: RouteComparison? = null,
    val drafts: List<OrderDraft> = emptyList(),
    val settings: CourierSettings = CourierSettings(),
    val busy: Boolean = false,
    val message: String? = null,
    val importWarnings: List<String> = emptyList(),
    val matrixLabel: String? = null,
    val matrixIsDemo: Boolean = false,
    val matrixCapturedAt: Long? = null,
    val now: Long = System.currentTimeMillis() / 1000,
    val shiftStartedAt: Long = System.currentTimeMillis() / 1000,
)

interface UiActions {
    fun updateDraft(index: Int, field: DraftField, value: String)
    fun confirmAssigned(index: Int, confirmed: Boolean)
    fun removeDraft(index: Int)
    fun acceptDrafts()
    fun addManualOrder()
    fun pickImages()
    fun pickMatrix()
    fun loadDemo()
    fun optimize()
    fun complete(id: String)
    fun pin(id: String, position: Int?)
    fun editOrder(id: String)
    /** Move a card within the user-verified original route (delta is -1 or +1). */
    fun moveOriginal(id: String, delta: Int)
    fun navigate(address: String)
    fun updateSetting(field: SettingField, value: String)
    fun clearData()
    fun fetchMatrix()
    fun dismissMessage()
}
