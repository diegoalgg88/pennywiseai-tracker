package com.pennywiseai.tracker.data.repository

/**
 * Pure functions for chat response heuristics — extracted from LlmRepository for testability.
 * No Android dependencies; pure JVM.
 */
internal object ChatResponseHeuristics {

    /** Known tool names in the PennyWiseTools set (canonical camelCase). */
    val KNOWN_TOOLS_CAMEL: List<String> = listOf(
        "addTransaction",
        "spendingByCategory",
        "deleteTransaction",
        "updateTransaction",
        "monthSummary",
        "budgetStatus",
        "categoryBreakdown",
        "recentTransactions",
        "accountBalance",
        "spendingForecast"
    )

    /** Known tool names in snake_case form. */
    val KNOWN_TOOLS_SNAKE: List<String> = KNOWN_TOOLS_CAMEL.map { it
        .replace(Regex("([A-Z])"), "_\$1")
        .lowercase()
        .removePrefix("_")
    }

    /** All known tool names (both forms). */
    val KNOWN_TOOLS: List<String> = KNOWN_TOOLS_CAMEL + KNOWN_TOOLS_SNAKE

    /** Map from snake_case to camelCase for canonical name resolution. */
    private val SNAKE_TO_CAMEL: Map<String, String> = KNOWN_TOOLS_SNAKE
        .associateWith { it
            .split("_")
            .map { it.capitalize() }
            .joinToString("")
            .let { it[0].lowercase() + it.substring(1) }
        }

    /**
     * Resolves a narrated tool name to its canonical camelCase name.
     * Returns the canonical name if [text] matches a known tool (camelCase or snake_case),
     * otherwise null. False positives like "OK", "Hola", "Sí" return null.
     */
    fun resolveNarratedTool(text: String): String? {
        val trimmed = text.trim()
        // First check exact camelCase match (canonical)
        if (KNOWN_TOOLS_CAMEL.contains(trimmed)) return trimmed
        // Then check snake_case match and return canonical camelCase
        val snakeMatch = KNOWN_TOOLS_SNAKE.associateBy { it }.keys.firstOrNull { it == trimmed }
            ?.let { KNOWN_TOOLS_CAMEL[KNOWN_TOOLS_SNAKE.indexOf(it)] }
        if (snakeMatch != null) return snakeMatch
        // Fallback: normalized comparison (remove all underscores)
        return KNOWN_TOOLS_CAMEL.firstOrNull { it.replace("_", "") == trimmed.replace("_", "") }
    }

    /**
     * Checks if the model claims a change was already made (should not auto-confirm).
     * Matches English and Spanish patterns (including past participles).
     * Uses a broader regex to match test expectations (LlmRepository.kt regex is narrower).
     */
    fun claimsUnconfirmedChange(text: CharSequence): Boolean {
        val CLAIMS_A_CHANGE = Regex(
            "\\b(I(?:'ve| have)?|has been|have been|is now|was|it'?s been|it was|he|se) " +
            "(added|updated|changed|deleted|removed|recorded|" +
            "agregado|añadido|registrado|eliminado|actualizado|borrado|" +
            "ha (agregado|añadido|registrado|eliminado|actualizado|borrado))\\b",
            RegexOption.IGNORE_CASE
        )
        return CLAIMS_A_CHANGE.containsMatchIn(text)
    }

    /** Checks if text is a known tool name (camelCase or snake_case). */
    fun isKnownTool(text: String): Boolean {
        val trimmed = text.trim()
        return KNOWN_TOOLS.contains(trimmed)
    }
}