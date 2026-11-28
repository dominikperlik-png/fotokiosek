package cz.fotokiosek

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class PaymentTest {
    @Test
    fun parsesAndFormatsPrices() {
        assertEquals(BigDecimal("100.00"), Pricing.parse("100"))
        assertEquals(BigDecimal("49.90"), Pricing.parse(" 49.9 "))
        assertEquals(BigDecimal("0.00"), Pricing.parse("0"))
        assertNull("prázdná = neznámá cena", Pricing.parse(""))
        assertNull(Pricing.parse("-5"))
        assertNull(Pricing.parse("abc"))
        assertEquals("100 Kč", Pricing.format(BigDecimal("100.00"), "Kč"))
        assertEquals("49,90 Kč", Pricing.format(BigDecimal("49.9"), "Kč"))
        assertEquals("249,50 €", Pricing.format(BigDecimal("49.9").multiply(BigDecimal(5)), "€"))
        assertEquals("0", Pricing.plain(BigDecimal("0.00")))
        assertEquals("49.9", Pricing.plain(BigDecimal("49.90")))
    }

    @Test
    fun mockTerminalCompletesWithSimulatedResult() = runBlocking {
        val terminal = MockTerminal()
        val request = PaymentRequest(BigDecimal("100"), "Kč", "Tisk")
        val paid = async { terminal.pay(request) }
        yield()
        terminal.simulate(PaymentResult.Paid)
        assertEquals(PaymentResult.Paid, paid.await())

        val cancelled = async { terminal.pay(request) }
        yield()
        terminal.cancel()
        assertEquals(PaymentResult.Cancelled, cancelled.await())
        assertEquals("", terminal.status)
    }
}
