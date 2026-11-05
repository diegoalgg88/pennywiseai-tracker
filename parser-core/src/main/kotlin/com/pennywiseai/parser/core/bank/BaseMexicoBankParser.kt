package com.pennywiseai.parser.core.bank

import com.pennywiseai.parser.core.TransactionType
import java.math.BigDecimal

/**
 * Base compartida para bancos de México (American Express Mexico, BBVA Mexico).
 *
 * Todos envían SMS en español con montos en MXN (`$1,234.56`, coma de miles y
 * punto decimal) y comparten el esqueleto `<verbo> ... de $<monto>` seguido de
 * `en <comercio>`, o precedido de `a <destinatario>` / `desde <originante>`.
 * Las subclases solo añaden su match de sender y sus patrones de cuenta/tarjeta.
 */
abstract class BaseMexicoBankParser : BankParser() {

    override fun getCurrency() = "MXN"

    /** Patrones de cuenta/tarjeta propios de cada banco; el grupo 1 es el last 4. Vacío = el SMS no trae cuenta. */
    protected open val accountPatterns: List<Regex> = emptyList()

    /**
     * Marcadores en español que delatan que el cuerpo NO es una transacción:
     * códigos de un solo uso, avisos operativos y promociones mexicanas.
     */
    override fun isTransactionMessage(message: String): Boolean {
        val lower = message.lowercase()
        if (lower.contains("codigo") || lower.contains("código") ||
            lower.contains("clave") || lower.contains("otp")
        ) return false
        if (FinancialMessageSafety.isSecurityCode(message)) return false
        if (FinancialMessageSafety.isOperationalOrPromotionalNotice(message)) return false
        if (MEXICAN_OPERATIONAL_PHRASES.any { lower.contains(it) }) return false
        // Un pago devuelto o rechazado no movió dinero — no se registra un monto
        // que nunca salió de la cuenta (mismo criterio que El Salvador).
        if (FinancialMessageSafety.hasExplicitFailure(message, MEXICAN_FAILURES)) return false
        if (MEXICAN_FAILURES.any { lower.contains(it) }) return false
        return extractTransactionType(message) != null
    }

    /**
     * Monto en formato mexicano: "$1,234.56", "$ 1,234.56", "MXN$1,234.56".
     * El separador de miles es coma y el decimal punto (formato US, como El Salvador).
     * Exige un decimal presente para no confundir "terminación 1234" con un monto.
     */
    override fun extractAmount(message: String): BigDecimal? =
        AMOUNT.find(message)?.groupValues?.get(1)?.replace(",", "")?.toBigDecimalOrNull()

    override fun extractTransactionType(message: String): TransactionType? {
        val lower = message.lowercase()
        return when {
            // "recibiste un abono", "depósito recibido" — entra dinero; se chequea
            // primero porque un SMS de abono también puede decir "Tarjeta de Débito".
            INCOME_MARKERS.any { lower.contains(it) } -> TransactionType.INCOME
            DEBIT_MARKERS.any { lower.contains(it) } -> TransactionType.EXPENSE
            else -> null
        }
    }

    override fun extractMerchant(message: String, sender: String): String? {
        PURCHASE.find(message)?.let { return it.groupValues[1].trim().trimEnd('.', ',') }

        if (extractTransactionType(message) == TransactionType.INCOME) {
            // El dinero que entra nombra al originante tras "de" o "desde".
            // NO leer "de NAME" cuando es el titular de la cuenta (lección de
            // El Salvador: "Abono a Cuenta de NAME" nombra al propio usuario).
            return FROM_PARTY.find(message)?.groupValues?.get(1)?.trim()?.trimEnd('.', ',')
        }
        // El dinero que sale nombra al beneficiario: "... a NAME el $26.00".
        return TO_PARTY.find(message)?.groupValues?.get(1)?.trim()
    }

    override fun extractAccountLast4(message: String): String? =
        accountPatterns.firstNotNullOfOrNull { it.find(message)?.groupValues?.get(1) }

    override fun extractBalance(message: String): BigDecimal? =
        super.extractBalance(message) ?: BALANCE.find(message)?.groupValues?.get(1)
            ?.replace(",", "")?.toBigDecimalOrNull()

    override fun detectIsCard(message: String): Boolean =
        CARD.containsMatchIn(message)

    private companion object {
        // "$1,234.56", "$ 26.00", "MXN$1,234.56" — exige decimales para
        // distinguir el monto del número de tarjeta "terminación 1009".
        val AMOUNT = Regex("""(?:MXN)?\$\s*([\d,]+\.\d{1,2})""", RegexOption.IGNORE_CASE)
        val DEBIT_MARKERS = listOf(
            "compra", "cargo", "retiro", "pagaste", "pago realizado", "dispuesto",
            "transferiste", "enviaste", "consumo"
        )
        val INCOME_MARKERS = listOf(
            "abono", "depósito recibido", "recibiste", "reembolso", "devolución",
            "transferencia recibida", "te depositaron"
        )
        // "... de $26.00 en OXXO ..." / "... por $26.00 en OXXO ..."
        val PURCHASE = Regex(
            """(?:de|por)\s+(?:MXN)?\$\s*[\d,]+\.\d{1,2}\s+en\s+(.+?)(?:\s+el\s+\d|\.\s|\.?$|,\s)""",
            RegexOption.IGNORE_CASE
        )
        // "... desde BANCO ORIGEN el 24/10 ..." — originante de un ingreso.
        val FROM_PARTY = Regex("""\b(?:desde|de parte de)\s+(.+?)(?:\s+el\s+\d|\.\s|\.?$)""", RegexOption.IGNORE_CASE)
        // "... a NAME el $26.00 ..." / "... a NAME por $26.00 ..." — beneficiario de un egreso.
        val TO_PARTY = Regex("""\ba\s+(.+?)\s+(?:el\s+)?(?:MXN)?\$""", RegexOption.IGNORE_CASE)
        // "Saldo disponible: $1,234.56", "Saldo: $1,234.56"
        val BALANCE = Regex("""saldo(?:\s+disponible)?\s*:?\s*(?:MXN)?\$\s*([\d,]+\.\d{1,2})""", RegexOption.IGNORE_CASE)
        val CARD = Regex("""tarjeta|\bTDC\b|\bTDD\b""", RegexOption.IGNORE_CASE)

        // Avisos operativos y promocionales del copy bancario mexicano verificado
        // (páginas oficiales de AMEX MX y BBVA MX, octubre 2026).
        val MEXICAN_OPERATIONAL_PHRASES = listOf(
            "mes sin intereses", "puntos mr", "membership rewards", "estado de cuenta",
            "fecha de corte", "fecha de pago", "safekey", "tu app", "descarga la app",
            "membresia", "membresía", "fue devuelto por el banco", "podria ser interrumpido",
            "podría ser interrumpido"
        )

        // Fallos explícitos en español mexicano: nada de dinero se movió.
        val MEXICAN_FAILURES = listOf(
            "rechazada", "rechazado", "declinada", "declinado", "negada", "negado",
            "sin fondos", "fondos insuficientes", "no realizada", "no realizado",
            "sin exito", "sin éxito", "fallo", "falló", "devuelto por el banco",
            "no procesada", "no procesado"
        )
    }
}
