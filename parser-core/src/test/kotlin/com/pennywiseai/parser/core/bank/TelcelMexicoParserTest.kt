package com.pennywiseai.parser.core.bank

import com.pennywiseai.parser.core.test.ExpectedTransaction
import com.pennywiseai.parser.core.test.ParserTestCase
import com.pennywiseai.parser.core.test.ParserTestUtils
import com.pennywiseai.parser.core.test.SimpleTestCase
import com.pennywiseai.parser.core.TransactionType
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal
import java.time.LocalDateTime

class TelcelMexicoParserTest {

    private val parser = TelcelMexicoParser()

    @TestFactory
    fun `telcel mexico parser handles billing notifications`(): List<DynamicTest> {
        val cases = listOf(
            ParserTestCase(
                name = "Saldo vencido - recordatorio de pago",
                message = "Estimado cliente, te recordamos que tienes un saldo vencido por \$601.39 Evita la cancelacion del servicio, te invitamos a pagar en pay.telcel.com/df1",
                sender = "TELCEL",
                expected = ExpectedTransaction(
                    amount = BigDecimal("601.39"),
                    currency = "MXN",
                    type = TransactionType.BALANCE_UPDATE,
                    merchant = "Telcel"
                )
            ),
            ParserTestCase(
                name = "Cargo de factura fallido - no se procesó",
                message = "Estimado cliente, el cargo de factura a su tarjeta de credito por \$601.39 no pudo procesarse. Pague aqui paga.mitelcel.com o CAC. +info *111",
                sender = "TELCEL",
                expected = ExpectedTransaction(
                    amount = BigDecimal("601.39"),
                    currency = "MXN",
                    type = TransactionType.BALANCE_UPDATE,
                    merchant = "Telcel"
                )
            ),
            ParserTestCase(
                name = "Fecha de pago por vencer - recordatorio",
                message = "Estimado cliente tu fecha de pago esta por vencer, para continuar con tu servicio paga \$601.39 antes del 02/11/26 Mas info *111",
                sender = "TELCEL",
                expected = ExpectedTransaction(
                    amount = BigDecimal("601.39"),
                    currency = "MXN",
                    type = TransactionType.BALANCE_UPDATE,
                    merchant = "Telcel"
                )
            )
        )

        return ParserTestUtils.runTestSuite(parser, cases)
    }

    @TestFactory
    fun `factory resolves telcel mexico sender`(): List<DynamicTest> {
        val cases = listOf(
            SimpleTestCase(
                bankName = "Telcel México",
                sender = "TELCEL",
                currency = "MXN",
                message = "Estimado cliente, te recordamos que tienes un saldo vencido por \$601.39 Evita la cancelacion del servicio, te invitamos a pagar en pay.telcel.com/df1",
                expected = ExpectedTransaction(
                    amount = BigDecimal("601.39"),
                    currency = "MXN",
                    type = TransactionType.BALANCE_UPDATE
                ),
                shouldHandle = true
            ),
            SimpleTestCase(
                bankName = "Telcel México",
                sender = "telcel",
                currency = "MXN",
                message = "Estimado cliente tu fecha de pago esta por vencer, para continuar con tu servicio paga \$601.39 antes del 02/11/26 Mas info *111",
                expected = ExpectedTransaction(
                    amount = BigDecimal("601.39"),
                    currency = "MXN",
                    type = TransactionType.BALANCE_UPDATE
                ),
                shouldHandle = true
            )
        )

        return ParserTestUtils.runFactoryTestSuite(cases, "Telcel Mexico factory smoke tests")
    }
}