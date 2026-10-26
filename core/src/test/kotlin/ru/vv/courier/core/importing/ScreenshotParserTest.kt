package ru.vv.courier.core.importing

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ScreenshotParserTest {
    private val parser = ScreenshotParser()
    private val captured = Instant.parse("2026-10-25T09:00:00Z").epochSecond // 12:00 Moscow

    @Test fun `two orders at one address remain independent and private fields are dropped`() {
        val result = parser.parse("""
            Мои
            Заказ №123456
            Адрес: Москва, ул. Учебная, 10, кв. 82
            12:00–14:00
            Вознаграждение 340 ₽
            Вес 4,5 кг
            Клиент Иван Иванов
            Телефон +7 900 111 22 33
            Домофон 1234
            Заказ №123457
            Москва, ул. Учебная, 10
            12:30–13:30
            280 руб.
            2 кг
        """.trimIndent(), captured)
        assertEquals(listOf("123456", "123457"), result.candidates.map { it.id })
        assertEquals(2, result.candidates.size)
        assertEquals("Москва, ул. Учебная, 10", result.candidates.first().address)
        assertEquals(captured + 7200, result.candidates.first().deadline)
        assertEquals(340.0, result.candidates.first().rewardRub!!, 0.001)
        assertEquals(4.5, result.candidates.first().weightKg!!, 0.001)
        assertFalse(result.toString().contains("Иван"))
        assertFalse(result.toString().contains("111 22 33"))
        assertFalse(result.toString().contains("82"))
    }

    @Test fun `available-only is rejected and ambiguous header requires confirmation`() {
        val card = "Заказ #778899\nул. Учебная, 1\n12:00-13:00"
        assertTrue(parser.parse("Доступные\n$card", captured).candidates.isEmpty())
        val ambiguous = parser.parse("Доступные Мои\n$card", captured)
        assertEquals(1, ambiguous.candidates.size)
        assertTrue(ambiguous.warnings.any { it.contains("активную вкладку") })
        assertTrue(ambiguous.candidates.single().warnings.any { it.contains("назначение") })
    }

    @Test fun `countdown uses information timestamp and warns about old screenshots`() {
        val text = "Мои\nЗаказ 987654\nул. Тестовая, 12\nОсталось 00:25:30"
        val result = parser.parse(text, captured)
        assertEquals(captured + 1530, result.candidates.single().deadline)
        assertNull(result.candidates.single().windowStart)
        assertTrue(result.candidates.single().warnings.any { it.contains("Старый скриншот") })
        val older = parser.parse(text, captured - 600)
        assertEquals(captured + 930, older.candidates.single().deadline)
    }

    @Test fun `minutes countdown and Russian duration are supported`() {
        val base = "Мои\nЗаказ 987654\nул. Тестовая, 12\n"
        assertEquals(captured + 1530, parser.parse(base + "Осталось 25:30", captured).candidates.single().deadline)
        assertEquals(captured + 5400, parser.parse(base + "Осталось 1 ч 30 мин", captured).candidates.single().deadline)
    }

    @Test fun `merge deduplicates by id and preserves conflicts visibly`() {
        val first = parser.parse("Мои\nЗаказ 123456\nул. Учебная, 1\n12:00-13:00\n300 ₽", captured)
        val duplicate = parser.parse("Мои\nЗаказ 123456\nул. Учебная, 1\n12:00-13:00\n300 ₽", captured)
        val changed = parser.parse("Мои\nЗаказ 123456\nул. Учебная, 2\n12:00-14:00\n350 ₽", captured)
        assertEquals(1, parser.merge(listOf(first, duplicate)).candidates.size)
        val merged = parser.merge(listOf(first, changed))
        assertEquals(first.candidates.single().address, merged.candidates.single().address)
        assertEquals(first.candidates.single().deadline, merged.candidates.single().deadline)
        assertTrue(merged.warnings.any { it.contains("несовпадение") && it.contains("адрес") && it.contains("срок") })
        assertTrue(merged.candidates.single().warnings.any { it.contains("несовпадение") })
    }

    @Test fun `midnight window resolves around Moscow capture date`() {
        val atMidnight = Instant.parse("2026-10-24T21:10:00Z").epochSecond // 25 October 00:10
        val candidate = parser.parse("Мои\nЗаказ 123456\nул. Учебная, 1\n23:30–00:30", atMidnight).candidates.single()
        assertEquals(Instant.parse("2026-10-24T20:30:00Z").epochSecond, candidate.windowStart)
        assertEquals(Instant.parse("2026-10-24T21:30:00Z").epochSecond, candidate.deadline)
    }

    @Test fun `past absolute deadlines do not move silently to tomorrow`() {
        val candidate = parser.parse("Мои\nЗаказ 123456\nул. Учебная, 1\nДоставить до 11:30", captured).candidates.single()
        assertEquals(captured - 1800, candidate.deadline)
        assertTrue(candidate.warnings.any { it.contains("прошёл") })
    }

    @Test fun `explicit date and decimal weight do not mix`() {
        val candidate = parser.parse("Мои\nЗаказ 123456\nул. Учебная, 1\nДата 26.10.2026\n12:00–13:00\n3.5 кг", captured).candidates.single()
        assertEquals(captured + 25 * 3600, candidate.deadline)
        assertEquals(3.5, candidate.weightKg!!, 0.001)
        val header = parser.parse("Мои\n26.10.2026\nЗаказ 123456\nул. Учебная, 1\n12:00–13:00", captured).candidates.single()
        assertEquals(captured + 25 * 3600, header.deadline)
    }

    @Test fun `missing order identifiers require manual input and never deduplicate by address`() {
        val batch = parser.parse("Адрес: ул. Учебная, 1\n12:00-13:00", captured)
        assertEquals("", batch.candidates.single().id)
        assertTrue(batch.candidates.single().warnings.any { it.contains("Номер заказа") })
        assertEquals(2, parser.merge(listOf(batch, batch)).candidates.size)
    }

    @Test fun `invalid time is returned for correction instead of crashing`() {
        val result = parser.parse("Мои\nЗаказ 123456\nул. Учебная, 1\n29:30–29:50", captured)
        assertEquals(1, result.candidates.size)
        assertNull(result.candidates.single().deadline)
        assertTrue(result.candidates.single().warnings.any { it.contains("Срок доставки не распознан") })
    }

    @Test fun `name or access code appended to a street line is not retained`() {
        val candidate = parser.parse("Мои\nЗаказ 123456\nМосква, ул. Учебная, 10 Иван Иванов\nДомофон #9090\n12:00-13:00", captured).candidates.single()
        assertEquals("123456", candidate.id)
        assertEquals("Москва, ул. Учебная, 10", candidate.address)
        assertFalse(candidate.toString().contains("Иван"))
        assertFalse(candidate.toString().contains("9090"))
        val withoutComma = parser.parse("Мои\nЗаказ 123456\nул. Учебная 10 Иван Иванов\n12:00-13:00", captured).candidates.single()
        assertEquals("ул. Учебная 10", withoutComma.address)
    }

    @Test fun `phone and name appended without commas are never persisted`() {
        val candidate = parser.parse("Мои\nЗаказ 123456\nул. Учебная 10 Иван Иванов 9001234567\n12:00-13:00", captured).candidates.single()
        assertEquals("ул. Учебная 10", candidate.address)
        assertFalse(candidate.toString().contains("Иван"))
        assertFalse(candidate.toString().contains("9001234567"))
        val withNoteComma = parser.parse("Мои\nЗаказ 123456\nул. Учебная 10 Иван Иванов, 9001234567\n12:00-13:00", captured).candidates.single()
        assertEquals("ул. Учебная 10", withNoteComma.address)
    }

    @Test fun `numbered streets and building suffix keep complete street and house`() {
        val base = "Мои\nЗаказ 123456\n"
        assertEquals("ул. 40 лет Октября 10 корпус 2", parser.parse(base + "ул. 40 лет Октября 10 корпус 2\n12:00-13:00", captured).candidates.single().address)
        assertEquals("Тихорецкий бульвар 1", parser.parse(base + "Тихорецкий бульвар 1\n12:00-13:00", captured).candidates.single().address)
        assertEquals("ул. Верхние Поля 32к1", parser.parse(base + "ул. Верхние Поля 32к1\n12:00-13:00", captured).candidates.single().address)
        assertEquals("1-я Вольская улица 12", parser.parse(base + "1-я Вольская улица 12\n12:00-13:00", captured).candidates.single().address)
    }

    @Test fun `decimal reward never changes delivery date`() {
        val candidate = parser.parse("Мои\nЗаказ 123456\nул. Учебная, 10\n12:00-13:00\n20.10 ₽", captured).candidates.single()
        assertEquals(captured + 3600, candidate.deadline)
        assertEquals(20.10, candidate.rewardRub!!, 0.001)
    }

    @Test fun `conflicting countdown and absolute window require correction`() {
        val candidate = parser.parse("Мои\nЗаказ 123456\nул. Учебная, 10\n12:00-13:00\nОсталось 25:30", captured).candidates.single()
        assertEquals(captured + 3600, candidate.deadline)
        assertTrue(candidate.warnings.any { it.contains("Несовпадение") && it.contains("таймер") })
    }
}
