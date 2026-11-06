package com.pennywiseai.parser.core.bank

import com.pennywiseai.parser.core.ParsedTransaction
import com.pennywiseai.parser.core.TransactionType
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Parser para SMS de Telcel México (carrier de telefonía móvil).
 *
 * Sender: "TELCEL" (mayúsculas exactas)
 * Moneda: MXN
 * Formatos detectados (screen05.png):
 * 1. Recordatorio de saldo vencido: "Estimado cliente, te recordamos que tienes un saldo vencido por $601.39 Evita la cancelacion del servicio, te invitamos a pagar en pay.telcel.com/df1"
 * 2. Cargo fallido: "Estimado cliente, el cargo de factura a su tarjeta de credito por $601.39 no pudo procesarse. Pague aqui paga.mitelcel.com o CAC. +info *111"
 * 3. Vencimiento próximo: "Estimado cliente tu fecha de pago esta por vencer, para continuar con tu servicio paga $601.39 antes del 02/11/26 Mas info *111"
 *
 * Todos son notificaciones de facturación/recordatorio — NO son transacciones de dinero movido
 * (excepto el #2 que indica un cargo que "no pudo procesarse", por tanto no hay movimiento real).
 * Se clasifican como BALANCE_UPDATE para que aparezcan en la app pero no alteren balances.
 */
class TelcelMexicoParser : BaseMexicoBankParser() {

    override fun getBankName() = "Telcel México"

    override fun canHandle(sender: String): Boolean = sender.uppercase(Locale.ROOT) == "TELCEL"

    override fun extractAmount(message: String): BigDecimal? {
        // Primero intenta patrón específico de Telcel: "por $XXX.XX" o "paga $XXX.XX"
        return TELCEL_AMOUNT.find(message)?.groupValues?.get(1)
            ?.replace(",", "")?.toBigDecimalOrNull()
            ?: super.extractAmount(message)
    }

    override fun extractTransactionType(message: String): TransactionType? {
        val lower = message.lowercase(Locale.ROOT)

        // Telcel son notificaciones de facturación/recordatorio → BALANCE_UPDATE
        // No movieron dinero (el #2 dice "no pudo procesarse")
        return if (TELCEL_BILL_MARKERS.any { lower.contains(it) }) {
            TransactionType.BALANCE_UPDATE
        } else {
            super.extractTransactionType(message)
        }
    }

    override fun extractMerchant(message: String, sender: String): String? = "Telcel"

    override fun extractAccountLast4(message: String): String? = null

    override fun isTransactionMessage(message: String): Boolean {
        val lower = message.lowercase(Locale.ROOT)

        // Solo aceptar si contiene marcadores de facturación Telcel
        if (TELCEL_BILL_MARKERS.any { lower.contains(it) }) return true

        // Rechazar OTPs, códigos, promociones genéricas
        if (lower.contains("codigo") || lower.contains("código") ||
            lower.contains("clave") || lower.contains("otp")
        ) return false

        return super.isTransactionMessage(message)
    }

    private companion object {
        // "saldo vencido por $601.39", "cargo ... por $601.39", "paga $601.39 antes"
        val TELCEL_AMOUNT = Regex(
            """(?:por|paga)\s+\$\s*([\d,]+\.\d{1,2})""",
            RegexOption.IGNORE_CASE
        )

        // Marcadores que identifican SMS de facturación/recordatorio Telcel
        val TELCEL_BILL_MARKERS = listOf(
            "saldo vencido",
            "cargo de factura",
            "fecha de pago",
            "paga ",
            "pay.telcel.com",
            "paga.mitelcel.com",
            "mitelcel.com",
            "antes del"
        )
    }
}