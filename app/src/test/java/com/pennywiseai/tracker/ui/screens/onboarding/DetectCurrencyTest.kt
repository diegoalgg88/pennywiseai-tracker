package com.pennywiseai.tracker.ui.screens.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cubre la resolución de moneda por región ISO durante el onboarding.
 * La región puede venir nula, vacía, en minúsculas o ser un código que no
 * existe; en todos esos casos la función debe seguir con la siguiente región
 * y, si ninguna sirve, devolver la moneda base de la app.
 */
class DetectCurrencyTest {

    private val supported = listOf("INR", "MXN", "USD", "EUR", "JPY")

    @Test
    fun `resolves the currency of the first matching country`() {
        assertEquals("MXN", detectCurrency(listOf("MX"), supported))
    }

    @Test
    fun `accepts a lowercase region code`() {
        assertEquals("MXN", detectCurrency(listOf("mx"), supported))
    }

    @Test
    fun `skips entries without a usable region or currency`() {
        // null, vacío, región inexistente y una región sin moneda propia.
        assertEquals("USD", detectCurrency(listOf(null, "", "ZZ", "AQ", "US"), supported))
    }

    @Test
    fun `skips a country whose currency the app does not support`() {
        assertEquals("EUR", detectCurrency(listOf("GB", "DE"), supported))
    }

    @Test
    fun `falls back to the app default when no country resolves`() {
        assertEquals("INR", detectCurrency(listOf(null, "AQ"), supported))
        assertEquals("INR", detectCurrency(emptyList(), supported))
    }

    @Test
    fun `takes the first supported country in the list`() {
        assertEquals("MXN", detectCurrency(listOf("MX", "US", "DE"), supported))
    }
}
