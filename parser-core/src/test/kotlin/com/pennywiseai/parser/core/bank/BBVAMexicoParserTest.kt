package com.pennywiseai.parser.core.bank

import com.pennywiseai.parser.core.TransactionType
import com.pennywiseai.parser.core.test.ExpectedTransaction
import com.pennywiseai.parser.core.test.ParserTestCase
import com.pennywiseai.parser.core.test.ParserTestUtils
import com.pennywiseai.parser.core.test.SimpleTestCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal

/**
 * Tests para BBVAMexicoParser (BBVA México, MXN).
 *
 * Sender SMS oficial: corto `266845` (única fuente legítima según la página
 * antifraude de BBVA MX). Alias `BBVAMX` para las notificaciones push de la
 * app `com.bancomer.mbanking` (BankNotificationConfig). Samples sintéticos
 * anclados a la estructura documentada; TODO: verificar contra el texto real
 * de las notificaciones push cuando llegue la primera transacción (BBVA MX
 * no envía SMS de transacción a este device — el canal real es push; los
 * copys reales quedan registrados en la tabla bank_notifications).
 */
class BBVAMexicoParserTest {

    private val parser = BBVAMexicoParser()

    @TestFactory
    fun `parsea movimientos de BBVA Mexico`() = ParserTestUtils.runTestSuite(
        parser,
        listOf(
            // Sample sintético — compra TDD con saldo disponible.
            ParserTestCase(
                name = "compra TDD en comercio",
                message = "Compra de $26.00 en OXXO GASOLINERA con tu Tarjeta Debito terminada en 1234. Saldo disponible: $1,234.56",
                sender = "266845",
                expected = ExpectedTransaction(
                    amount = BigDecimal("26.00"),
                    currency = "MXN",
                    type = TransactionType.EXPENSE,
                    merchant = "OXXO GASOLINERA",
                    accountLast4 = "1234",
                    balance = BigDecimal("1234.56"),
                    isFromCard = true
                )
            ),
            // Sample sintético — compra TDC: gasto con tarjeta de crédito (CREDIT).
            ParserTestCase(
                name = "compra TDC en comercio",
                message = "Has realizado una compra de $1,234.56 con tu Tarjeta de Credito terminada en 5678 en LIVERPOOL",
                sender = "266845",
                expected = ExpectedTransaction(
                    amount = BigDecimal("1234.56"),
                    currency = "MXN",
                    type = TransactionType.CREDIT,
                    merchant = "LIVERPOOL",
                    accountLast4 = "5678",
                    isFromCard = true
                )
            ),
            // Sample sintético — retiro ATM.
            ParserTestCase(
                name = "retiro en cajero",
                message = "Retiro de $2,000.00 en CAJERO BBVA. Saldo disponible: $3,456.78",
                sender = "266845",
                expected = ExpectedTransaction(
                    amount = BigDecimal("2000.00"),
                    currency = "MXN",
                    type = TransactionType.EXPENSE
                )
            ),
            // Sample sintético — pago de la propia TDC: abono (INCOME).
            ParserTestCase(
                name = "pago recibido de tarjeta de crédito",
                message = "Pago recibido de $5,000.00 para tu Tarjeta de Credito. Gracias por tu pago.",
                sender = "266845",
                expected = ExpectedTransaction(
                    amount = BigDecimal("5000.00"),
                    currency = "MXN",
                    type = TransactionType.INCOME
                )
            ),
            // Negativos: OTP, promo, aviso operativo.
            ParserTestCase(
                name = "otp — no es transacción",
                message = "Tu codigo de verificacion BBVA es 123456. No lo compartas con nadie.",
                sender = "266845",
                shouldParse = false
            ),
            ParserTestCase(
                name = "promo mes sin intereses — no es transacción",
                message = "Con tus compras acumula Mes sin Intereses en miles de comercios. Consulta en bbva.mx",
                sender = "266845",
                shouldParse = false
            ),
            ParserTestCase(
                name = "aviso de estado de cuenta — no es transacción",
                message = "Ya está disponible tu estado de cuenta. Consulta tu fecha de corte en la app BBVA.",
                sender = "266845",
                shouldParse = false
            ),
            ParserTestCase(
                name = "compra rechazada — no movió dinero",
                message = "Tu compra de $150.00 fue rechazada por fondos insuficientes. Tarjeta Debito terminada en 1234",
                sender = "266845",
                shouldParse = false
            )
        ),
        handleCases = listOf(
            "266845" to true,
            "BBVAMX" to true,
            "+52266845" to true,   // corto con prefijo de país
            "521266845" to true,  // variante de prefijo OEM
            "BBVA" to false,       // sender no verificado, no se reclama
            "HDFCBK" to false
        ),
        suiteName = "BBVAMexicoParser"
    )

    @TestFactory
    fun `factory resuelve el sender 266845 y el alias BBVAMX`() =
        ParserTestUtils.runFactoryTestSuite(
            listOf(
                // El corto oficial SMS.
                SimpleTestCase(
                    bankName = "BBVA Mexico",
                    sender = "266845",
                    currency = "MXN",
                    message = "Compra de $26.00 en OXXO GASOLINERA con tu Tarjeta Debito terminada en 1234. Saldo disponible: $1,234.56",
                    expected = ExpectedTransaction(
                        amount = BigDecimal("26.00"),
                        currency = "MXN",
                        type = TransactionType.EXPENSE
                    )
                ),
                // El alias de las notificaciones push de la app.
                SimpleTestCase(
                    bankName = "BBVA Mexico",
                    sender = "BBVAMX",
                    currency = "MXN",
                    message = "Compra de $26.00 en OXXO GASOLINERA con tu Tarjeta Debito terminada en 1234. Saldo disponible: $1,234.56",
                    expected = ExpectedTransaction(
                        amount = BigDecimal("26.00"),
                        currency = "MXN",
                        type = TransactionType.EXPENSE
                    )
                )
            ),
            suiteName = "BBVAMexicoParser factory dispatch"
        )

    @Test
    fun `canHandle acepta corto oficial y alias, no senders no verificados`() {
        assertEquals(true, parser.canHandle("266845"))
        assertEquals(true, parser.canHandle("BBVAMX"))
        assertEquals(true, parser.canHandle("+52266845"))
        assertEquals(false, parser.canHandle("BBVA"))
    }
}
