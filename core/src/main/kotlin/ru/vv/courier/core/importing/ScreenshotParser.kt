package ru.vv.courier.core.importing

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Only these route fields may leave the short-lived OCR buffer. */
data class ImportCandidate(
    val id: String,
    val address: String,
    val windowStart: Long?,
    val deadline: Long?,
    val rewardRub: Double?,
    val weightKg: Double?,
    val capturedAt: Long,
    val source: String,
    val warnings: List<String> = emptyList(),
)

data class ImportBatch(val candidates: List<ImportCandidate>, val warnings: List<String>)

/** Conservative Russian card parser. Raw OCR text must never be persisted or logged. */
class ScreenshotParser {
    private val moscow = ZoneId.of("Europe/Moscow")
    private val orderMarker = Regex(
        "(?:(?:номер\\s+заказа|№\\s*заказа)\\s*:?\\s*|заказ(?:а)?\\s*(?:№|#|N)?\\s*|(?m:^[\\t ]*[№#]\\s*))([A-ZА-Я0-9][A-ZА-Я0-9-]{2,})",
        RegexOption.IGNORE_CASE,
    )
    private val timeWindow = Regex("(?<!\\d)([0-2]?\\d):([0-5]\\d)\\s*[-–—−]\\s*([0-2]?\\d):([0-5]\\d)(?!\\d)")
    private val deadlineTime = Regex("(?:доставить\\s+до|доставка\\s+до|срок\\s*:?|до)\\s*([0-2]?\\d):([0-5]\\d)", RegexOption.IGNORE_CASE)
    private val datePattern = Regex("(?<!\\d)([0-3]\\d)\\.([01]\\d)(?:\\.(20\\d{2}))?(?!\\d|\\s*кг)")
    private val sensitiveLine = Regex("(?:клиент|получатель|телефон|контакт|домофон|код\\s*(?:доступа|подъезда)?|квартира|кв\\.|подъезд|этаж|\\+7|\\b8\\s*\\(?9\\d{2})", RegexOption.IGNORE_CASE)
    private val privateSuffix = Regex("(?:[,;]\\s*)?(?<![А-ЯЁа-яёA-Za-z])(?:квартира\\b|кв\\.?(?=\\s|[:№#\\d])|апарт\\.?(?=\\s|[:№#\\d])|подъезд\\b|этаж\\b|домофон\\b|код\\b(?:\\s+доступа)?)\\s*[:№#-]?.*$", RegexOption.IGNORE_CASE)
    private val explicitAddress = Regex("(?:\\bул\\.|улица|улице|проспект|пр-т|просп\\.|переулок|пер\\.|шоссе|бульвар|бул\\.|проезд|набережная|наб\\.|площадь|пл\\.|аллея|квартал|мкр\\.|микрорайон|пос[её]лок|деревня)", RegexOption.IGNORE_CASE)
    private val addressLabel = Regex("^(?:адрес(?:\\s+доставки)?\\s*:?\\s*)", RegexOption.IGNORE_CASE)

    fun parse(text: String, capturedAt: Long): ImportBatch {
        val normalized = text.replace('\u00a0', ' ').replace("\r", "")
        val hasAvailable = Regex("\\bДоступные\\b", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
        val hasMine = Regex("\\bМои\\b", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
        if (hasAvailable && !hasMine) return ImportBatch(emptyList(), listOf(
            "Обнаружена вкладка «Доступные»: эти заказы не считаются назначенными. Импортируйте «Мои».",
        ))
        val source = if (hasMine && !hasAvailable) "Мои — требуется подтверждение" else "Вкладка не подтверждена"
        val globalWarnings = mutableListOf("Проверьте адреса, номера и сроки; OCR может ошибаться. Подтвердите, что каждый заказ назначен вам во вкладке «Мои».")
        if (hasAvailable && hasMine) globalWarnings += "Видны названия обеих вкладок. По тексту OCR нельзя определить активную вкладку."
        if (!hasMine) globalWarnings += "Вкладка «Мои» не распознана: назначение заказов нужно подтвердить вручную."
        val markers = orderMarker.findAll(normalized).filter { match -> match.groupValues[1].any(Char::isDigit) }.toList()
        val headerDate = markers.firstOrNull()?.let { marker ->
            readDate(normalized.substring(0, marker.range.first), Instant.ofEpochSecond(capturedAt).atZone(moscow).year)
        }
        val blocks = if (markers.isNotEmpty()) markers.mapIndexed { index, match ->
            match.groupValues[1].uppercase() to normalized.substring(match.range.first, markers.getOrNull(index + 1)?.range?.first ?: normalized.length)
        } else normalized.split(Regex("\\n\\s*\\n")).filter { findAddress(it).isNotEmpty() }.map { "" to it }
        val candidates = blocks.mapNotNull { (id, block) ->
            val address = findAddress(block)
            if (address.isBlank()) return@mapNotNull null
            val warnings = mutableListOf("Подтвердите назначение заказа, адрес и временное окно.")
            if (id.isBlank()) warnings += "Номер заказа не распознан: введите уникальный номер вручную."
            val timing = parseTiming(block, capturedAt, warnings, headerDate)
            if (timing.second == null) warnings += "Срок доставки не распознан: введите его вручную."
            val reward = Regex("(\\d[\\d ]*(?:[,.]\\d{1,2})?)\\s*(?:₽|руб(?:\\.|лей|ля)?)", RegexOption.IGNORE_CASE)
                .find(block)?.groupValues?.get(1)?.numeric()
            val weight = Regex("(\\d+(?:[,.]\\d+)?)\\s*кг", RegexOption.IGNORE_CASE)
                .find(block)?.groupValues?.get(1)?.numeric()
            ImportCandidate(id, address, timing.first, timing.second, reward, weight, capturedAt, source, warnings)
        }
        if (candidates.isEmpty()) globalWarnings += "Карточки с адресами не найдены. Добавьте или исправьте заказ вручную."
        return merge(listOf(ImportBatch(candidates, globalWarnings)))
    }

    fun merge(batches: List<ImportBatch>): ImportBatch {
        val unique = linkedMapOf<String, ImportCandidate>()
        val missingIds = mutableListOf<ImportCandidate>()
        val warnings = batches.flatMap { it.warnings }.toMutableList()
        for (candidate in batches.flatMap { it.candidates }) {
            if (candidate.id.isBlank()) {
                missingIds += candidate
                continue
            }
            val previous = unique[candidate.id]
            if (previous == null) {
                unique[candidate.id] = candidate
            } else {
                val conflicts = buildList {
                    if (normalizeAddress(previous.address) != normalizeAddress(candidate.address)) add("адрес")
                    if (previous.windowStart != candidate.windowStart) add("начало окна")
                    if (previous.deadline != candidate.deadline) add("срок")
                    if (previous.rewardRub != candidate.rewardRub) add("вознаграждение")
                    if (previous.weightKg != candidate.weightKg) add("вес")
                }
                if (conflicts.isNotEmpty()) {
                    val message = "Заказ ${candidate.id}: несовпадение (${conflicts.joinToString()}). Сохранён первый вариант, сверяйте вручную."
                    warnings += message
                    unique[candidate.id] = previous.copy(warnings = (previous.warnings + candidate.warnings + message).distinct())
                } else {
                    unique[candidate.id] = previous.copy(warnings = (previous.warnings + candidate.warnings).distinct())
                }
            }
        }
        return ImportBatch(unique.values.toList() + missingIds, warnings.distinct())
    }

    private fun parseTiming(block: String, capturedAt: Long, warnings: MutableList<String>, headerDate: LocalDate?): Pair<Long?, Long?> {
        val captured = Instant.ofEpochSecond(capturedAt).atZone(moscow)
        val explicitDate = readDate(block, captured.year) ?: headerDate
        val date = explicitDate ?: captured.toLocalDate()
        timeWindow.find(block)?.let { match ->
            val startTime = safeTime(match.groupValues[1], match.groupValues[2])
            val endTime = safeTime(match.groupValues[3], match.groupValues[4])
            if (startTime != null && endTime != null) {
                var start = date.atTime(startTime).atZone(moscow)
                var end = date.atTime(endTime).atZone(moscow)
                if (endTime < startTime) {
                    end = end.plusDays(1)
                    if (explicitDate == null && captured.toLocalTime() <= endTime) {
                        start = start.minusDays(1)
                        end = end.minusDays(1)
                    }
                    warnings += "Окно пересекает полночь; проверьте дату доставки."
                }
                if (explicitDate == null) warnings += "Дата окна принята по московской дате получения данных; проверьте её."
                if (end.toEpochSecond() < capturedAt) warnings += "Указанный срок уже прошёл на момент получения данных."
                parseCountdown(block)?.let { remaining ->
                    if (kotlin.math.abs(end.toEpochSecond() - capturedAt - remaining) > 60L) {
                        warnings += "Несовпадение: конец окна и обратный таймер дают разные сроки. Сохранено окно; проверьте оба значения вручную."
                    }
                }
                return start.toEpochSecond() to end.toEpochSecond()
            }
        }
        val countdown = parseCountdown(block)
        if (countdown != null) {
            warnings += "Обратный отсчёт привязан к указанному времени получения скриншота. Старый скриншот с новым временем даст неверный срок."
            return null to capturedAt + countdown
        }
        deadlineTime.find(block)?.let { match ->
            val time = safeTime(match.groupValues[1], match.groupValues[2])
            if (time != null) {
                val deadline = date.atTime(time).atZone(moscow).toEpochSecond()
                if (deadline < capturedAt) warnings += "Срок уже прошёл; автоматически на завтра не переносится."
                if (explicitDate == null) warnings += "Дата срока принята по московской дате получения данных; проверьте её."
                return null to deadline
            }
        }
        return null to null
    }

    private fun parseCountdown(block: String): Long? {
        val prefix = "(?:осталось|таймер|до\\s+опоздания|времени\\s+осталось)\\s*:?\\s*"
        val hms = Regex(prefix + "(\\d{1,2}):([0-5]\\d):([0-5]\\d)", RegexOption.IGNORE_CASE).find(block)
        if (hms != null) return hms.groupValues[1].toLong() * 3600 + hms.groupValues[2].toLong() * 60 + hms.groupValues[3].toLong()
        val standaloneHms = Regex("(?m)^\\s*(\\d{1,2}):([0-5]\\d):([0-5]\\d)\\s*$").find(block)
        if (standaloneHms != null) return standaloneHms.groupValues[1].toLong() * 3600 + standaloneHms.groupValues[2].toLong() * 60 + standaloneHms.groupValues[3].toLong()
        val ms = Regex(prefix + "(\\d{1,3}):([0-5]\\d)(?![:\\d])", RegexOption.IGNORE_CASE).find(block)
        if (ms != null) return ms.groupValues[1].toLong() * 60 + ms.groupValues[2].toLong()
        val duration = Regex(prefix + "(?:(\\d{1,2})\\s*(?:ч(?:ас(?:а|ов)?)?\\.?)\\s*)?(?:(\\d{1,3})\\s*(?:мин(?:ут(?:а|ы)?)?\\.?))", RegexOption.IGNORE_CASE).find(block)
        return duration?.let { (it.groupValues[1].toLongOrNull() ?: 0) * 3600 + (it.groupValues[2].toLongOrNull() ?: 0) * 60 }
    }

    private fun findAddress(block: String): String {
        val lines = block.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        for ((index, original) in lines.withIndex()) {
            val labeled = addressLabel.containsMatchIn(original)
            val line = original.replace(addressLabel, "").replace(privateSuffix, "").trim().trimEnd(',', ';')
            if (line.isBlank() || sensitiveLine.containsMatchIn(line)) continue
            if (orderMarker.containsMatchIn(line) || timeWindow.containsMatchIn(line)) continue
            val street = explicitAddress.containsMatchIn(line)
            val generic = labeled || Regex("^[А-ЯЁа-яё][А-ЯЁа-яё .-]{3,},\\s*\\d+[А-ЯЁа-яёA-Za-z]?(?:[ /кКсС]\\d+)?(?:\\s|$)").containsMatchIn(line)
            if (!street && !generic) continue
            if (Regex("\\d").containsMatchIn(line)) {
                val address = addressThroughHouse(line)
                if (address.isNotBlank()) return address.take(240)
            }
            // Some cards wrap the house number onto its own following line.
            val next = lines.getOrNull(index + 1)
            if (next != null && Regex("^(?:д(?:ом)?\\.?\\s*)?\\d+[А-ЯЁа-яёA-Za-z]?(?:\\s*(?:к(?:орпус)?|стр(?:оение)?)\\.?\\s*\\d+)?$").matches(next)) return "$line, $next".take(240)
        }
        return ""
    }

    private fun safeTime(hour: String, minute: String): LocalTime? = runCatching { LocalTime.of(hour.toInt(), minute.toInt()) }.getOrNull()
    private fun readDate(text: String, year: Int): LocalDate? {
        for (line in text.lineSequence()) {
            // A decimal reward or weight can resemble dd.MM; they are never dates.
            if (Regex("(?:₽|руб|кг)", RegexOption.IGNORE_CASE).containsMatchIn(line)) continue
            for (match in datePattern.findAll(line)) {
                val date = runCatching {
                    LocalDate.of(match.groupValues[3].toIntOrNull() ?: year, match.groupValues[2].toInt(), match.groupValues[1].toInt())
                }.getOrNull()
                if (date != null) return date
            }
        }
        return null
    }
    private fun addressThroughHouse(value: String): String {
        // Restrict the stored field to street + house/building. A client name or note after
        // the house is not routing data, even if OCR put it on the same line.
        val houseAfterComma = Regex("^(.+?,\\s*(?:д(?:ом)?\\.?\\s*)?\\d+[А-ЯЁа-яёA-Za-z]?(?:/\\d+[А-ЯЁа-яёA-Za-z]?)?(?:\\s*,?\\s*(?:корп(?:ус)?\\.?|к\\.?|стр(?:оение)?\\.?|с\\.?)\\s*\\d+[А-ЯЁа-яёA-Za-z]?){0,2})(?=\\s|,|;|$)", RegexOption.IGNORE_CASE)
        val commaAddress = houseAfterComma.find(value)?.groupValues?.get(1)?.trim()
        // Without a comma, stop at the first plausible house token after the
        // street name. Taking the last number can accidentally retain a customer
        // name/phone or access note that OCR joined onto the same line.
        val streetType = explicitAddress.find(value)
        val prefixName = streetType?.let { value.substring(0, it.range.first).replace(Regex("(?:Москва|г\\.|[,;])", RegexOption.IGNORE_CASE), "").trim() }.orEmpty()
        val streetStart = if (prefixName.isNotEmpty()) 0 else streetType?.range?.last?.plus(1) ?: 0
        val house = Regex("(?<![А-ЯЁа-яёA-Za-z0-9])\\d{1,4}[А-ЯЁа-яёA-Za-z]?(?:/\\d{1,4}[А-ЯЁа-яёA-Za-z]?)?(?:[кКсС]\\d{1,3}){0,2}(?=\\s|,|;|$)")
            .findAll(value).firstOrNull { token ->
                token.range.first >= streetStart && value.substring(streetStart, token.range.first).any(Char::isLetter)
            } ?: return commaAddress.orEmpty()
        val buildingSuffix = Regex("^(?:\\s*,?\\s*(?:корп(?:ус)?\\.?|к\\.?|стр(?:оение)?\\.?|с\\.?)\\s*\\d+[А-ЯЁа-яёA-Za-z]?){1,2}(?=\\s|,|;|$)", RegexOption.IGNORE_CASE)
            .find(value.substring(house.range.last + 1))?.value.orEmpty()
        val plainAddress = (value.substring(0, house.range.last + 1) + buildingSuffix).trim()
        return listOfNotNull(commaAddress, plainAddress).minBy { it.length }
    }
    private fun String.numeric(): Double? = replace(" ", "").replace(',', '.').toDoubleOrNull()
    private fun normalizeAddress(value: String): String = value.lowercase().replace(Regex("[\\s,.]+"), " ").trim()
}
