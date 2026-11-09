package com.pennywiseai.tracker.data.repository

import com.pennywiseai.tracker.utils.TokenUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #959: a fresh chat must always fit; only a long conversation runs out of room. */
class ChatPromptBudgetTest {

    private fun prompt(
        expense: List<String>,
        income: List<String>,
        accounts: List<String>,
        customInstructions: String = ""
    ) =
        LlmRepository.composeSystemPrompt(
            currentDate = "2026-10-21",
            monthLine = "₹12,345 spent, ₹50,000 income, 58 transactions",
            expenseCategories = expense,
            incomeCategories = income,
            accounts = accounts,
            customInstructions = customInstructions
        )

    @Test
    fun `budget leaves headroom inside the model's 4096-token context`() {
        assertTrue(TokenUtils.CHAT_TOKEN_BUDGET < TokenUtils.MODEL_CONTEXT_TOKENS)
        assertTrue(TokenUtils.CHAT_TOKEN_BUDGET > 1200)
    }

    @Test
    fun `short lists are listed in full`() {
        val p = prompt(listOf("Food & Dining", "Groceries"), listOf("Salary"), listOf("Test Bank 1111"))
        assertTrue(p.contains("Expense categories: Food & Dining, Groceries\n"))
        assertTrue(p.contains("Income categories: Salary\n"))
        assertTrue(p.contains("Accounts: Test Bank 1111\n"))
        assertFalse(p.contains(" more\n"))
    }

    @Test
    fun `no accounts reads as none yet`() {
        assertTrue(prompt(emptyList(), emptyList(), emptyList()).contains("Accounts: none yet\n"))
    }

    @Test
    fun `long lists are capped and the rest counted`() {
        val names = (1..100).map { "Category number $it" }
        val listed = LlmRepository.promptList(names, maxChars = 200)
        assertTrue(listed.length <= 200 + ", and 100 more".length)
        assertTrue(listed.startsWith("Category number 1, Category number 2, "))
        val shown = listed.split(", ").size - 1
        assertTrue(listed.endsWith(", and ${100 - shown} more"))
    }

    @Test
    fun `a name longer than the cap is counted, not listed`() {
        assertEquals("and 1 more", LlmRepository.promptList(listOf("x".repeat(500)), maxChars = 100))
    }

    @Test
    fun `fresh chat fits however many categories and accounts the user has`() {
        val longName = "A Rather Long User Defined Category Name"
        val p = prompt(
            expense = (1..300).map { "$longName $it" },
            income = (1..100).map { "$longName income $it" },
            accounts = (1..200).map { "Joint Household Savings Account Number $it" }
        )
        // The prompt is a bounded slice of the budget, leaving room to actually chat.
        val promptTokens = TokenUtils.estimateTokens(p)
        assertTrue(
            "prompt uses $promptTokens of ${TokenUtils.CHAT_TOKEN_BUDGET} chat tokens",
            promptTokens < TokenUtils.CHAT_TOKEN_BUDGET
        )
        assertTrue(TokenUtils.fitsChatBudget(listOf(p), "how much did I spend on groceries this month?"))
    }

    @Test
    fun `a genuinely long conversation runs out of room`() {
        val p = prompt(listOf("Groceries"), listOf("Salary"), emptyList())
        val turn = "x".repeat(400) // ~100 tokens a message
        val history = listOf(p) + List(40) { turn }
        assertFalse(TokenUtils.fitsChatBudget(history, "one more question"))
    }

    @Test
    fun `custom instructions appended and fit within budget`() {
        val custom = "Always respond in Spanish. Use INR currency. Never suggest investing."
        val p = prompt(listOf("Groceries"), listOf("Salary"), listOf("Test Bank 1111"), customInstructions = custom)
        assertTrue(p.contains("--- Additional user instructions ---"))
        assertTrue(p.contains(custom))
        // With Qwen 1792 token chat budget, prompt should fit comfortably
        assertTrue(TokenUtils.estimateTokens(p) < TokenUtils.CHAT_TOKEN_BUDGET / 2)
        assertTrue(TokenUtils.fitsChatBudget(listOf(p), "cómo va mi presupuesto este mes?"))
    }

    @Test
    fun `empty custom instructions not appended`() {
        val p = prompt(listOf("Groceries"), listOf("Salary"), emptyList(), customInstructions = "")
        assertFalse(p.contains("--- Additional user instructions ---"))
        assertFalse(p.contains("Additional user instructions"))
    }

    @Test
    fun `system prompt additions appended with semantic block`() {
        val additions = "Always respond in first person. Be concise."
        val p = LlmRepository.composeSystemPrompt(
            currentDate = "2026-10-21",
            monthLine = "₹12,345 spent, ₹50,000 income, 58 transactions",
            expenseCategories = listOf("Groceries"),
            incomeCategories = listOf("Salary"),
            accounts = listOf("Test Bank 1111"),
            customInstructions = "",
            systemPromptAdditions = additions
        )
        assertTrue(p.contains("--- System prompt additions (semantic) ---"))
        assertTrue(p.contains(additions))
        // Should fit within budget
        assertTrue(TokenUtils.estimateTokens(p) < TokenUtils.CHAT_TOKEN_BUDGET / 2)
        assertTrue(TokenUtils.fitsChatBudget(listOf(p), "cómo va mi presupuesto este mes?"))
    }

    @Test
    fun `empty system prompt additions not appended`() {
        val p = LlmRepository.composeSystemPrompt(
            currentDate = "2026-10-21",
            monthLine = "₹12,345 spent, ₹50,000 income, 58 transactions",
            expenseCategories = listOf("Groceries"),
            incomeCategories = listOf("Salary"),
            accounts = listOf("Test Bank 1111"),
            customInstructions = "",
            systemPromptAdditions = ""
        )
        assertFalse(p.contains("--- System prompt additions (semantic) ---"))
        assertFalse(p.contains("System prompt additions"))
    }

    @Test
    fun `both custom instructions and system prompt additions appended in order`() {
        val custom = "Always respond in Spanish"
        val additions = "Be concise. Use first person."
        val p = LlmRepository.composeSystemPrompt(
            currentDate = "2026-10-21",
            monthLine = "₹12,345 spent, ₹50,000 income, 58 transactions",
            expenseCategories = listOf("Groceries"),
            incomeCategories = listOf("Salary"),
            accounts = listOf("Test Bank 1111"),
            customInstructions = custom,
            systemPromptAdditions = additions
        )
        assertTrue(p.contains("--- Additional user instructions ---"))
        assertTrue(p.contains(custom))
        assertTrue(p.contains("--- System prompt additions (semantic) ---"))
        assertTrue(p.contains(additions))
        // Custom instructions should appear before system prompt additions
        val customIndex = p.indexOf("--- Additional user instructions ---")
        val additionsIndex = p.indexOf("--- System prompt additions (semantic) ---")
        assertTrue(customIndex < additionsIndex)
        // Both should fit within budget
        assertTrue(TokenUtils.estimateTokens(p) < TokenUtils.CHAT_TOKEN_BUDGET / 2)
        assertTrue(TokenUtils.fitsChatBudget(listOf(p), "cómo va mi presupuesto este mes?"))
    }
}
