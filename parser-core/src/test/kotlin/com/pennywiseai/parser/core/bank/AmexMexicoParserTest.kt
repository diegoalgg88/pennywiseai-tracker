package com.pennywiseai.parser.core.bank

import com.pennywiseai.parser.core.TransactionType
import com.pennywiseai.parser.core.test.ExpectedTransaction
import com.pennywiseai.parser.core.test.ParserTestCase
import com.pennywiseai.parser.core.test.ParserTestUtils
import com.pennywiseai.parser.core.test.SimpleTestCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal

/**
 * Tests para AmexMexicoParser (American Express México, MXN).
 *
 * El sender `AMEX` está en disputa REAL con el parser de India (mismo sender
 * alfanumérico, verificado en device 2026-10-24): la resolución es por
 * contenido (content-gate en español), no por sender. Los factory smoke tests
 * cubren AMBOS sentidos del shadowing.
 */
class AmexMexicoParserTest {

    private val parser = AmexMexicoParser()

    @TestFactory
    fun `parsea cargos de tarjeta AMEX Mexico`() = ParserTestUtils.runTestSuite(
        parser,
        listOf(
            // Sample sintético — estructura documentada, monto con centavos y
            // terminación de tarjeta. TODO: verificar contra el texto real de la
            // notificación push de Amex App MX cuando llegue la primera transacción
            // (BBVA/AMEX MX no envían SMS de transacción — el canal real es push;
            // copys reales quedan en la tabla bank_notifications).
            ParserTestCase(
                name = "cargo en comercio",
                message = "Se realizó un cargo de $1,234.56 en TU TIENDA SA DE CV con tu Tarjeta terminación 1009",
                sender = "AMEX",
                expected = ExpectedTransaction(
                    amount = BigDecimal("1234.56"),
                    currency = "MXN",
                    type = TransactionType.EXPENSE,
                    merchant = "TU TIENDA SA DE CV",
                    accountLast4 = "1009",
                    isFromCard = true
                )
            ),
            ParserTestCase(
                name = "compra con monto pequeño",
                message = "Compra realizada por $26.00 en OXXO GASOLINERA con tu Tarjeta de crédito terminación 1009",
                sender = "AMEX",
                expected = ExpectedTransaction(
                    amount = BigDecimal("26.00"),
                    currency = "MXN",
                    type = TransactionType.EXPENSE,
                    merchant = "OXXO GASOLINERA",
                    accountLast4 = "1009",
                    isFromCard = true
                )
            ),
            // Sample REAL del device (2026-10-24) — aviso de pago devuelto,
            // sin monto: no es transacción.
            ParserTestCase(
                name = "pago devuelto (sample real device) — no es transacción",
                message = "El pago de tu AmEx fue devuelto por el banco. El servicio podria ser interrumpido. Paga en http://m.amex/MXAP o https://m.amex/centrosDePago",
                sender = "AMEX",
                shouldParse = false
            ),
            ParserTestCase(
                name = "otp (sample real device, sender 642639) — no es transacción",
                message = "NUNCA compartas este codigo de un solo uso, Amex no te llamara para pedirlo:  818916. Si no lo pediste, por favor contactanos o visita el sitio web de Amex.",
                sender = "642639",
                shouldParse = false
            ),
            ParserTestCase(
                name = "promo puntos MR — no es transacción",
                message = "¡Hola! Acumula Puntos MR en tus compras con tu Tarjeta American Express. Conoce más en amex.com.mx/recompensas",
                sender = "AMEX",
                shouldParse = false
            ),
            ParserTestCase(
                name = "estado de cuenta listo — no es transacción",
                message = "Tu estado de cuenta está listo. Revisa tu fecha de pago en la Amex App.",
                sender = "AMEX",
                shouldParse = false
            )
        ),
        handleCases = listOf(
            "AMEX" to true,
            "AMEXMX" to true,
            "AMEXIN" to false,   // India no es territorio de este parser
            "642639" to false,  // sender de OTP, no se reclama
            "TX-AMEXIN-S" to false,
            "HDFCBK" to false
        ),
        suiteName = "AmexMexicoParser"
    )

    @TestFactory
    fun `factory resuelve el sender AMEX por contenido (shadowing con India)`() =
        ParserTestUtils.runFactoryTestSuite(
            listOf(
                // Español → parser de MÉXICO (content-gate pasa).
                SimpleTestCase(
                    bankName = "American Express Mexico",
                    sender = "AMEX",
                    currency = "MXN",
                    message = "Se realizó un cargo de $1,234.56 en TU TIENDA SA DE CV con tu Tarjeta terminación 1009",
                    expected = ExpectedTransaction(
                        amount = BigDecimal("1234.56"),
                        currency = "MXN",
                        type = TransactionType.EXPENSE
                    )
                ),
                // Inglés + INR → parser de INDIA (content-gate del MX falla,
                // el despacho content-aware continúa al de India).
                SimpleTestCase(
                    bankName = "American Express",
                    sender = "AMEXIN",
                    currency = "INR",
                    message = "Alert: You've spent INR 1,017.70 on your AMEX card ** 91000 at VOUCHER PLAT on 20 August 2025"
                ),
                // Sender `AMEX` con body que no matchea NINGÚN parser (aviso
                // operativo en español sin monto): debe retornar null.
                SimpleTestCase(
                    bankName = "American Express Mexico",
                    sender = "AMEX",
                    currency = "MXN",
                    message = "El pago de tu AmEx fue devuelto por el banco. El servicio podria ser interrumpido. Paga en http://m.amex/MXAP",
                    shouldParse = false
                )
            ),
            suiteName = "AmexMexicoParser factory dispatch"
        )

    @Test
    fun `factory dispatch content-aware envia body en ingles con sender AMEX al parser de India`() {
        // getParser("AMEX") devuelve el primer parser que matchea el sender (el de
        // Mexico, registrado antes); el shadowing real se resuelve en
        // BankParserFactory.parse(), que itera TODOS los parsers del sender y
        // devuelve el primer parse() no-null — este es el path del pipeline.
        val indiaBody = "Alert: You've spent INR 1,017.70 on your AMEX card ** 91000 at VOUCHER PLAT on 20 August 2025"
        val parsed = BankParserFactory.parse(indiaBody, "AMEX", System.currentTimeMillis())
        assertNotNull(parsed, "El despacho content-aware debia resolver el body de India")
        assertEquals("American Express", parsed!!.bankName, "Un body en ingles con sender AMEX debe caer al parser de India")
        assertEquals("INR", parsed.currency)
    }

    @Test
    fun `canHandle acepta solo senders exactos de Mexico`() {
        assertEquals(true, parser.canHandle("AMEX"))
        assertEquals(true, parser.canHandle("amex".uppercase()))
        assertEquals(false, parser.canHandle("AMEXIN"))
        assertEquals(false, parser.canHandle("HDFCBK"))
    }
}
