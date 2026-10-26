package ru.vv.courier.core.importing

/** Strip private access details before reviewed/manual addresses enter persistent storage. */
object RoutePrivacy {
    private val phone = Regex("(?:\\+7|8)\\s*\\(?\\d{3}\\)?[\\s-]*\\d{3}[\\s-]*\\d{2}[\\s-]*\\d{2}")
    private val privateSuffix = Regex(
        "(?:[,;]\\s*)?(?<![А-ЯЁа-яёA-Za-z])(?:квартира\\b|кв\\.?(?=\\s|[:№#\\d])|апарт\\.?(?=\\s|[:№#\\d])|подъезд\\b|этаж\\b|домофон\\b|код\\b(?:\\s+доступа)?)\\s*[:№#-]?.*$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    fun sanitizeAddress(value: String): String {
        require(!phone.containsMatchIn(value)) { "Удалите телефон из адреса. Для маршрута нужны только улица и дом." }
        return value.trim().replace(privateSuffix, "").trim().trimEnd(',', ';')
    }
}
