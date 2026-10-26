package ru.vv.courier.core.importing

import org.junit.Assert.assertEquals
import org.junit.Test

class RoutePrivacyTest {
    @Test fun removesApartmentAndAccessSuffixesFromManualAddresses() {
        listOf("кв 15", "кв. 15", "квартира 15", "код 1234", "код доступа 1234", "апарт. 10", "подъезд 3", "этаж 5", "домофон 1234").forEach { suffix ->
            assertEquals("улица Учебная, 10", RoutePrivacy.sanitizeAddress("улица Учебная, 10, $suffix"))
        }
    }

    @Test fun keepsBuildingAndStreetNamesContainingPrivateWordFragments() {
        assertEquals("улица Кварцевая, 10 корпус 2", RoutePrivacy.sanitizeAddress("улица Кварцевая, 10 корпус 2"))
        assertEquals("улица Учебная, 10", RoutePrivacy.sanitizeAddress("улица Учебная, 10\nкод 1234\nпримечание"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPhoneNumbersInManualAddressInsteadOfSavingThem() {
        RoutePrivacy.sanitizeAddress("улица Учебная, 10, +7 999 000-00-00")
    }
}
