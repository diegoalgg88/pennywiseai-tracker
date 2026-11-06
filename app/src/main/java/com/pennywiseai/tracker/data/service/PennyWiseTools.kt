package com.pennywiseai.tracker.data.service

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

/**
 * Tools offered to the on-device model in Chat (#170/#767). The runtime only
 * uses these for their schema: conversations are created with
 * automaticToolCalling = false, so the bodies below never execute — the app
 * intercepts the call and asks the user before writing anything.
 */
@Suppress("unused")
class PennyWiseTools : ToolSet {
    @Tool(description = "Record a transaction the user is telling you about: money they spent, paid, bought, or received.")
    fun addTransaction(
        @ToolParam(description = "Amount of money as a number, without currency symbol") amount: Double,
        @ToolParam(description = "The shop, service or person the money went to or came from. Never a bank or card.") merchant: String,
        @ToolParam(description = "One of the known category names") category: String,
        @ToolParam(description = "EXPENSE for money spent or paid (the usual case), INCOME only for money received such as salary, refund or cashback") type: String,
        @ToolParam(description = "The bank, card or cash the user mentioned, or empty if not mentioned") account: String
    ): String = ""

    @Tool(description = "Delete a transaction the user recorded earlier, e.g. 'delete the starbucks coffee from yesterday'.")
    fun deleteTransaction(
        @ToolParam(description = "Merchant, shop or description words the user used to identify it") merchant: String,
        @ToolParam(description = "Amount if the user mentioned one, else 0") amount: Double,
        @ToolParam(description = "How many days ago: 0 for today, 1 for yesterday, -1 if not mentioned") daysAgo: Int
    ): String = ""

    @Tool(description = "Change the category or merchant name of a transaction the user recorded earlier, e.g. 'the uber yesterday was actually Transportation'.")
    fun updateTransaction(
        @ToolParam(description = "Merchant, shop or description words the user used to identify it") merchant: String,
        @ToolParam(description = "Amount if the user mentioned one, else 0") amount: Double,
        @ToolParam(description = "How many days ago: 0 for today, 1 for yesterday, -1 if not mentioned") daysAgo: Int,
        @ToolParam(description = "The new category, or empty to keep it") newCategory: String,
        @ToolParam(description = "The new merchant name, or empty to keep it") newMerchant: String
    ): String = ""

    @Tool(description = "Look up this month's totals: money spent, money received and number of transactions.")
    fun monthSummary(): String = ""

    @Tool(description = "Look up how much the user has spent in a category this month.")
    fun spendingByCategory(
        @ToolParam(description = "One of the known category names") category: String
    ): String = ""

    @Tool(description = "Look up the status of the user's active budgets: limit, spent, remaining and percent used for each.")
    fun budgetStatus(): String = ""

    @Tool(description = "Look up this month's spending broken down by category, most spent first.")
    fun categoryBreakdown(
        @ToolParam(description = "How many categories to return, max 10; default 5") limit: Int
    ): String = ""

    @Tool(description = "Look up the user's most recent transactions.")
    fun recentTransactions(
        @ToolParam(description = "How many recent transactions to return, max 20; default 5") limit: Int,
        @ToolParam(description = "How many days back to look, max 30; default 7") daysBack: Int
    ): String = ""

    @Tool(description = "Look up the balance of each of the user's accounts.")
    fun accountBalance(): String = ""

    @Tool(description = "Forecast the user's total spending for this month based on the daily average so far.")
    fun spendingForecast(): String = ""

    // --- READ-ONLY TOOLS (added 2026-11-05) ---

    @Tool(description = "Search transactions with rich filters: date range, category, merchant, amount range, account, currency, type.")
    fun searchTransactions(
        @ToolParam(description = "Free-text query to match against merchant, notes, category") query: String,
        @ToolParam(description = "Start date (ISO format: YYYY-MM-DD)") dateFrom: String,
        @ToolParam(description = "End date (ISO format: YYYY-MM-DD)") dateTo: String,
        @ToolParam(description = "Category name to filter by") category: String,
        @ToolParam(description = "Merchant name to filter by") merchant: String,
        @ToolParam(description = "Minimum amount") amountMin: Double,
        @ToolParam(description = "Maximum amount") amountMax: Double,
        @ToolParam(description = "Account alias or last 4 digits") account: String,
        @ToolParam(description = "Currency code (ISO 4217)") currency: String,
        @ToolParam(description = "Transaction type: EXPENSE or INCOME") type: String,
        @ToolParam(description = "Maximum results to return, max 50; default 20") limit: Int
    ): String = ""

    @Tool(description = "Show spending trend over multiple months with weekly or monthly granularity.")
    fun spendingTrend(
        @ToolParam(description = "How many months back to analyze, 3-12; default 6") monthsBack: Int,
        @ToolParam(description = "Granularity: MONTH or WEEK") granularity: String
    ): String = ""

    @Tool(description = "List subscriptions with their next billing date, amount, and status.")
    fun subscriptionSummary(
        @ToolParam(description = "Filter by status: ACTIVE, PAUSED, CANCELLED; empty for all") status: String
    ): String = ""

    @Tool(description = "Convert between currencies using current or custom exchange rates.")
    fun exchangeRate(
        @ToolParam(description = "Amount to convert") amount: Double,
        @ToolParam(description = "Source currency code (ISO 4217)") fromCurrency: String,
        @ToolParam(description = "Target currency code (ISO 4217)") toCurrency: String,
        @ToolParam(description = "Use custom rate override if available; default true") useCustomRate: Boolean
    ): String = ""

    @Tool(description = "Advanced statistics for a category: average, median, top merchants, % vs budget.")
    fun categoryStats(
        @ToolParam(description = "Category name") category: String,
        @ToolParam(description = "Months back to analyze, default 3") monthsBack: Int
    ): String = ""

    companion object {
        const val ADD_TRANSACTION = "add_transaction"
        const val SPENDING_BY_CATEGORY = "spending_by_category"
        const val DELETE_TRANSACTION = "delete_transaction"
        const val UPDATE_TRANSACTION = "update_transaction"
        const val MONTH_SUMMARY = "month_summary"
        const val BUDGET_STATUS = "budget_status"
        const val CATEGORY_BREAKDOWN = "category_breakdown"
        const val RECENT_TRANSACTIONS = "recent_transactions"
        const val ACCOUNT_BALANCE = "account_balance"
        const val SPENDING_FORECAST = "spending_forecast"
        // Read-only tools (added 2026-11-05)
        const val SEARCH_TRANSACTIONS = "search_transactions"
        const val SPENDING_TREND = "spending_trend"
        const val SUBSCRIPTION_SUMMARY = "subscription_summary"
        const val EXCHANGE_RATE = "exchange_rate"
        const val CATEGORY_STATS = "category_stats"
    }
}