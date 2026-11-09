package com.pennywiseai.tracker.data.repository

import com.pennywiseai.tracker.data.database.dao.SubscriptionDao
import com.pennywiseai.tracker.data.database.dao.TransactionDao
import com.pennywiseai.tracker.data.database.entity.SubscriptionState
import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.database.entity.TransactionType
import com.pennywiseai.tracker.data.model.*
import com.pennywiseai.tracker.data.repository.BudgetRepository
import com.pennywiseai.tracker.data.currency.CurrencyConversionService
import com.pennywiseai.tracker.data.preferences.UserPreferencesRepository
import com.pennywiseai.tracker.utils.forTotals
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for gathering AI chat context from financial data
 */
@Singleton
class AiContextRepository @Inject constructor(
    private val transactionDao: TransactionDao,
    private val subscriptionDao: SubscriptionDao,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val currencyConversionService: CurrencyConversionService,
    private val budgetRepository: BudgetRepository
) {
    
    /**
     * Gathers all financial context for AI chat in parallel
     */
    suspend fun getChatContext(): ChatContext = coroutineScope {
        val currentDate = LocalDate.now()
        val baseCurrency = userPreferencesRepository.baseCurrency.first()
        
        // Launch parallel queries
        val monthSummaryDeferred = async { getMonthSummary(currentDate, baseCurrency) }
        val recentTransactionsDeferred = async { getRecentTransactions(currentDate, baseCurrency) }
        val activeSubscriptionsDeferred = async { getActiveSubscriptions(currentDate, baseCurrency) }
        val topCategoriesDeferred = async { getTopCategories(currentDate, baseCurrency) }
        val quickStatsDeferred = async { getQuickStats(currentDate, baseCurrency) }
        
        ChatContext(
            currentDate = currentDate,
            monthSummary = monthSummaryDeferred.await(),
            recentTransactions = recentTransactionsDeferred.await(),
            activeSubscriptions = activeSubscriptionsDeferred.await(),
            topCategories = topCategoriesDeferred.await(),
            quickStats = quickStatsDeferred.await()
        )
    }
    
    private suspend fun getMonthSummary(currentDate: LocalDate, baseCurrency: String): MonthSummary {
        val yearMonth = YearMonth.from(currentDate)
        val startOfMonth = yearMonth.atDay(1)
        val endOfMonth = yearMonth.atEndOfMonth()
        
        // Get all transactions for current month
        val transactions = transactionDao.getTransactionsBetweenDatesList(
            startOfMonth.atStartOfDay(),
            endOfMonth.atTime(23, 59, 59)
        ).forTotals()  // exclude one-off purchases (#451) and loan-linked transactions from AI summary

        var totalIncome = BigDecimal.ZERO
        var totalExpense = BigDecimal.ZERO
        val transactionCount = transactions.size
        
        // Process in a single pass
        transactions.forEach { transaction ->
            // Skip unconvertible amounts rather than counting face value (#670).
            val convertedAmount = currencyConversionService.convertAmountOrNull(
                amount = transaction.amount,
                fromCurrency = transaction.currency,
                toCurrency = baseCurrency
            ) ?: return@forEach
            when (transaction.transactionType) {
                TransactionType.INCOME -> totalIncome = totalIncome.add(convertedAmount)
                TransactionType.EXPENSE -> totalExpense = totalExpense.add(convertedAmount)
                TransactionType.CREDIT -> totalExpense = totalExpense.add(convertedAmount) // Credit counts as expense
                TransactionType.TRANSFER -> {} // Transfers don't affect income/expense totals
                TransactionType.INVESTMENT -> {} // Investments are asset reallocation, not expenses
            }
        }
        
        return MonthSummary(
            totalIncome = totalIncome,
            totalExpense = totalExpense,
            transactionCount = transactionCount,
            daysInMonth = yearMonth.lengthOfMonth(),
            currentDay = currentDate.dayOfMonth
        )
    }
    
    private suspend fun getRecentTransactions(currentDate: LocalDate, baseCurrency: String, days: Int = 14): List<TransactionSummary> {
        val startDate = currentDate.minusDays(days.toLong())
        
        val transactions = transactionDao.getTransactionsBetweenDatesList(
            startDate.atStartOfDay(),
            currentDate.atTime(23, 59, 59)
        )
        
        return transactions
            .sortedByDescending { it.dateTime } // Most recent first
            .take(20) // Limit to 20 most recent
            .mapNotNull { transaction ->
                val daysAgo = ChronoUnit.DAYS.between(
                    transaction.dateTime.toLocalDate(),
                    currentDate
                ).toInt()

                // Drop unconvertible amounts from the AI's view rather than feeding
                // it a mislabeled face-value figure (#670).
                val convertedAmount = currencyConversionService.convertAmountOrNull(
                    amount = transaction.amount,
                    fromCurrency = transaction.currency,
                    toCurrency = baseCurrency
                ) ?: return@mapNotNull null

                TransactionSummary(
                    merchantName = transaction.merchantName,
                    amount = convertedAmount,
                    category = transaction.category,
                    daysAgo = daysAgo,
                    dateTime = transaction.dateTime,
                    transactionType = transaction.transactionType
                )
            }
    }
    
    private suspend fun getActiveSubscriptions(currentDate: LocalDate, baseCurrency: String): List<SubscriptionSummary> {
        return subscriptionDao.getSubscriptionsByStateList(SubscriptionState.ACTIVE)
            .mapNotNull { subscription ->
                val daysUntilPayment = ChronoUnit.DAYS.between(
                    currentDate,
                    subscription.nextPaymentDate
                ).toInt()

                val convertedAmount = currencyConversionService.convertAmountOrNull(
                    amount = subscription.amount,
                    fromCurrency = subscription.currency,
                    toCurrency = baseCurrency
                ) ?: return@mapNotNull null

                SubscriptionSummary(
                    merchantName = subscription.merchantName,
                    amount = convertedAmount,
                    nextPaymentDays = daysUntilPayment
                )
            }
            .sortedBy { it.nextPaymentDays }
            .take(10) // Limit to 10 subscriptions
    }
    
    /** This month's spend in one category, for the chat's lookup tool (#170). Null = nothing found. */
    suspend fun getCategorySpending(category: String): CategorySpending? {
        val baseCurrency = userPreferencesRepository.baseCurrency.first()
        return getTopCategories(LocalDate.now(), baseCurrency, limit = Int.MAX_VALUE)
            .firstOrNull { it.category.equals(category, ignoreCase = true) }
    }

    private suspend fun getTopCategories(currentDate: LocalDate, baseCurrency: String, limit: Int = 5): List<CategorySpending> {
        val yearMonth = YearMonth.from(currentDate)
        val startOfMonth = yearMonth.atDay(1)
        val endOfMonth = yearMonth.atEndOfMonth()
        
        val transactions = transactionDao.getTransactionsBetweenDatesList(
            startOfMonth.atStartOfDay(),
            endOfMonth.atTime(23, 59, 59)
        ).forTotals()  // exclude one-off purchases (#451) and loan-linked transactions from AI summary

        // Group by category and calculate spending
        val categoryMap = mutableMapOf<String, MutableList<BigDecimal>>()
        var totalExpense = BigDecimal.ZERO
        
        transactions
            .filter { it.transactionType == TransactionType.EXPENSE }
            .forEach { transaction ->
                val category = transaction.category
                val convertedAmount = currencyConversionService.convertAmountOrNull(
                    amount = transaction.amount,
                    fromCurrency = transaction.currency,
                    toCurrency = baseCurrency
                ) ?: return@forEach // skip unconvertible rather than face-value (#670)
                categoryMap.getOrPut(category) { mutableListOf() }.add(convertedAmount)
                totalExpense = totalExpense.add(convertedAmount)
            }
        
        return categoryMap.map { (category, amounts) ->
            val categoryTotal = amounts.reduce { acc, amount -> acc.add(amount) }
            val percentage = if (totalExpense > BigDecimal.ZERO) {
                categoryTotal.divide(totalExpense, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal(100))
                    .toFloat()
            } else 0f
            
            CategorySpending(
                category = category,
                amount = categoryTotal,
                percentage = percentage,
                transactionCount = amounts.size
            )
        }
            .sortedByDescending { it.amount }
            .take(limit)
    }
    
    private suspend fun getQuickStats(currentDate: LocalDate, baseCurrency: String): QuickStats {
        val yearMonth = YearMonth.from(currentDate)
        val startOfMonth = yearMonth.atDay(1)
        val endOfMonth = yearMonth.atEndOfMonth()
        
        val transactions = transactionDao.getTransactionsBetweenDatesList(
            startOfMonth.atStartOfDay(),
            endOfMonth.atTime(23, 59, 59)
        ).forTotals()  // exclude one-off purchases (#451) and loan-linked transactions from AI summary

        val expenses = transactions.filter { it.transactionType == TransactionType.EXPENSE }
        
        // Calculate average daily spending
        val convertedAmounts = expenses.mapNotNull {
            currencyConversionService.convertAmountOrNull(
                amount = it.amount,
                fromCurrency = it.currency,
                toCurrency = baseCurrency
            )
        }
        val totalExpense = convertedAmounts.fold(BigDecimal.ZERO) { acc, amount -> acc.add(amount) }
        val daysElapsed = currentDate.dayOfMonth
        val avgDailySpending = if (daysElapsed > 0) {
            totalExpense.divide(BigDecimal(daysElapsed), 2, RoundingMode.HALF_UP)
        } else BigDecimal.ZERO
        
        // Find largest expense (by converted base currency amount for fair comparison)
        var largestExpenseEntity: TransactionEntity? = null
        var largestConvertedAmount: BigDecimal = BigDecimal.ZERO

        expenses.zip(convertedAmounts).forEach { (transaction, converted) ->
            if (converted > largestConvertedAmount) {
                largestConvertedAmount = converted
                largestExpenseEntity = transaction
            }
        }

        val largestExpense = largestExpenseEntity?.let { transaction ->
            val daysAgo = ChronoUnit.DAYS.between(
                transaction.dateTime.toLocalDate(),
                currentDate
            ).toInt()

            TransactionSummary(
                merchantName = transaction.merchantName,
                amount = largestConvertedAmount,
                category = transaction.category,
                daysAgo = daysAgo
            )
        }
        
        // Find most frequent merchant
        val merchantCounts = expenses.groupingBy { it.merchantName }.eachCount()
        val mostFrequent = merchantCounts.maxByOrNull { it.value }
        
        return QuickStats(
            avgDailySpending = avgDailySpending,
            largestExpenseThisMonth = largestExpense,
            mostFrequentMerchant = mostFrequent?.key,
            mostFrequentMerchantCount = mostFrequent?.value ?: 0
        )
    }

    /**
     * Spending trend over multiple months for the spending_trend tool.
     * Returns list of (periodLabel, amountInBaseCurrency).
     */
    suspend fun getSpendingTrend(monthsBack: Int, isWeekly: Boolean): List<Pair<String, BigDecimal>> {
        val baseCurrency = userPreferencesRepository.baseCurrency.first()
        val currentDate = LocalDate.now()
        val trend = mutableListOf<Pair<String, BigDecimal>>()

        for (i in 0 until monthsBack) {
            val yearMonth = YearMonth.from(currentDate).minusMonths(i.toLong())
            val startOfMonth = yearMonth.atDay(1)
            val endOfMonth = yearMonth.atEndOfMonth()

            val transactions = transactionDao.getTransactionsBetweenDatesList(
                startOfMonth.atStartOfDay(),
                endOfMonth.atTime(23, 59, 59)
            ).forTotals()

            if (isWeekly) {
                // Group by week within the month
                val weeks = mutableMapOf<Int, MutableList<TransactionEntity>>()
                transactions.filter { it.transactionType == TransactionType.EXPENSE }.forEach { tx ->
                    val weekOfMonth = ((tx.dateTime.toLocalDate().dayOfMonth - 1) / 7) + 1
                    weeks.getOrPut(weekOfMonth) { mutableListOf() }.add(tx)
                }
                weeks.keys.sorted().forEach { week ->
                    val weekTxns = weeks[week]!!
                    val weekTotal = weekTxns
                        .mapNotNull { currencyConversionService.convertAmountOrNull(it.amount, it.currency, baseCurrency) }
                        .fold(BigDecimal.ZERO) { acc, a -> acc.add(a) }
                    val label = "${yearMonth.month}/${yearMonth.year} W$week"
                    trend.add(label to weekTotal)
                }
            } else {
                val monthTotal = transactions
                    .filter { it.transactionType == TransactionType.EXPENSE }
                    .mapNotNull { currencyConversionService.convertAmountOrNull(it.amount, it.currency, baseCurrency) }
                    .fold(BigDecimal.ZERO) { acc, a -> acc.add(a) }
                val label = "${yearMonth.month}/${yearMonth.year}"
                trend.add(label to monthTotal)
            }
        }

        return trend.reversed() // chronological order
    }

    /**
     * Advanced stats for a category for the category_stats tool.
     * Returns average, median, top merchants, and % vs budget.
     */
    suspend fun getCategoryStats(category: String, monthsBack: Int): CategoryStats? {
        val baseCurrency = userPreferencesRepository.baseCurrency.first()
        val currentDate = LocalDate.now()
        val startDate = currentDate.minusMonths(monthsBack.toLong())

        val transactions = transactionDao.getTransactionsBetweenDatesList(
            startDate.atStartOfDay(),
            currentDate.atTime(23, 59, 59)
        ).forTotals()

        val catTxns = transactions
            .filter { it.category.equals(category, ignoreCase = true) && it.transactionType == TransactionType.EXPENSE }
            .mapNotNull { currencyConversionService.convertAmountOrNull(it.amount, it.currency, baseCurrency) }
            .toList()

        if (catTxns.isEmpty()) return null

        val sorted = catTxns.sorted()
        val average = sorted.fold(BigDecimal.ZERO) { acc, a -> acc.add(a) } / BigDecimal(sorted.size)
        val median = sorted[sorted.size / 2]

        // Top merchants
        val merchantCounts = transactions
            .filter { it.category.equals(category, ignoreCase = true) && it.transactionType == TransactionType.EXPENSE }
            .groupingBy { it.merchantName }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
            .take(5)
            .map { it.first }

        // Budget percentage
        val budgetPct = try {
            val budgets = budgetRepository.getActiveBudgets().first()
            val budget = budgets.firstOrNull { it.name.equals(category, ignoreCase = true) }
            budget?.let { b ->
                val spend = budgetRepository.getBudgetSpending(b).first()
                if (b.limitAmount > BigDecimal.ZERO) {
                    spend.totalSpent.divide(b.limitAmount, 4, RoundingMode.HALF_UP).multiply(BigDecimal(100)).toFloat()
                } else 0f
            } ?: 0f
        } catch (e: Exception) {
            0f
        }

        return CategoryStats(
            average = average,
            median = median,
            topMerchants = merchantCounts,
            budgetPercentage = budgetPct
        )
    }
}