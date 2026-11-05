package com.pennywiseai.tracker.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class ChatResponseHeuristicsTest {

    @Test
    fun resolveNarratedTool_camelCase_returnsCanonical() = runBlocking {
        assertEquals("addTransaction", ChatResponseHeuristics.resolveNarratedTool("addTransaction"))
        assertEquals("spendingByCategory", ChatResponseHeuristics.resolveNarratedTool("spendingByCategory"))
        assertEquals("monthSummary", ChatResponseHeuristics.resolveNarratedTool("monthSummary"))
    }

    @Test
    fun resolveNarratedTool_snakeCase_returnsCamelCaseCanonical() = runBlocking {
        assertEquals("addTransaction", ChatResponseHeuristics.resolveNarratedTool("add_transaction"))
        assertEquals("spendingByCategory", ChatResponseHeuristics.resolveNarratedTool("spending_by_category"))
        assertEquals("monthSummary", ChatResponseHeuristics.resolveNarratedTool("month_summary"))
        assertEquals("budgetStatus", ChatResponseHeuristics.resolveNarratedTool("budget_status"))
        assertEquals("categoryBreakdown", ChatResponseHeuristics.resolveNarratedTool("category_breakdown"))
        assertEquals("recentTransactions", ChatResponseHeuristics.resolveNarratedTool("recent_transactions"))
        assertEquals("accountBalance", ChatResponseHeuristics.resolveNarratedTool("account_balance"))
        assertEquals("spendingForecast", ChatResponseHeuristics.resolveNarratedTool("spending_forecast"))
    }

    @Test
    fun resolveNarratedTool_falsePositives_returnNull() = runBlocking {
        assertNull(ChatResponseHeuristics.resolveNarratedTool("OK"))
        assertNull(ChatResponseHeuristics.resolveNarratedTool("Hola"))
        assertNull(ChatResponseHeuristics.resolveNarratedTool("Sí"))
        assertNull(ChatResponseHeuristics.resolveNarratedTool("Yes"))
        assertNull(ChatResponseHeuristics.resolveNarratedTool(""))
        assertNull(ChatResponseHeuristics.resolveNarratedTool("hello"))
    }

    @Test
    fun claimsUnconfirmedChange_englishPatterns_returnsTrue() = runBlocking {
        assertTrue(ChatResponseHeuristics.claimsUnconfirmedChange("I've added the transaction"))
        assertTrue(ChatResponseHeuristics.claimsUnconfirmedChange("I have added it"))
        assertTrue(ChatResponseHeuristics.claimsUnconfirmedChange("It has been added"))
        assertTrue(ChatResponseHeuristics.claimsUnconfirmedChange("It's been recorded"))
        assertTrue(ChatResponseHeuristics.claimsUnconfirmedChange("It was updated"))
        assertTrue(ChatResponseHeuristics.claimsUnconfirmedChange("has been deleted"))
        assertTrue(ChatResponseHeuristics.claimsUnconfirmedChange("was removed"))
    }

    @Test
    fun claimsUnconfirmedChange_spanishPatterns_returnsTrue() = runBlocking {
        assertTrue(ChatResponseHeuristics.claimsUnconfirmedChange("he agregado el gasto"))
        assertTrue(ChatResponseHeuristics.claimsUnconfirmedChange("he añadido la transacción"))
        assertTrue(ChatResponseHeuristics.claimsUnconfirmedChange("he registrado el gasto"))
        assertTrue(ChatResponseHeuristics.claimsUnconfirmedChange("he eliminado el gasto"))
        assertTrue(ChatResponseHeuristics.claimsUnconfirmedChange("he borrado la transacción"))
        assertTrue(ChatResponseHeuristics.claimsUnconfirmedChange("he actualizado la cantidad"))
        assertTrue(ChatResponseHeuristics.claimsUnconfirmedChange("se ha agregado el gasto"))
        assertTrue(ChatResponseHeuristics.claimsUnconfirmedChange("se ha añadido la transacción"))
        assertTrue(ChatResponseHeuristics.claimsUnconfirmedChange("se ha registrado el gasto"))
        assertTrue(ChatResponseHeuristics.claimsUnconfirmedChange("se ha eliminado el gasto"))
        assertTrue(ChatResponseHeuristics.claimsUnconfirmedChange("se ha borrado la transacción"))
        assertTrue(ChatResponseHeuristics.claimsUnconfirmedChange("se ha actualizado la cantidad"))
    }

    @Test
    fun claimsUnconfirmedChange_negativeCases_returnFalse() = runBlocking {
        assertFalse(ChatResponseHeuristics.claimsUnconfirmedChange("I'll add it"))
        assertFalse(ChatResponseHeuristics.claimsUnconfirmedChange("I will record that"))
        assertFalse(ChatResponseHeuristics.claimsUnconfirmedChange("Here is the result"))
        assertFalse(ChatResponseHeuristics.claimsUnconfirmedChange("The transaction is 120"))
        assertFalse(ChatResponseHeuristics.claimsUnconfirmedChange("gasté 100 en café"))
        assertFalse(ChatResponseHeuristics.claimsUnconfirmedChange("recibí mi nómina"))
    }

    @Test
    fun isKnownTool_camelCaseAndSnakeCase_returnTrue() = runBlocking {
        assertTrue(ChatResponseHeuristics.isKnownTool("addTransaction"))
        assertTrue(ChatResponseHeuristics.isKnownTool("add_transaction"))
        assertTrue(ChatResponseHeuristics.isKnownTool("spendingByCategory"))
        assertTrue(ChatResponseHeuristics.isKnownTool("spending_by_category"))
        assertTrue(ChatResponseHeuristics.isKnownTool("monthSummary"))
        assertTrue(ChatResponseHeuristics.isKnownTool("month_summary"))
    }

    @Test
    fun isKnownTool_unknownTool_returnFalse() = runBlocking {
        assertFalse(ChatResponseHeuristics.isKnownTool("unknownTool"))
        assertFalse(ChatResponseHeuristics.isKnownTool("OK"))
        assertFalse(ChatResponseHeuristics.isKnownTool("Hola"))
    }

    @Test
    fun knownTools_containsAllTenTools() = runBlocking {
        // KNOWN_TOOLS has 20 items: 10 camelCase + 10 snake_case
        assertEquals(20, ChatResponseHeuristics.KNOWN_TOOLS.size)
        
        // Verify all 10 base tools are present in canonical (camelCase) form
        val baseTools = setOf("addTransaction", "spendingByCategory", "deleteTransaction",
            "updateTransaction", "monthSummary", "budgetStatus", "categoryBreakdown",
            "recentTransactions", "accountBalance", "spendingForecast")
        
        // The canonical names are in KNOWN_TOOLS_CAMEL
        val canonical = ChatResponseHeuristics.KNOWN_TOOLS_CAMEL.toSet()
        assertEquals(baseTools, canonical)
        
        // The snake_case variants are in KNOWN_TOOLS_SNAKE
        assertEquals(10, ChatResponseHeuristics.KNOWN_TOOLS_SNAKE.size)
    }
}