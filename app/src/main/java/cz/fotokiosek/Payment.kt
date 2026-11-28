package cz.fotokiosek

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Ceny pro samoobslužný režim. Zdroj = web (StyleSync → `price_photo`, `price_ai`, `price_email`).
 * Prázdná / neplatná cena = neznámá → nic neprodávat (lepší než omylem zdarma).
 */
object Pricing {
    /** „100“, „49.9“ → částka; prázdné, záporné nebo nesmysl → null. */
    fun parse(s: String): BigDecimal? =
        s.trim().toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }?.setScale(2, RoundingMode.HALF_UP)

    /** „100 Kč“, „49,90 Kč“ (celé částky bez haléřů). */
    fun format(amount: BigDecimal, currency: String): String {
        val a = amount.setScale(2, RoundingMode.HALF_UP)
        val text = if (a.remainder(BigDecimal.ONE).signum() == 0) a.setScale(0).toPlainString()
        else a.toPlainString().replace('.', ',')
        return "$text $currency".trim()
    }

    /** Text částky pro účetnictví („100“, „49.9“). */
    fun plain(amount: BigDecimal): String =
        if (amount.signum() == 0) "0" else amount.stripTrailingZeros().toPlainString()
}

/** Co se platí: částka, měna a popis pro zákazníka (a pro terminál / účtenku). */
data class PaymentRequest(val amount: BigDecimal, val currency: String, val description: String)

sealed interface PaymentResult {
    data object Paid : PaymentResult
    data object Cancelled : PaymentResult
    data class Failed(val message: String) : PaymentResult
}

/**
 * Platební terminál. Skutečný terminál (karta) se později napojí jako další implementace:
 * [pay] pošle částku do terminálu a čeká, až zákazník zaplatí / zruší; [cancel] přeruší čekání
 * (tlačítko Zrušit, nečinnost). Pozor u skutečného terminálu: zaplaceno těsně před zrušením musí
 * vrátit [PaymentResult.Paid] (aplikace pak službu dodá), ne Cancelled.
 */
interface PaymentTerminal {
    /** Název pro menu správce a log. */
    val name: String
    /** Zkušební terminál: obrazovka platby ukáže tlačítka pro simulaci. */
    val simulated: Boolean
    /** Stav pro zákazníka („Přilož kartu…“). */
    val status: String
    suspend fun pay(request: PaymentRequest): PaymentResult
    fun cancel()
}

/** Zkušební terminál: nic neúčtuje, výsledek „zadá“ obsluha tlačítkem na obrazovce platby ([simulate]). */
class MockTerminal : PaymentTerminal {
    override val name = "Zkušební (simulace)"
    override val simulated = true
    override var status by mutableStateOf("")
        private set
    private var pending: CompletableDeferred<PaymentResult>? = null

    override suspend fun pay(request: PaymentRequest): PaymentResult {
        pending?.complete(PaymentResult.Cancelled)
        val deferred = CompletableDeferred<PaymentResult>()
        pending = deferred
        status = "Čekám na platbu…"
        return try {
            deferred.await()
        } finally {
            if (pending === deferred) {
                pending = null
                status = ""
            }
        }
    }

    /** Tlačítka „Simulovat úspěšnou platbu“ / „Simulovat chybu“ na obrazovce platby. */
    fun simulate(result: PaymentResult) {
        pending?.complete(result)
    }

    override fun cancel() {
        pending?.complete(PaymentResult.Cancelled)
    }
}
