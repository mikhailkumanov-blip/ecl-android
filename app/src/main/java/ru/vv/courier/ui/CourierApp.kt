package ru.vv.courier.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import ru.vv.courier.core.routing.DeliveryOrder
import ru.vv.courier.core.routing.RoutePlan
import ru.vv.courier.core.routing.RouteStop

private val Green = Color(0xFF9AE9A5)
private val Amber = Color(0xFFFFCE80)
private val Red = Color(0xFFFFB4AB)
private val Moscow = ZoneId.of("Europe/Moscow")
private val Clock = DateTimeFormatter.ofPattern("HH:mm").withZone(Moscow)
private val Day = DateTimeFormatter.ofPattern("d MMMM · HH:mm", Locale("ru")).withZone(Moscow)

@Composable
fun CourierApp(state: AppUiState, actions: UiActions) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var confirmAction by remember { mutableStateOf<String?>(null) }
    val guardedActions = object : UiActions by actions {
        override fun loadDemo() {
            if (state.orders.isNotEmpty()) confirmAction = "demo" else actions.loadDemo()
        }
        override fun clearData() {
            confirmAction = "clear"
        }
    }
    LaunchedEffect(state.drafts.isNotEmpty()) {
        if (state.drafts.isNotEmpty()) selectedTab = 1
    }
    val scrollStates = listOf(rememberScrollState(), rememberScrollState(), rememberScrollState(), rememberScrollState())
    val tabs = listOf("Маршрут", "Импорт", "Смена", "Настройки")
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Green,
            onPrimary = Color(0xFF083416),
            secondary = Amber,
            background = Color(0xFF0C1410),
            surface = Color(0xFF152019),
            surfaceVariant = Color(0xFF243229),
            error = Red,
        ),
    ) {
        confirmAction?.let { action ->
            AlertDialog(
                onDismissRequest = { confirmAction = null },
                title = { Text(if (action == "demo") "Заменить смену демосценарием?" else "Удалить данные смены?") },
                text = { Text(if (action == "demo") "Текущие заказы, маршрут и заработок смены будут заменены учебными данными." else "Локальные заказы, дорожная матрица и заработок смены будут удалены.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmAction = null
                        if (action == "demo") actions.loadDemo() else actions.clearData()
                    }, enabled = !state.busy) { Text(if (action == "demo") "Загрузить демо" else "Удалить") }
                },
                dismissButton = { TextButton(onClick = { confirmAction = null }) { Text("Отмена") } },
            )
        }
        Scaffold(
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { index, title ->
                        NavigationBarItem(
                            selected = index == selectedTab,
                            onClick = { selectedTab = index },
                            icon = { Text(listOf("↗", "+", "₽", "≡")[index], fontSize = 23.sp, modifier = Modifier.clearAndSetSemantics { }) },
                            label = { Text(title, fontSize = 11.sp, maxLines = 1) },
                        )
                    }
                }
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding)
                    .verticalScroll(scrollStates[selectedTab]).padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = Green, shape = RoundedCornerShape(14.dp)) {
                        Text("VV", Modifier.padding(12.dp), color = Color(0xFF103B1A), fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Курьер PRO", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Люблино · ${Day.format(Instant.ofEpochSecond(state.now))}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Обработка…", style = MaterialTheme.typography.bodySmall)
                }
                state.message?.let { message ->
                    Panel {
                        Text(message)
                        TextButton(onClick = actions::dismissMessage) { Text("Понятно") }
                    }
                }
                when (selectedTab) {
                    0 -> RouteScreen(state, guardedActions) { selectedTab = 1 }
                    1 -> ImportScreen(state, guardedActions)
                    2 -> ShiftScreen(state, guardedActions)
                    3 -> SettingsScreen(state, guardedActions)
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun RouteScreen(state: AppUiState, actions: UiActions, openImport: () -> Unit) {
    val active = state.orders.filterNot { it.completed }
    var showOriginal by rememberSaveable { mutableStateOf(false) }
    val plan = if (showOriginal) state.comparison?.original else state.comparison?.optimized
    Title("Диспетчер", "${active.size} в работе · ${state.orders.count { it.completed }} доставлено")
    if (state.matrixIsDemo) {
        Notice("ДЕМО · Синтетическая матрица. Время и километры нужны только для проверки приложения; это не дорожный прогноз.", Amber)
    } else if (state.matrixLabel == null) {
        Notice("Дорожная матрица не загружена. Пробег, пробки и прогноз прибытия пока неизвестны.", Amber)
    } else {
        Text("Дорожные данные: ${state.matrixLabel}", style = MaterialTheme.typography.bodySmall)
        state.matrixCapturedAt?.let {
            Text("Получены ${Day.format(Instant.ofEpochSecond(it))}", style = MaterialTheme.typography.bodySmall)
            if (state.now - it > 900) Notice("Матрице больше 15 минут. Обновите её: дорожная ситуация могла измениться.", Amber)
        }
    }
    if (active.isEmpty()) {
        Panel {
            Text("Начните с заказов", style = MaterialTheme.typography.titleLarge)
            Text("Импортируйте скриншоты вкладки «Мои» или введите заказы вручную. Сначала проверьте адреса и индивидуальные сроки.")
            Button(onClick = openImport, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Добавить заказы") }
            OutlinedButton(onClick = actions::loadDemo, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Попробовать сценарий · 10 заказов") }
        }
        return
    }
    state.comparison?.let { comparison ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PlanSummary("Исходный", comparison.original, state.now, Modifier.weight(1f))
            PlanSummary("Предложенный", comparison.optimized, state.now, Modifier.weight(1f))
        }
        if (comparison.optimized.lateCount > 0) {
            Notice("Есть риск опоздания: ${comparison.optimized.lateCount} заказ(а). Показан лучший найденный вариант; проверьте сроки и свяжитесь с диспетчером по правилам компании.", Red)
        }
        comparison.optimized.warnings.distinct().forEach { Notice(it, Amber) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showOriginal) {
                Button(onClick = { showOriginal = true }, modifier = Modifier.weight(1f)) { Text("Исходный") }
                OutlinedButton(onClick = { showOriginal = false }, modifier = Modifier.weight(1f)) { Text("Предложенный") }
            } else {
                OutlinedButton(onClick = { showOriginal = true }, modifier = Modifier.weight(1f)) { Text("Исходный") }
                Button(onClick = { showOriginal = false }, modifier = Modifier.weight(1f)) { Text("Предложенный") }
            }
        }
    }
    Button(onClick = actions::optimize, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Пересчитать маршрут") }
    val ordered = plan?.stops?.map { it.order } ?: active.sortedBy { it.originalPosition }
    ordered.firstOrNull()?.let { next ->
        OutlinedButton(onClick = { actions.navigate(next.address) }, modifier = Modifier.fillMaxWidth()) { Text("Следующий адрес → Яндекс Навигатор") }
    }
    Text(if (showOriginal) "Порядок из ВкусВилла" else "Последовательность доставок", style = MaterialTheme.typography.titleMedium)
    ordered.forEachIndexed { index, order ->
        OrderCard(index + 1, order, plan?.stops?.firstOrNull { it.order.id == order.id }, state, actions) {
            actions.editOrder(order.id)
            openImport()
        }
    }
    Text("Сверьте исходные места карточек с маршрутом в официальном приложении и исправьте кнопками «Выше» и «Ниже». Порядок скриншотов может отличаться от маршрута. Приложение ВкусВилла не изменяется.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun PlanSummary(title: String, plan: RoutePlan, now: Long, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text("${decimal(plan.distanceMeters / 1000.0)} км", fontSize = 23.sp, fontWeight = FontWeight.Bold)
            Text("Финиш ${time(plan.finishTime, now)}")
            Text("До финиша ${minutes(max(0, plan.finishTime - now))}", style = MaterialTheme.typography.bodySmall)
            Text("Опоздания: ${plan.lateCount}", color = if (plan.lateCount > 0) Red else Green)
            if (plan.totalLateSeconds > 0) Text("Σ ${minutes(plan.totalLateSeconds)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun OrderCard(position: Int, order: DeliveryOrder, stop: RouteStop?, state: AppUiState, actions: UiActions, edit: () -> Unit) {
    var pinText by remember(order.id, order.pinnedPosition) { mutableStateOf(order.pinnedPosition?.toString() ?: "") }
    val critical = stop?.slackSeconds?.let { it < 300 } ?: (order.deadline - state.now < 600)
    val original = state.orders.filterNot { it.completed }.sortedBy { it.originalPosition }
    val originalIndex = original.indexOfFirst { it.id == order.id }
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${position.toString().padStart(2, '0')}  ·  № ${order.id}", color = Green, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (order.pinnedPosition != null) Text("Закреплён · ${order.pinnedPosition}", color = Amber, style = MaterialTheme.typography.labelMedium)
        }
        Text(order.address, style = MaterialTheme.typography.titleMedium)
        Text("Окно ${time(order.windowStart, state.now)}–${time(order.deadline, state.now)} · ${decimal(order.weightKg)} кг · ${money(order.rewardRub)} ₽")
        Text("До дедлайна ${minutes(order.deadline - state.now)}", color = if (order.deadline < state.now) Red else if (critical) Amber else MaterialTheme.colorScheme.onSurface)
        if (stop != null) {
            Text("Прибытие ${time(stop.arrival, state.now)} · завершение ${time(stop.completion, state.now)}", fontWeight = FontWeight.Medium)
            if (stop.serviceStart > stop.arrival) Text("Ожидание окна: ${minutes(stop.serviceStart - stop.arrival)}", style = MaterialTheme.typography.bodySmall)
            Text(
                if (stop.lateSeconds > 0) "Опоздание ${minutes(stop.lateSeconds)}" else "Запас ${minutes(stop.slackSeconds)}",
                color = if (stop.lateSeconds > 0) Red else if (critical) Amber else Green,
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            Text("Прибытие — · нет расчёта по дорогам", color = Amber)
        }
        Text("Обслуживание ${minutes(order.serviceSeconds)}: парковка, подъезд, лифт, передача", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Исходное место ${originalIndex + 1}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { actions.moveOriginal(order.id, -1) }, enabled = !state.busy && originalIndex > 0) { Text("↑ Выше") }
            TextButton(onClick = { actions.moveOriginal(order.id, 1) }, enabled = !state.busy && originalIndex >= 0 && originalIndex < original.lastIndex) { Text("↓ Ниже") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = pinText, onValueChange = { pinText = it.filter(Char::isDigit).take(2) },
                label = { Text("Место") }, modifier = Modifier.width(92.dp), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            TextButton(onClick = { actions.pin(order.id, pinText.toIntOrNull()) }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text(if (pinText.isBlank()) "Снять фиксацию" else "Закрепить") }
            TextButton(onClick = edit, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Изменить") }
        }
        Button(onClick = { actions.complete(order.id) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Доставлено · пересчитать") }
    }
}

@Composable
private fun ImportScreen(state: AppUiState, actions: UiActions) {
    Title("Проверка заказов", "OCR работает на устройстве · кириллица")
    Text("Загрузите несколько скриншотов «Мои» через Photo Picker или «Поделиться». Кадры и сырой текст не сохраняются. Имена, телефоны, квартиры и коды не нужны.")
    Field(state.settings.capturedAt, "Когда получены скриншоты · МСК", { actions.updateSetting(SettingField.CAPTURED_AT, it) }, "ГГГГ-ММ-ДД ЧЧ:мм")
    Notice("Для обратного таймера срок = момент получения информации + остаток. Укажите время до импорта; у старых скриншотов срок может быть неверным. Проверьте абсолютную дату и время каждой доставки.", Amber)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = actions::pickImages, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Скриншоты") }
        OutlinedButton(onClick = actions::addManualOrder, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Вручную") }
    }
    state.importWarnings.distinct().forEach { Notice(it, Amber) }
    if (state.drafts.isNotEmpty()) {
        Text("Проверьте ${state.drafts.size} карточек", style = MaterialTheme.typography.titleLarge)
        Text("Время — московское, с датой. Дедлайн означает завершение передачи заказа. Проверьте порядок карточек по официальному приложению.", style = MaterialTheme.typography.bodySmall)
    }
    state.drafts.forEachIndexed { index, draft ->
        DraftCard(index, draft, state.busy, actions)
    }
    if (state.drafts.isNotEmpty()) {
        Button(
            onClick = actions::acceptDrafts,
            enabled = !state.busy && state.drafts.all { it.assignedConfirmed },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Подтвердить проверенные заказы") }
        Text("Заказы «Доступные» не считаются назначенными. Для каждой карточки подтвердите, что заказ находится в «Мои».", style = MaterialTheme.typography.bodySmall)
    }
    HorizontalDivider()
    OutlinedButton(onClick = actions::pickMatrix, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Импорт дорожной матрицы JSON") }
    Text("Матрица должна содержать все заказы и даркстор, автомобильное время и расстояния в обоих направлениях. Формат описан в README проекта.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun DraftCard(index: Int, draft: OrderDraft, busy: Boolean, actions: UiActions) {
    fun update(field: DraftField, value: String) = actions.updateDraft(index, field, value)
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Карточка ${index + 1}", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { actions.removeDraft(index) }, enabled = !busy) { Text("Удалить") }
        }
        draft.warnings.forEach { Text(it, color = Amber, style = MaterialTheme.typography.bodySmall) }
        Field(draft.id, "Номер заказа", { update(DraftField.ID, it) })
        Field(draft.address, "Адрес · без квартиры и кода", { update(DraftField.ADDRESS, it) })
        Field(draft.windowStart, "Начало окна · МСК", { update(DraftField.WINDOW_START, it) }, "ГГГГ-ММ-ДД ЧЧ:мм")
        Field(draft.deadline, "Завершить до · МСК", { update(DraftField.DEADLINE, it) }, "ГГГГ-ММ-ДД ЧЧ:мм")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(draft.rewardRub, "Оплата · ₽", { update(DraftField.REWARD, it) }, numeric = true, modifier = Modifier.weight(1f))
            Field(draft.weightKg, "Вес · кг", { update(DraftField.WEIGHT, it) }, numeric = true, modifier = Modifier.weight(1f))
        }
        Field(draft.serviceMinutes, "Обслуживание · минут", { update(DraftField.SERVICE_MINUTES, it) }, "Парковка + подъезд + лифт + передача", numeric = true)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(draft.latitude, "Широта", { update(DraftField.LATITUDE, it) }, numeric = true, modifier = Modifier.weight(1f))
            Field(draft.longitude, "Долгота", { update(DraftField.LONGITUDE, it) }, numeric = true, modifier = Modifier.weight(1f))
        }
        Text("Координаты нужны для API дорог. Для импорта готовой матрицы достаточно совпадающих ID.", style = MaterialTheme.typography.bodySmall)
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                value = draft.assignedConfirmed, role = Role.Checkbox,
                onValueChange = { actions.confirmAssigned(index, it) },
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = draft.assignedConfirmed, onCheckedChange = null)
            Text("Проверено: заказ назначен мне во вкладке «Мои»", modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ShiftScreen(state: AppUiState, actions: UiActions) {
    val completed = state.orders.filter { it.completed }
    val gross = completed.sumOf { it.rewardRub }
    val hours = max(0L, state.now - state.shiftStartedAt) / 3600.0
    val totalKm = state.settings.totalKm.number()
    val emptyKm = state.settings.emptyKm.number()
    val consumption = state.settings.fuelLitersPer100Km.number()
    val fuelPrice = state.settings.fuelPriceRub.number()
    val fuel = if (totalKm != null && consumption != null && fuelPrice != null && totalKm >= 0 && consumption >= 0 && fuelPrice >= 0) totalKm * consumption * fuelPrice / 100.0 else null
    Title("Экономика смены", "С ${Day.format(Instant.ofEpochSecond(state.shiftStartedAt))}")
    Panel {
        Text("Начислено за выполненные", style = MaterialTheme.typography.labelLarge)
        Text("${money(gross)} ₽", style = MaterialTheme.typography.displaySmall, color = Green, fontWeight = FontWeight.Bold)
        Text("${completed.size} доставок · ${if (hours >= 1.0 / 60) decimal(completed.size / hours) else "—"} заказов/час")
        Text("${decimal(hours)} ч в приложении за эту смену", style = MaterialTheme.typography.bodySmall)
    }
    Panel {
        Text("Пробег по одометру", style = MaterialTheme.typography.titleMedium)
        Field(state.settings.totalKm, "Общий пробег смены · км", { actions.updateSetting(SettingField.TOTAL_KM, it) }, numeric = true)
        Field(state.settings.emptyKm, "Холостой пробег · км", { actions.updateSetting(SettingField.EMPTY_KM, it) }, numeric = true)
        Field(state.settings.fuelLitersPer100Km, "Расход · л/100 км", { actions.updateSetting(SettingField.FUEL_LITERS_PER_100KM, it) }, numeric = true)
        Field(state.settings.fuelPriceRub, "Цена топлива · ₽/л", { actions.updateSetting(SettingField.FUEL_PRICE_RUB, it) }, numeric = true)
        if (emptyKm != null && totalKm != null && emptyKm > totalKm) Text("Холостой пробег больше общего. Проверьте ввод.", color = Red)
        Text("Топливо: ${fuel?.let { "${money(it)} ₽" } ?: "нужен фактический пробег"}")
        Text("После топлива: ${fuel?.let { "${money(gross - it)} ₽" } ?: "—"}", style = MaterialTheme.typography.titleLarge, color = Green)
        Text("Это оценка чистого дохода до налогов, ремонта и амортизации. Демо-пробег не используется. Рост заработка не гарантирован.", style = MaterialTheme.typography.bodySmall)
    }
    if (completed.isNotEmpty()) {
        Text("Выполненные заказы", style = MaterialTheme.typography.titleMedium)
        completed.forEach { order -> Text("№ ${order.id} · ${money(order.rewardRub)} ₽") }
    }
}

@Composable
private fun SettingsScreen(state: AppUiState, actions: UiActions) {
    val settings = state.settings
    fun change(field: SettingField, value: String) = actions.updateSetting(field, value)
    Title("Условия маршрута", "Настройте перед началом смены")
    Panel {
        Text("Даркстор", style = MaterialTheme.typography.titleLarge)
        Field(settings.depotAddress, "Адрес даркстора", { change(SettingField.DEPOT_ADDRESS, it) })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(settings.depotLatitude, "Широта", { change(SettingField.DEPOT_LATITUDE, it) }, numeric = true, modifier = Modifier.weight(1f))
            Field(settings.depotLongitude, "Долгота", { change(SettingField.DEPOT_LONGITUDE, it) }, numeric = true, modifier = Modifier.weight(1f))
        }
        Toggle("Вернуться в даркстор", "Учитывать обратную дорогу в пробеге и времени финиша", settings.returnToDepot) { change(SettingField.RETURN_TO_DEPOT, it.toString()) }
        Toggle("Разрешена ранняя передача", "Включайте только если доставка до начала окна разрешена", settings.allowEarlyService) { change(SettingField.ALLOW_EARLY_SERVICE, it.toString()) }
        Toggle("Соблюдать исходный порядок", "Используйте при запрете компании менять последовательность", settings.preserveCompanyOrder) { change(SettingField.PRESERVE_COMPANY_ORDER, it.toString()) }
        Toggle("Голосовые предупреждения", "Предупреждать о малом запасе времени при пересчёте", settings.voiceEnabled) { change(SettingField.VOICE_ENABLED, it.toString()) }
    }
    Panel {
        Text("Дорожная маршрутизация", style = MaterialTheme.typography.titleLarge)
        Text("GraphHopper Matrix API: автомобильные расстояния и время без учёта пробок. Для каждой точки нужны проверенные координаты. Запрос может быть платным по вашему тарифу.")
        OutlinedTextField(
            value = settings.apiKey, onValueChange = { change(SettingField.API_KEY, it) },
            label = { Text("API-ключ · только на время сеанса") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        Text("Нажимая кнопку, вы разрешаете отправить координаты точек стороннему сервису GraphHopper. Имена, телефоны, квартиры, коды и изображения не отправляются. Ключ не сохраняется в базе.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = actions::fetchMatrix, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Получить дорожную матрицу") }
        OutlinedButton(onClick = actions::pickMatrix, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Загрузить матрицу JSON") }
        Text("Без дорожных данных приложение не подставляет расстояния по прямой и не оценивает пробки.", style = MaterialTheme.typography.bodySmall)
    }
    Notice("Работайте с закреплениями и изменением порядка в рамках правил компании. Официальное приложение не изменяется. Пользуйтесь экраном только на безопасной остановке.", Amber)
    OutlinedButton(onClick = actions::loadDemo, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Загрузить демо · 10 заказов") }
    TextButton(onClick = actions::clearData, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Удалить локальные данные смены") }
}

@Composable
private fun Title(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Panel(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun Notice(message: String, color: Color) {
    Box(Modifier.fillMaxWidth().background(color.copy(alpha = 0.10f), RoundedCornerShape(14.dp)).padding(13.dp)) {
        Text(message, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Field(
    value: String,
    label: String,
    onChange: (String) -> Unit,
    hint: String? = null,
    numeric: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val supporting: (@Composable () -> Unit)? = if (hint != null) ({ Text(hint) }) else null
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        supportingText = supporting,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text),
        modifier = modifier.fillMaxWidth(), singleLine = true,
    )
}

@Composable
private fun Toggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

private fun time(seconds: Long, now: Long): String {
    val instant = Instant.ofEpochSecond(seconds)
    return if (instant.atZone(Moscow).toLocalDate() == Instant.ofEpochSecond(now).atZone(Moscow).toLocalDate()) Clock.format(instant) else Day.format(instant)
}
private fun minutes(seconds: Long): String = "${if (seconds < 0) "−" else ""}${(abs(seconds) + 59) / 60} мин"
private fun decimal(value: Double): String = String.format(Locale("ru"), "%.1f", value)
private fun money(value: Double): String = String.format(Locale("ru"), "%.0f", value)
private fun String.number(): Double? = replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
