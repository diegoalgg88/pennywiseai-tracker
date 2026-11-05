package com.pennywiseai.parser.core.bank

import com.pennywiseai.parser.core.ParsedTransaction
import com.pennywiseai.parser.core.TransactionType
import java.math.BigDecimal

/**
 * Parser para American Express México.
 *
 * Sender verificado en device (2026-10-24): el alfanumérico `AMEX` — el MISMO
 * sender que reclama el parser de India (`AMEXBankParser`), que matchea con
 * `contains("AMEX")`. Por eso este parser se registra ANTES en la factory y su
 * `parse()` lleva un content-gate en español como mecanismo primario de
 * routing: un SMS de AMEX India en inglés falla el gate (retorna null) y el
 * despacho content-aware de la factory continúa al parser de India.
 *
 * Formatos soportados (es-MX):
 * - Cargo: "Se realizó un cargo de $1,234.56 en TU TIENDA con tu Tarjeta
 *   terminación 1009"
 * - Pago recibido: "Recibimos tu pago de $5,000.00. Gracias."
 *
 * Senders: AMEX (device), AMEXMX (variante futura documentada).
 * El corto `642639` (OTP de Amex) NO se reclama: el gate de "codigo" de la
 * base lo descarta si llegara por aquí.
 */
class AmexMexicoParser : BaseMexicoBankParser() {

    override fun getBankName() = "American Express Mexico"

    override fun canHandle(sender: String): Boolean {
        val normalizedSender = sender.uppercase()
        return normalizedSender == "AMEX" ||
            normalizedSender == "AMEXMX"
    }

    override fun parse(smsBody: String, sender: String, timestamp: Long): ParsedTransaction? {
        // Content-gate en español — mecanismo PRIMARIO de routing, no solo
        // defensa: el sender `AMEX` está en disputa real con el parser de
        // India (verificado en device). Sin marcadores mexicanos, o con
        // marcadores indios, este parser se abstiene y deja pasar al de India.
        val lower = smsBody.lowercase()
        val hasSpanishMarkers = SPANISH_MARKERS.any { lower.contains(it) }
        val hasIndianMarkers = INDIAN_MARKERS.any { lower.contains(it) }
        if (!hasSpanishMarkers || hasIndianMarkers) return null
        return super.parse(smsBody, sender, timestamp)
    }

    override fun extractAmount(message: String): BigDecimal? {
        // "cargo de $1,234.56 en ...", "pago de $5,000.00"
        return super.extractAmount(message)
    }

    override fun extractTransactionType(message: String): TransactionType? {
        val lower = message.lowercase()
        return when {
            // El pago de la propia tarjeta es dinero que ENTRA a la cuenta de
            // crédito (abono), no un gasto del usuario.
            lower.contains("recibimos tu pago") -> TransactionType.INCOME
            else -> super.extractTransactionType(message)
        }
    }

    override fun extractMerchant(message: String, sender: String): String? {
        // "cargo de $1,234.56 en TU TIENDA con tu Tarjeta"
        MERCHANT_AT_EN.find(message)?.let {
            val merchant = cleanMerchantName(it.groupValues[1].trim())
            if (isValidMerchantName(merchant)) return merchant
        }
        return super.extractMerchant(message, sender)
    }

    override fun extractAccountLast4(message: String): String? {
        super.extractAccountLast4(message)?.let { return it }
        // "Tarjeta terminación 1009" / "terminada en 1009"
        TERMINATION.find(message)?.let { return it.groupValues[1].takeLast(4) }
        return null
    }

    override fun isTransactionMessage(message: String): Boolean {
        val lower = message.lowercase()
        // Avisos operativos de AMEX MX verificados en device: el pago devuelto
        // no movió dinero.
        if (lower.contains("fue devuelto por el banco")) return false
        return super.isTransactionMessage(message)
    }

    private companion object {
        val SPANISH_MARKERS = listOf(
            "compra", "cargo", "pago", "abono", "mxn", "terminación", "terminacion",
            "tarjeta de crédito", "tarjeta de credito"
        )
        val INDIAN_MARKERS = listOf("inr", "rs.", "₹")
        // "de $1,234.56 en TU TIENDA con tu Tarjeta"
        val MERCHANT_AT_EN = Regex(
            """(?:de|por)\s+(?:MXN)?\$\s*[\d,]+\.\d{1,2}\s+en\s+(.+?)\s+con\s+t(?:u|us)?\s+tarjeta""",
            RegexOption.IGNORE_CASE
        )
        // "terminación 1009" / "terminada en 1009"
        val TERMINATION = Regex(
            """terminaci(?:ón|on)\s+(?:en\s+)?(\d{4,})""",
            RegexOption.IGNORE_CASE
        )
    }
}
