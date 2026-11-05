package com.pennywiseai.parser.core.bank

import com.pennywiseai.parser.core.ParsedTransaction
import com.pennywiseai.parser.core.TransactionType

/**
 * Parser para BBVA México (antes BBVA Bancomer).
 *
 * Sender SMS oficial verificado: el corto numérico **`266845`** (único remitente
 * legítimo según la página antifraude de BBVA MX). También acepta el alias
 * alfanumérico **`BBVAMX`** — el "sender" sintético con el que
 * `BankNotificationListenerService` despacha las notificaciones push de la app
 * `com.bancomer.mbanking` (mismo patrón que Enpara/Huntington en
 * `BankNotificationConfig`).
 *
 * Formatos soportados (es-MX):
 * - Compra TDD: "Compra de $26.00 en OXXO con tu Tarjeta Debito terminada en 1234 el 24/10/26. Saldo disponible: $1,234.56"
 * - Compra TDC: "Has realizado una compra de $1,234.56 con tu Tarjeta de Credito terminada en 5678 en LIVERPOOL"
 * - Retiro ATM: "Retiro de $2,000.00 en CAJERO BBVA el 24/10/26. Saldo disponible: $3,456.78"
 * - SPEI: "Transferencia SPEI de $500.00 a JUAN PEREZ. Saldo disponible: ..."
 * - Pago de tarjeta: "Pago recibido de $5,000.00 para tu Tarjeta de Credito. Gracias."
 */
class BBVAMexicoParser : BaseMexicoBankParser() {

    override fun getBankName() = "BBVA Mexico"

    override fun canHandle(sender: String): Boolean {
        // El corto oficial puede llegar con prefijo de país según el OEM/agregador
        // (+52, 521): se compara el final del sender.
        val normalized = sender.uppercase().replace(" ", "")
        return normalized == "BBVAMX" ||
            normalized == "266845" ||
            normalized.endsWith("266845")
    }

    override fun parse(smsBody: String, sender: String, timestamp: Long): ParsedTransaction? {
        // Gate de contenido en español — mismo criterio que AmexMexicoParser:
        // sin marcadores mexicanos este parser se abstiene.
        val lower = smsBody.lowercase()
        if (!SPANISH_MARKERS.any { lower.contains(it) }) return null
        return super.parse(smsBody, sender, timestamp)
    }

    override fun extractTransactionType(message: String): TransactionType? {
        val lower = message.lowercase()
        return when {
            // "pago recibido de $X para tu Tarjeta de Credito" — el pago de la
            // propia TDC es abono a la cuenta de crédito (INCOME), no gasto.
            lower.contains("pago recibido") -> TransactionType.INCOME
            // Compra con Tarjeta de Credito → CREDIT (gasto con tarjeta de
            // crédito, isFromCard=true; la cuenta de crédito es un pasivo, no
            // entra al total gastable — mismo criterio que AMEX).
            lower.contains("tarjeta de credito") || lower.contains("tarjeta de crédito") ->
                if (DEBIT_OF_PURCHASE.any { lower.contains(it) }) TransactionType.CREDIT else null
            // SPEI saliente sin ambas puntas identificadas → EXPENSE simple;
            // el usuario puede recategorizar con reglas.
            lower.contains("transferencia spei") && lower.contains(" a ") ->
                TransactionType.EXPENSE
            else -> super.extractTransactionType(message)
        }
    }

    override fun extractAccountLast4(message: String): String? {
        super.extractAccountLast4(message)?.let { return it }
        // "terminada en 1234" / "terminación 5678" / "Tarjeta Debito 1234"
        TERMINATION.find(message)?.let { return it.groupValues[1].takeLast(4) }
        return null
    }

    /** Patrones de cuenta/tarjeta propios de BBVA MX. */
    override val accountPatterns: List<Regex> = listOf(
        // "Tarjeta Debito terminada en 1234"
        Regex("""terminada?\s+en\s+(\d{4})""", RegexOption.IGNORE_CASE),
        // "terminación 5678" / "terminacion 5678"
        Regex("""terminaci(?:ón|on)\s+(?:en\s+)?(\d{4})""", RegexOption.IGNORE_CASE)
    )

    override fun extractMerchant(message: String, sender: String): String? {
        // Dos formas de nombrar al comercio:
        // 1) "... de $26.00 en OXXO GASOLINERA con tu Tarjeta Debito ..." — el
        //    merchant va ANTES de la cláusula de la tarjeta.
        PURCHASE_BEFORE_CARD.find(message)?.let {
            val merchant = cleanMerchantName(it.groupValues[1].trim())
            if (isValidMerchantName(merchant)) return merchant
        }
        // 2) "... Tarjeta de Credito terminada en 5678 en LIVERPOOL" — el
        //    merchant va DESPUÉS del last 4 (formato TDC).
        PURCHASE_AFTER_CARD.find(message)?.let {
            val merchant = cleanMerchantName(it.groupValues[1].trim())
            if (isValidMerchantName(merchant)) return merchant
        }
        return super.extractMerchant(message, sender)
    }

    override fun isTransactionMessage(message: String): Boolean {
        val lower = message.lowercase()
        // El SMS de saldo a petición ("Envía SALDO al 266845") es operativo.
        if (lower.contains("envia") && lower.contains("saldo")) return false
        return super.isTransactionMessage(message)
    }

    private companion object {
        val SPANISH_MARKERS = listOf(
            "compra", "cargo", "pago", "abono", "retiro", "transferencia", "spei",
            "saldo", "tarjeta", "mxn"
        )
        val DEBIT_OF_PURCHASE = listOf("compra", "consumo", "cargo")
        val TERMINATION = Regex("""terminaci(?:ón|on)\s+(?:en\s+)?(\d{4,})""", RegexOption.IGNORE_CASE)
        // "... de $26.00 en OXXO GASOLINERA con tu Tarjeta Debito ..."
        val PURCHASE_BEFORE_CARD = Regex(
            """(?:de|por)\s+(?:MXN)?\$\s*[\d,]+\.\d{1,2}\s+en\s+(.+?)\s+con\s+t[uú]?\s+[Tt]arjeta""",
            RegexOption.IGNORE_CASE
        )
        // "... Tarjeta de Credito terminada en 5678 en LIVERPOOL"
        val PURCHASE_AFTER_CARD = Regex(
            """terminada?\s+en\s+\d{4}\s+en\s+(.+?)(?:\s+el\s+\d|\.\s|\.?$)""",
            RegexOption.IGNORE_CASE
        )
    }
}
