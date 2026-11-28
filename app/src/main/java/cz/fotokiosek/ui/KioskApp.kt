package cz.fotokiosek.ui

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import kotlin.math.max
import kotlin.math.min
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cz.fotokiosek.AiStyle
import cz.fotokiosek.AiStyles
import cz.fotokiosek.AppLog
import cz.fotokiosek.CameraController
import cz.fotokiosek.GeminiClient
import cz.fotokiosek.IppClient
import cz.fotokiosek.KioskSettings
import cz.fotokiosek.LogoStore
import cz.fotokiosek.MockTerminal
import cz.fotokiosek.PaymentRequest
import cz.fotokiosek.PaymentResult
import cz.fotokiosek.PaymentTerminal
import cz.fotokiosek.PhotoStore
import cz.fotokiosek.Pricing
import cz.fotokiosek.PrintLayout
import cz.fotokiosek.PrintMedia
import cz.fotokiosek.RateLimit
import cz.fotokiosek.ReportWorker
import cz.fotokiosek.SendPhotoWorker
import cz.fotokiosek.SelphyUsb
import cz.fotokiosek.SystemPrint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.math.BigDecimal

sealed interface Screen {
    data object Idle : Screen
    data class Countdown(val value: Int) : Screen
    /** Fotka je pořízená a připravuje se (logo, tisková verze) – animace „vyvolávání“. Rovnost = identita. */
    class Developing(val raw: Bitmap) : Screen
    data class Review(val photo: Bitmap) : Screen
    data class Email(val photo: Bitmap, val email: String = "") : Screen
    data class Confirm(val photo: Bitmap, val email: String) : Screen
    data object Sent : Screen
    data object Gallery : Screen
    /** AI FOTOKIOSEK: výběr stylu pro fotku [raw]. */
    data class AiPick(val raw: Bitmap) : Screen
    /** AI právě upravuje fotku. */
    data class AiWorking(val raw: Bitmap, val style: AiStyle) : Screen
    data class AiFailed(val raw: Bitmap, val message: String) : Screen
    data object Admin : Screen
    /** Samoobslužný režim: čeká se na zaplacení [request]; po zaplacení [onPaid], jinak zpět na [back]. Rovnost = identita. */
    class Payment(val request: PaymentRequest, val back: Screen, val onPaid: () -> Unit) : Screen
}

/** Krátká hláška nahoře na obrazovce (červená = chyba, zelená = info). */
data class Notice(val text: String, val error: Boolean = true)

@Composable
fun KioskApp(
    settings: KioskSettings,
    camera: CameraController,
    lastTouch: MutableLongState,
    pickLogo: ((Uri?) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf<Screen>(Screen.Idle) }
    var notice by remember { mutableStateOf<Notice?>(null) }
    var askPin by remember { mutableStateOf(false) }
    // Ochrana PINu: po MAX_PIN_FAILURES špatných pokusech se menu správce na chvíli zamkne.
    var pinFailures by remember { mutableIntStateOf(0) }
    var pinLockedUntil by remember { mutableLongStateOf(0L) }
    var printFor by remember { mutableStateOf<Bitmap?>(null) }
    /** Tisková verze poslední fotky (ořez na papír + logo) – ukazuje se po vyfocení a tiskne se. */
    var printPhoto by remember { mutableStateOf<Bitmap?>(null) }
    /** Fotka otevřená z galerie – „Znovu“ se pak vrací do galerie. */
    var fromGallery by remember { mutableStateOf(false) }
    var askGalleryPin by remember { mutableStateOf(false) }
    var printing by remember { mutableStateOf(false) }
    /** AI FOTOKIOSEK: původní fotka, poslední AI výsledek a počet pokusů u aktuální fotky. */
    var aiRaw by remember { mutableStateOf<Bitmap?>(null) }
    var aiLast by remember { mutableStateOf<Bitmap?>(null) }
    var aiTries by remember { mutableIntStateOf(0) }
    /** Název AI stylu poslední AI fotky a fotky právě na obrazovce (null = původní) – pro účetnictví. */
    var aiLastStyle by remember { mutableStateOf<String?>(null) }
    var shownStyle by remember { mutableStateOf<String?>(null) }
    val flash = remember { Animatable(0f) }
    /** Platební terminál samoobslužného režimu (zatím jen zkušební – viz Payment.kt). */
    val terminal = remember { MockTerminal() }
    /** AI fotka na obrazovce (null = původní) a AI fotka, za kterou už zákazník zaplatil. */
    var shownAi by remember { mutableStateOf<Bitmap?>(null) }
    var aiPaidFor by remember { mutableStateOf<Bitmap?>(null) }

    /** Uložení neuložených změn z otevřeného menu správce (nastavuje AdminScreen). */
    var adminAutoSave by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun goIdle() {
        // Menu správce zavřené nečinností: nejdřív uložit jeho neuložené změny (úvodní obrazovka už je má ukázat).
        if (screen == Screen.Admin) adminAutoSave?.invoke()
        if (screen is Screen.Payment) terminal.cancel()
        screen = Screen.Idle
        askPin = false
        printFor = null
        printPhoto = null
        fromGallery = false
        askGalleryPin = false
        aiRaw = null
        aiLast = null
        aiTries = 0
        aiLastStyle = null
        shownStyle = null
        shownAi = null
        aiPaidFor = null
    }

    /** E-mailová verze (celá fotka s logem) a tisková verze (ořez na papír + logo; jen při zapnutém tisku). */
    suspend fun prepare(raw: Bitmap): Pair<Bitmap, Bitmap?> = withContext(Dispatchers.Default) {
        val mail = runCatching { LogoStore.applyToPhoto(context, settings, raw) }.getOrDefault(raw)
        // Při zapnutém tisku se ukáže rovnou tisková verze (přesně to, co vyjde z tiskárny).
        val print = if (!settings.printEnabled) null else runCatching { PrintLayout.forPrint(context, settings, raw) }
            .onFailure { AppLog.e("Tisk", "Příprava tiskové verze fotky selhala", it) }
            .getOrNull()
        mail to print
    }

    /** Špatný PIN (správce i galerie) se počítá společně – po MAX_PIN_FAILURES se zadávání zamkne. */
    fun pinResult(ok: Boolean): Boolean {
        if (ok) {
            pinFailures = 0
        } else if (++pinFailures >= MAX_PIN_FAILURES) {
            pinFailures = 0
            pinLockedUntil = SystemClock.elapsedRealtime() + PIN_LOCK_MS
            askPin = false
            askGalleryPin = false
            AppLog.w("Správa", "$MAX_PIN_FAILURES× špatný PIN – zadávání PINu zamčeno na ${PIN_LOCK_MS / 60_000} min")
            notice = Notice("Moc špatných pokusů. Zadávání PINu je na ${PIN_LOCK_MS / 60_000} minut zamčené.")
        }
        return ok
    }

    fun pinLocked(): Boolean {
        val left = pinLockedUntil - SystemClock.elapsedRealtime()
        if (left > 0) notice = Notice("Zadávání PINu je zamčené kvůli špatným pokusům. Zkus to za ${left / 60_000 + 1} min.")
        return left > 0
    }

    /** Galerie: s PINem (když je vyžadovaný), jinak rovnou. */
    fun openGallery() {
        if (!settings.galleryRequirePin) {
            screen = Screen.Gallery
        } else if (!pinLocked()) {
            askGalleryPin = true
        }
    }

    fun saveToGallery(photo: Bitmap) {
        if (!settings.galleryEnabled) return
        val keepDays = settings.keepDays
        scope.launch(Dispatchers.IO) {
            runCatching { PhotoStore.saveToGallery(context, photo, keepDays) }
                .onFailure { AppLog.e("Galerie", "Uložení fotky do galerie selhalo", it) }
        }
    }

    /** Hotová fotka (původní nebo z AI) → logo, tisková verze → obrazovka s tiskem a e-mailem. */
    fun showResult(photo: Bitmap, style: String? = null) {
        val from = screen
        scope.launch {
            val (mail, print) = prepare(photo)
            if (screen !== from) return@launch // mezitím jinam (nečinnost, dvojí klepnutí)
            printPhoto = print
            shownStyle = style
            shownAi = if (style != null) photo else null
            screen = Screen.Review(mail)
        }
    }

    fun aiStyles(): List<AiStyle> = AiStyles.active(context, settings)
    fun aiActive() = settings.aiEnabled && settings.geminiApiKey.isNotBlank() && aiStyles().isNotEmpty()
    fun aiTriesLeft() = (settings.aiMaxTries.coerceIn(1, 20) - aiTries).coerceAtLeast(0)

    fun pickAiStyle(raw: Bitmap, style: AiStyle) {
        if (aiTriesLeft() <= 0) return
        aiTries++
        val working = Screen.AiWorking(raw, style)
        screen = working
        val apiKey = settings.geminiApiKey
        val model = settings.geminiModel.ifBlank { GeminiClient.DEFAULT_MODEL }
        val media = if (settings.printEnabled) PrintMedia.forSettings(settings) else null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // Při tisku poslat rovnou ve tvaru papíru – AI pak skládá obraz přímo pro výtisk.
                    val source = if (media != null) PrintLayout.fit(raw, media) else raw
                    GeminiClient.stylize(apiKey, model, source, AiStyles.fullPrompt(style))
                }
            }
            if (screen !== working) {
                // Zrušeno – zaplacený výsledek ale nezahodit, když zákazník pořád vybírá styl k téže fotce.
                if (aiRaw === raw) result.getOrNull()?.let {
                    aiLast = it
                    aiLastStyle = style.name
                    saveToGallery(it)
                    ReportWorker.ai(context, settings, style.name)
                }
                return@launch
            }
            result.onSuccess { image ->
                AppLog.i("AI", "Styl ${style.name} hotový")
                aiLast = image
                aiLastStyle = style.name
                ReportWorker.ai(context, settings, style.name)
                saveToGallery(image)
                showResult(image, style.name)
            }.onFailure { e ->
                if (e is CancellationException) throw e
                val message = (e as? GeminiClient.AiException)?.message ?: "Neočekávaná chyba: ${AppLog.causes(e)}"
                AppLog.e("AI", "Styl ${style.name} selhal: $message", e)
                settings.lastError = "${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}: AI: $message"
                screen = Screen.AiFailed(raw, message)
            }
        }
    }

    fun openFromGallery(file: File) {
        scope.launch {
            val raw = withContext(Dispatchers.IO) { runCatching { PhotoStore.load(file) }.getOrNull() }
            if (raw == null) {
                notice = Notice("Fotku se nepodařilo otevřít.")
                return@launch
            }
            val (mail, print) = prepare(raw)
            if (screen != Screen.Gallery) return@launch // mezitím zpět na úvod (nečinnost)
            fromGallery = true
            printPhoto = print
            screen = Screen.Review(mail)
        }
    }

    fun shoot() {
        if (screen != Screen.Idle) return
        val seconds = settings.countdown.coerceIn(0, 10)
        // Obrazovku přepnout hned (ne až v korutině) – jinak dvojité klepnutí spustí dva odpočty a dvě focení.
        screen = Screen.Countdown(seconds)
        scope.launch {
            val lead = settings.shutterLeadMs.coerceIn(0, 900).toLong()
            val end = SystemClock.elapsedRealtime() + seconds * 1000L
            suspend fun waitUntil(t: Long) = delay((t - SystemClock.elapsedRealtime()).coerceAtLeast(0))

            // Výsledek focení jako Result – chyba nesmí shodit celý scope obrazovky.
            var photo: Deferred<Result<Bitmap>>? = null
            fun fire() {
                photo = async { runCatching { camera.takePhoto() } }
            }

            for (n in seconds downTo 1) {
                screen = Screen.Countdown(n)
                if (n == 1) {
                    // Zrcadlovka: namáčknout spoušť (zaostřit) chvíli před koncem
                    waitUntil(end - PREFOCUS_MS)
                    camera.prefocus()
                    // Spoušť s předstihem – kompenzuje prodlevu fotoaparátu, fotka vznikne s „Úsměv!“
                    waitUntil(end - lead)
                    fire()
                }
                waitUntil(end - (n - 1) * 1000L)
            }
            screen = Screen.Countdown(0)
            flash.snapTo(1f)
            launch { flash.animateTo(0f, tween(600)) }
            if (photo == null) fire()

            val result = photo!!.await()
            // „Úsměv!“ ať je vidět aspoň chvilku, i když je fotka hotová hned
            waitUntil(end + 300)
            result.onSuccess { raw ->
                // Galerie (když je zapnutá): fotka bez loga, aby šla znovu tisknout i posílat.
                saveToGallery(raw)
                ReportWorker.photo(context, settings)
                // Mezistav: pořízená fotka se „vyvolá“, mezitím se na pozadí připraví logo a tisková verze.
                val developing = Screen.Developing(raw)
                screen = developing
                val shownAt = SystemClock.elapsedRealtime()
                if (aiActive()) {
                    waitUntil(shownAt + DEVELOP_MIN_MS)
                    if (screen !== developing) return@onSuccess

                    // AI FOTOKIOSEK: nejdřív výběr stylu.
                    aiRaw = raw
                    aiLast = null
                    aiTries = 0
                    screen = Screen.AiPick(raw)
                    return@onSuccess
                }
                val (final, forPrint) = prepare(raw)
                waitUntil(shownAt + DEVELOP_MIN_MS)
                if (screen !== developing) return@onSuccess // mezitím jinam (nečinnost, správce)
                printPhoto = forPrint
                screen = Screen.Review(final)
            }.onFailure { e ->
                if (e is CancellationException) throw e
                // Skutečná příčina do logu i do „Poslední chyby“ v menu správce; zákazník vidí jen obecnou hlášku.
                val reason = AppLog.causes(e)
                AppLog.e("Focení", "Fotka se nepovedla (zdroj: ${camera.source}): $reason", e)
                settings.lastError = "${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}: Focení: $reason"
                notice = Notice("Fotku se nepodařilo pořídit. Zkus to prosím znovu.")
                goIdle()
            }
        }
    }

    fun send(photo: Bitmap, email: String, paid: BigDecimal? = null) {
        // Zaplacené se pošle vždy (limit adres se kontroloval před placením).
        if (!RateLimit.allow(email) && paid == null) {
            notice = Notice("Na tuhle adresu už odešlo hodně fotek. Zkus to prosím později.")
            return
        }
        screen = Screen.Sent
        val style = shownStyle
        val source = if (fromGallery) "galerie" else "foto"
        scope.launch(Dispatchers.IO) {
            try {
                val file = PhotoStore.save(context, photo)
                SendPhotoWorker.enqueue(context, file, email)
                AppLog.i("Fronta", "Fotka ${file.name} zařazena k odeslání")
                ReportWorker.email(context, settings, source, style, paid)
            } catch (e: Exception) {
                settings.lastError = "Uložení fotky selhalo: ${e.message}" + paidNote(paid)
                AppLog.e("Fronta", "Uložení fotky selhalo", e)
            }
        }
    }

    /**
     * Samoobslužný režim platí pro zákazníka. Tisk / e-mail z galerie se neplatí jen u galerie chráněné PINem
     * (obsluha) – galerie bez PINu by jinak byla cesta, jak tisknout zdarma.
     */
    fun selfService() = settings.selfService && !(fromGallery && settings.galleryRequirePin)

    /** Proč teď v samoobsluze nejde tisknout (kontrola PŘED placením), nebo null. */
    fun selfServicePrintProblem(): String? = when {
        settings.printMode == "system" -> "Tisk přes systémový dialog v samoobsluze nejde. Zavolej prosím obsluhu."
        settings.printMode != "selphy_usb" && settings.printerUrl.isBlank() -> "Tiskárna není nastavená. Zavolej prosím obsluhu."
        settings.printMode == "selphy_usb" && SelphyUsb.problem != null -> "Tiskárna: ${SelphyUsb.problem}. Zavolej prosím obsluhu."
        else -> null
    }

    /**
     * Cena akce v samoobslužném režimu: [base] (tisk × kopie, e-mail) + AI fotka, pokud je na obrazovce
     * a ještě není zaplacená (AI se platí, až když se výsledek líbí). null = cena chybí → neprodávat.
     * Druhá hodnota = doplněk popisu („ + AI úprava“).
     */
    fun selfServicePrice(base: BigDecimal?): Pair<BigDecimal, String>? {
        if (base == null) return null
        val ai = shownAi
        if (ai == null || ai === aiPaidFor) return base to ""
        val aiPrice = Pricing.parse(settings.priceAi) ?: return null
        return base.add(aiPrice) to " + AI úprava"
    }

    fun missingPrice() {
        AppLog.w("Platba", "Samoobslužný režim: chybí cena (synchronizuj styly a pravidla z webu)")
        notice = Notice("Cena není nastavená. Zavolej prosím obsluhu.")
    }

    /** Zaplatit [total] (0 = zdarma → hned [onPaid]); obrazovka platby, po zaplacení zpět na [back] a [onPaid]. */
    fun pay(total: BigDecimal, description: String, back: Screen, onPaid: () -> Unit) {
        if (screen is Screen.Payment) return // dvojí klepnutí – terminál smí mít jen jednu platbu
        val ai = shownAi
        val paidAction = {
            if (ai != null) aiPaidFor = ai
            onPaid()
        }
        if (total.signum() == 0) {
            paidAction()
            return
        }
        val request = PaymentRequest(total, settings.priceCurrency, description)
        val paying = Screen.Payment(request, back, paidAction)
        screen = paying
        AppLog.i("Platba", "Platba ${Pricing.plain(total)} ${request.currency} (${terminal.name}): $description")
        // Obrazovku platby opustil cokoli jiného (nečinnost, menu správce) → platbu v terminálu zrušit.
        val watcher = scope.launch {
            snapshotFlow { screen }.first { it !== paying }
            terminal.cancel()
        }
        scope.launch {
            val result = terminal.pay(request)
            watcher.cancel()
            if (screen !== paying) {
                // Zaplaceno těsně před zrušením (nečinnost, menu správce): zaplacené se musí dodat.
                if (result == PaymentResult.Paid) {
                    AppLog.w("Platba", "Zaplaceno až po opuštění obrazovky platby – služba se dodává")
                    settings.lastError = "Platba ${Pricing.plain(total)} ${request.currency} přišla až po zrušení obrazovky platby – služba dodána dodatečně, zkontroluj"
                    paying.onPaid()
                }
                return@launch
            }
            when (result) {
                PaymentResult.Paid -> {
                    AppLog.i("Platba", "Zaplaceno: ${Pricing.plain(total)} ${request.currency}")
                    screen = back
                    paying.onPaid()
                }
                PaymentResult.Cancelled -> {
                    AppLog.i("Platba", "Platba zrušena")
                    screen = back
                }
                is PaymentResult.Failed -> {
                    AppLog.w("Platba", "Platba selhala: ${result.message}")
                    screen = back
                    notice = Notice("Platba se nepovedla: ${result.message}")
                }
            }
        }
    }

    fun print(photo: Bitmap, copies: Int, paid: BigDecimal? = null) {
        printFor = null
        // Pro účetnictví zachytit hned – výsledek tisku přijde později, kdy už může být jiná obrazovka.
        val reportSource = if (fromGallery) "galerie" else "foto"
        val reportStyle = shownStyle
        val job = "Fotokiosek ${System.currentTimeMillis() / 1000}"
        // Vždy 10×15 cm při 300 DPI, bez okrajů (rozměr podle modelu tiskárny).
        val media = PrintMedia.forSettings(settings)
        if (settings.printMode == "system") {
            // Na některých deskách tiskový systém Androidu chybí – chyba nesmí shodit kiosek.
            runCatching { (context as? Activity)?.let { SystemPrint.print(it, PrintLayout.fit(photo, media), copies, job, media) } }
                .onSuccess {
                    settings.printCount += copies
                    ReportWorker.print(context, settings, copies, reportSource, reportStyle, paid)
                }
                .onFailure { e ->
                    AppLog.e("Tisk", "Systémový tisk nejde spustit", e)
                    settings.lastError = "Tisk: ${e.message}" + paidNote(paid)
                    notice = Notice(printFailedText(paid))
                }
            return
        }
        val usbSelphy = settings.printMode == "selphy_usb"
        if (!usbSelphy && settings.printerUrl.isBlank()) {
            if (paid != null) settings.lastError = "Tisk: tiskárna není nastavená" + paidNote(paid)
            notice = Notice(if (paid != null) printFailedText(paid) else "Tiskárna není nastavená. Požádej obsluhu.")
            return
        }
        printing = true
        notice = Notice(if (copies == 1) "Tisknu fotku…" else "Tisknu $copies kopie…", error = false)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (usbSelphy) {
                    SelphyUsb.print(context, photo, copies) { settings.lastError = "Tisk dalších kopií: $it" }
                } else {
                    val jpeg = ByteArrayOutputStream().use { out ->
                        PrintLayout.fit(photo, media).compress(Bitmap.CompressFormat.JPEG, 95, out)
                        out.toByteArray()
                    }
                    IppClient.print(settings.printerUrl, jpeg, copies, job, media)
                }
            }
            printing = false
            if (result.ok) {
                settings.printCount += copies
                ReportWorker.print(context, settings, copies, reportSource, reportStyle, paid)
                notice = Notice("Fotka se tiskne, vyzvedni si ji u tiskárny.", error = false)
            } else {
                settings.lastError = "Tisk: ${result.message}" + paidNote(paid)
                notice = Notice(printFailedText(paid))
            }
        }
    }

    // Po nečinnosti zpět na úvodní obrazovku
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            val idleSeconds = (SystemClock.elapsedRealtime() - lastTouch.value) / 1000
            val limit = if (screen == Screen.Admin) 180 else settings.idleTimeout.coerceAtLeast(10)
            // Odpočet se neruší; čekání na AI má delší limit (síť může viset déle než timeout klienta).
            val effectiveLimit = when (screen) {
                is Screen.AiWorking -> AI_WORKING_LIMIT_S
                is Screen.Payment -> PAYMENT_LIMIT_S // nezaplaceno včas → zrušit (goIdle zruší i terminál)
                else -> limit
            }
            if (screen != Screen.Idle && screen !is Screen.Countdown && idleSeconds >= effectiveLimit) goIdle()
            if ((askPin || askGalleryPin) && idleSeconds >= 30) {
                askPin = false
                askGalleryPin = false
            }
        }
    }
    LaunchedEffect(screen) {
        // Nová obrazovka = čas nečinnosti od nuly (jinak by např. AI výsledek po dlouhém čekání hned zmizel).
        lastTouch.longValue = SystemClock.elapsedRealtime()
        if (screen == Screen.Sent) {
            delay(8000)
            goIdle()
        }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(5000)
            notice = null
        }
    }

    KioskTheme {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            // Živý náhled běží pořád, ostatní obrazovky ho překrývají.
            // Při zapnutém tisku čáry, co se vytiskne (jen na úvodní obrazovce a při odpočtu).
            val showGuide = settings.printEnabled && (screen == Screen.Idle || screen is Screen.Countdown)
            LivePreview(camera, settings.fillScreen, if (showGuide) PrintMedia.forSettings(settings) else null)

            when (val s = screen) {
                Screen.Idle -> IdleScreen(
                    settings, camera.error,
                    onShoot = { shoot() },
                    onGallery = if (settings.galleryEnabled) ({ openGallery() }) else null,
                )
                is Screen.Countdown -> CountdownOverlay(s.value)
                is Screen.Developing -> DevelopingScreen(s.raw, settings.fillScreen)
                is Screen.Review -> ReviewScreen(
                    photo = printPhoto ?: s.photo,
                    watermark = selfService(),
                    canPrint = settings.printEnabled,
                    printing = printing,
                    retakeLabel = if (fromGallery) "Galerie" else "Znovu",
                    onRetake = {
                        // Zpět do galerie (s PINem, když je vyžadovaný) – fotku mohl mezitím převzít zákazník.
                        val back = fromGallery
                        goIdle()
                        if (back) openGallery()
                    },
                    onPrint = { printFor = printPhoto ?: s.photo },
                    onSend = { screen = Screen.Email(s.photo) },
                    extra = aiRaw?.let { raw ->
                        listOfNotNull(
                            if (aiTriesLeft() > 0 || aiLast != null) "Zkusit jiný styl" to { printPhoto = null; screen = Screen.AiPick(raw) } else null,
                            "Použít původní fotku" to { showResult(raw) },
                        )
                    }.orEmpty(),
                )
                is Screen.Email -> EmailScreen(
                    initial = s.email,
                    onBack = { screen = Screen.Review(s.photo) },
                    onDone = { email -> screen = Screen.Confirm(s.photo, email) },
                )
                is Screen.Confirm -> ConfirmScreen(
                    email = s.email,
                    price = if (!selfService()) null else selfServicePrice(Pricing.parse(settings.priceEmail))
                        ?.takeIf { it.first.signum() > 0 }
                        ?.let { (total, extra) -> "Cena: " + Pricing.format(total, settings.priceCurrency) + if (extra.isNotEmpty()) " (včetně AI úpravy)" else "" },
                    onEdit = { screen = Screen.Email(s.photo, s.email) },
                    onConfirm = {
                        if (!selfService()) {
                            send(s.photo, s.email)
                        } else {
                            val price = selfServicePrice(Pricing.parse(settings.priceEmail))
                            when {
                                price == null -> missingPrice()
                                // Limit adres zkontrolovat před placením (send by jinak odmítl už zaplacenou fotku).
                                !RateLimit.wouldAllow(s.email) ->
                                    notice = Notice("Na tuhle adresu už odešlo hodně fotek. Zkus to prosím později.")
                                else -> pay(price.first, "Poslání fotky na e-mail${price.second}", s) { send(s.photo, s.email, price.first) }
                            }
                        }
                    },
                )
                is Screen.Payment -> PaymentScreen(s.request, terminal, onCancel = { terminal.cancel() })
                Screen.Sent -> SentScreen(onDone = { goIdle() })
                is Screen.AiPick -> AiPickScreen(
                    styles = remember(s) { aiStyles() },
                    triesLeft = aiTriesLeft(),
                    lastResult = aiLast,
                    onPick = { pickAiStyle(s.raw, it) },
                    onOriginal = { showResult(s.raw) },
                    onLastResult = { aiLast?.let { showResult(it, aiLastStyle) } },
                )
                is Screen.AiWorking -> AiWorkingScreen(s.style, onCancel = { screen = Screen.AiPick(s.raw) })
                is Screen.AiFailed -> AiFailedScreen(
                    message = s.message,
                    canTryAgain = aiTriesLeft() > 0,
                    onOriginal = { showResult(s.raw) },
                    onOtherStyle = { screen = Screen.AiPick(s.raw) },
                    onLastResult = aiLast?.let { last -> { showResult(last, aiLastStyle) } },
                )
                Screen.Gallery -> GalleryScreen(
                    settings.keepDays,
                    canDelete = settings.galleryRequirePin,
                    // Dialog je samostatné okno – jeho dotyky nenulují nečinnost, tak aspoň při otevření.
                    onActivity = { lastTouch.longValue = SystemClock.elapsedRealtime() },
                    onOpen = { openFromGallery(it) }, onClose = { goIdle() })
                Screen.Admin -> AdminScreen(
                    settings, camera, pickLogo,
                    onActivity = { lastTouch.longValue = SystemClock.elapsedRealtime() },
                    onClose = { goIdle() },
                    setAutoSave = { adminAutoSave = it },
                )
            }

            // Tiskárna čeká na obsluhu (došel papír, páska…) – drží se na obrazovce, dokud to někdo nevyřeší.
            SelphyUsb.problem?.let { problem ->
                if (screen != Screen.Admin) {
                    Text(
                        "Tiskárna: $problem.\nZavolej prosím obsluhu – tisk pak sám pokračuje.",
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(start = 140.dp, end = 140.dp, top = 16.dp)
                            .fillMaxWidth()
                            .background(Color(0xFFB26A00), RoundedCornerShape(16.dp))
                            .padding(20.dp),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (screen != Screen.Admin) {
                AdminCorner(Modifier.align(Alignment.TopStart)) {
                    if (!pinLocked()) askPin = true
                }
            }
            if (askPin) {
                PinPad(
                    onCancel = { askPin = false },
                    onEnter = { pin ->
                        pinResult(pin == settings.pin).also { ok ->
                            if (ok) {
                                askPin = false
                                screen = Screen.Admin
                            }
                        }
                    },
                )
            }
            if (askGalleryPin) {
                PinPad(
                    onCancel = { askGalleryPin = false },
                    title = "PIN pro galerii",
                    onEnter = { pin ->
                        // Galerii otevře vlastní PIN galerie (když je nastavený) i PIN správce.
                        val ok = (settings.galleryPin.isNotEmpty() && pin == settings.galleryPin) || pin == settings.pin
                        pinResult(ok).also {
                            if (it) {
                                askGalleryPin = false
                                screen = Screen.Gallery
                            }
                        }
                    },
                )
            }


            printFor?.let { photo ->
                val self = selfService()
                val unit = if (self) Pricing.parse(settings.pricePhoto) else null
                CopiesDialog(
                    max = settings.maxCopies(),
                    // Samoobsluha: cena za zvolený počet kopií (+ AI úprava, když ještě není zaplacená).
                    price = if (!self) null else { copies ->
                        selfServicePrice(unit?.multiply(BigDecimal(copies)))
                            ?.let { (total, extra) -> "Cena: " + Pricing.format(total, settings.priceCurrency) + if (extra.isNotEmpty()) " (včetně AI úpravy)" else "" }
                    },
                    onCancel = { printFor = null },
                    onConfirm = { copies ->
                        if (!self) {
                            print(photo, copies)
                        } else {
                            val price = selfServicePrice(unit?.multiply(BigDecimal(copies)))
                            val problem = selfServicePrintProblem()
                            printFor = null
                            if (price == null) missingPrice()
                            else if (problem != null) notice = Notice(problem)
                            else pay(price.first, "Tisk fotky 10×15 – $copies ks${price.second}", screen) { print(photo, copies, price.first) }
                        }
                    },
                )
            }

            notice?.let {
                Text(
                    it.text,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 140.dp, start = 24.dp, end = 24.dp)
                        .background(if (it.error) Color(0xFFC62828) else Color(0xFF2E7D32), RoundedCornerShape(16.dp))
                        .padding(horizontal = 28.dp, vertical = 16.dp),
                    fontSize = 26.sp,
                    textAlign = TextAlign.Center,
                )
            }

            if (flash.value > 0f) {
                Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value)))
            }
        }
    }
}

private const val PREFOCUS_MS = 800L
/** Jak dlouho nejmíň běží animace „vyvolávání“ (aby nebyla jen probliknutím). */
private const val DEVELOP_MIN_MS = 900L
private const val MAX_PIN_FAILURES = 5
private const val PIN_LOCK_MS = 5 * 60_000L
/** Po kolika s nečinnosti se zruší čekání na AI (klient má limit 120 s na čtení). */
private const val AI_WORKING_LIMIT_S = 180
/** Po kolika s nečinnosti se zruší čekání na platbu. */
private const val PAYMENT_LIMIT_S = 120

private fun paidNote(paid: BigDecimal?) =
    if (paid != null) " (ZAPLACENO ${Pricing.plain(paid)} – vrátit zákazníkovi, nebo vytisknout z galerie)" else ""

private fun printFailedText(paid: BigDecimal?) =
    if (paid != null) "Tisk se nepovedl. Platba proběhla – zavolej prosím obsluhu." else "Tisk se nepovedl. Požádej obsluhu."


/**
 * Živý náhled – snímky jsou už otočené a zrcadlené stejně jako výsledná fotka
 * (viz KioskCamera.orient / CanonCamera.decode), takže tady se nic neotáčí.
 */
@Composable
private fun LivePreview(camera: CameraController, fill: Boolean, printMedia: PrintMedia?) {
    Box(
        Modifier.fillMaxSize().onSizeChanged { camera.setViewSize(it.width, it.height) },
        contentAlignment = Alignment.Center,
    ) {
        val frame = camera.frame
        if (frame != null) {
            Image(
                bitmap = frame,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = if (fill) ContentScale.Crop else ContentScale.Fit,
            )
            if (printMedia != null) {
                val screenShaped = fill && camera.source == CameraController.Source.ANDROID
                PrintAreaGuide(frame.width, frame.height, fill, screenShaped, printMedia)
            }
        } else if (camera.error == null) {
            Text("Spouštím kameru…", color = Color.White.copy(alpha = 0.5f), fontSize = 24.sp)
        }
    }
}

/**
 * Čáry ohraničující část obrazu, která se vytiskne (fotka se pro tisk ořízne na tvar papíru 10×15,
 * viz PrintLayout.forPrint).
 * [screenShaped] = fotka má přesně tvar obrazovky (vestavěná kamera + „přes celou obrazovku“ → ViewPort),
 * pak se počítá z obrazovky a nevadí ani otočení náhledu. Jinak se bere tvar náhledu (zrcadlovka, režim Fit).
 */
@Composable
private fun PrintAreaGuide(frameW: Int, frameH: Int, fill: Boolean, screenShaped: Boolean, media: PrintMedia) {
    Spacer(
        Modifier
            .fillMaxSize()
            // Vlastní vrstva + mezipaměť: nový snímek kamery (~20× za s) čáry nepřekresluje.
            .graphicsLayer()
            .drawWithCache {
                val w = size.width
                val h = size.height
                // Kde na obrazovce leží fotka (Crop = přesahuje plochu, Fit = vejde se celá).
                val imgW: Float
                val imgH: Float
                if (screenShaped || frameW <= 0 || frameH <= 0) {
                    imgW = w; imgH = h
                } else {
                    val scale = if (fill) max(w / frameW, h / frameH) else min(w / frameW, h / frameH)
                    imgW = frameW * scale; imgH = frameH * scale
                }
                // Papír ve stejné orientaci jako fotka (stejně jako PrintLayout.fit), ořez uprostřed.
                val paper = if (imgW >= imgH) media.longPx.toFloat() / media.shortPx else media.shortPx.toFloat() / media.longPx
                val wider = imgW / imgH > paper
                val printW = if (wider) imgH * paper else imgW
                val printH = if (wider) imgH else imgW / paper
                val left = (w - printW) / 2f
                val top = (h - printH) / 2f
                val right = left + printW
                val bottom = top + printH
                val shade = Color.Black.copy(alpha = 0.35f)
                val stroke = 4.dp.toPx()
                val dash = PathEffect.dashPathEffect(floatArrayOf(28.dp.toPx(), 16.dp.toPx()))
                // Čáry jen tam, kde je hrana uvnitř obrazovky (hrana na okraji displeje nic neříká).
                val vertical = left > 1f
                val horizontal = top > 1f
                onDrawBehind {
                    // Ztmavit, co se nevytiskne (uvnitř oblasti zůstane obraz beze změny).
                    if (vertical) {
                        drawRect(shade, Offset.Zero, Size(left, h))
                        drawRect(shade, Offset(right, 0f), Size(w - right, h))
                    }
                    if (horizontal) {
                        val x0 = left.coerceAtLeast(0f)
                        val x1 = right.coerceAtMost(w)
                        drawRect(shade, Offset(x0, 0f), Size(x1 - x0, top))
                        drawRect(shade, Offset(x0, bottom), Size(x1 - x0, h - bottom))
                    }
                    val y0 = top.coerceAtLeast(0f)
                    val y1 = bottom.coerceAtMost(h)
                    val x0 = left.coerceAtLeast(0f)
                    val x1 = right.coerceAtMost(w)
                    if (vertical) {
                        drawLine(Accent, Offset(left, y0), Offset(left, y1), stroke, pathEffect = dash)
                        drawLine(Accent, Offset(right, y0), Offset(right, y1), stroke, pathEffect = dash)
                    }
                    if (horizontal) {
                        drawLine(Accent, Offset(x0, top), Offset(x1, top), stroke, pathEffect = dash)
                        drawLine(Accent, Offset(x0, bottom), Offset(x1, bottom), stroke, pathEffect = dash)
                    }
                }
            },
    )
}

@Composable
private fun LogoBadge(modifier: Modifier) {
    val context = LocalContext.current
    val version = LogoStore.version
    // Logo se načítá na pozadí (velké PNG by na pomalé desce zaseklo úvodní obrazovku).
    val loaded by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, version) {
        value = withContext(Dispatchers.IO) { runCatching { LogoStore.load(context)?.asImageBitmap() }.getOrNull() }
    }
    val logo = loaded ?: return
    Image(
        bitmap = logo,
        contentDescription = "Logo",
        modifier = modifier.widthIn(max = 220.dp).heightIn(max = 120.dp),
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun IdleScreen(settings: KioskSettings, cameraError: String?, onShoot: () -> Unit, onGallery: (() -> Unit)?) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.TopEnd).padding(24.dp), horizontalAlignment = Alignment.End) {
            if (onGallery != null) {
                Text(
                    "🖼  Galerie",
                    modifier = Modifier
                        .clip(RoundedCornerShape(32.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .border(3.dp, Accent, RoundedCornerShape(32.dp))
                        .clickable(onClick = onGallery)
                        .padding(horizontal = 32.dp, vertical = 18.dp),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Spacer(Modifier.height(16.dp))
            }
            if (settings.logoOnScreen) LogoBadge(Modifier)
        }
        Text(
            settings.welcomeText,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp, start = if (onGallery != null) 280.dp else 120.dp, end = if (onGallery != null) 280.dp else 120.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
                .padding(horizontal = 32.dp, vertical = 16.dp),
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        if (cameraError != null) {
            Text(
                cameraError + "\n\nSprávce: 5× klepni do levého horního rohu.",
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp)
                    .background(Color(0xFF3A1010), RoundedCornerShape(16.dp))
                    .padding(32.dp),
                fontSize = 26.sp,
                textAlign = TextAlign.Center,
            )
        } else {
            ShutterButton(Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp), onShoot)
        }
    }
}

@Composable
private fun ShutterButton(modifier: Modifier, onClick: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = 1.07f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "scale",
    )
    Box(
        modifier
            .size(190.dp)
            .graphicsLayer {
                scaleX = pulse
                scaleY = pulse
            }
            .border(8.dp, Color.White, CircleShape)
            .padding(14.dp)
            .clip(CircleShape)
            .background(Accent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("VYFOTIT", color = Color.Black, fontSize = 30.sp, fontWeight = FontWeight.Black)
    }
}

/**
 * „Vyvolávání“ fotky po vyfocení: pořízený snímek vyjede z bílého záblesku přes vybledlou černobílou
 * do plných barev a lehce se usadí (zmenšení 1,06 → 1). Jen barevný filtr a měřítko jedné bitmapy
 * (žádné rozmazání – Android 7 ho neumí plynule), dole pulzující tečky.
 */
@Composable
private fun DevelopingScreen(raw: Bitmap, fill: Boolean) {
    val image = remember(raw) { raw.asImageBitmap() }
    val progress = remember(raw) { Animatable(0f) }
    LaunchedEffect(raw) { progress.animateTo(1f, tween(DEVELOP_MIN_MS.toInt(), easing = FastOutSlowInEasing)) }
    // Barvy se mění jen po dobu animace (~1 s); tečky překreslí obrazovku jen 3× za 1,2 s.
    val filter = ColorFilter.colorMatrix(developMatrix(progress.value))
    val dots = rememberInfiniteTransition(label = "dots")
    val phase by dots.animateFloat(0f, 3f, infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "phase")
    val dot by remember { derivedStateOf { phase.toInt() } }
    Box(Modifier.fillMaxSize().background(Dark), contentAlignment = Alignment.Center) {
        Image(
            image,
            contentDescription = "Pořízená fotka",
            contentScale = if (fill) ContentScale.Crop else ContentScale.Fit,
            colorFilter = filter,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val scale = 1.06f - 0.06f * progress.value
                scaleX = scale
                scaleY = scale
            },
        )
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(32.dp))
                .padding(horizontal = 32.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Připravuji fotku", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            repeat(3) { i ->
                val on = dot == i
                Box(Modifier.size(14.dp).clip(CircleShape).background(if (on) Accent else Color.White.copy(alpha = 0.35f)))
            }
        }
    }
}

/** Barevná matice „vyvolávání“: t = 0 vybledlá černobílá, t = 1 původní barvy. */
private fun developMatrix(t: Float): ColorMatrix {
    val s = t // sytost 0 → 1
    val wash = (1f - t) * 0.55f // podíl bílé
    val k = 1f - wash
    val off = 255f * wash
    val lr = 0.213f * (1 - s); val lg = 0.715f * (1 - s); val lb = 0.072f * (1 - s)
    return ColorMatrix(
        floatArrayOf(
            (lr + s) * k, lg * k, lb * k, 0f, off,
            lr * k, (lg + s) * k, lb * k, 0f, off,
            lr * k, lg * k, (lb + s) * k, 0f, off,
            0f, 0f, 0f, 1f, 0f,
        )
    )
}

@Composable
private fun CountdownOverlay(value: Int) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            if (value > 0) value.toString() else "Úsměv!",
            fontSize = if (value > 0) 240.sp else 120.sp,
            fontWeight = FontWeight.Black,
            style = TextStyle(shadow = Shadow(Color.Black, Offset(0f, 6f), 24f)),
        )
    }
}

@Composable
private fun ReviewScreen(
    photo: Bitmap,
    /** Samoobsluha: přes náhled vodoznak „NÁHLED“ (jen na obrazovce – tiskne a posílá se fotka bez něj). */
    watermark: Boolean,
    canPrint: Boolean,
    printing: Boolean,
    retakeLabel: String,
    onRetake: () -> Unit,
    onPrint: () -> Unit,
    onSend: () -> Unit,
    /** Další tlačítka nad hlavní řadou (AI: jiný styl / původní fotka). */
    extra: List<Pair<String, () -> Unit>> = emptyList(),
) {
    val image = remember(photo) { photo.asImageBitmap() }
    Column(
        Modifier.fillMaxSize().background(Dark).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Jak se ti líbí?", fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Image(
            bitmap = image,
            contentDescription = "Pořízená fotka",
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 24.dp)
                .then(if (watermark) Modifier.watermark(image.width, image.height) else Modifier),
            contentScale = ContentScale.Fit,
        )
        if (extra.isNotEmpty()) {
            Row(
                Modifier.widthIn(max = 1000.dp).fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                extra.forEach { (label, action) -> BigButton(label, Modifier.weight(1f), primary = false, onClick = action) }
            }
        }
        Row(
            Modifier.widthIn(max = if (canPrint) 1200.dp else 900.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            BigButton(retakeLabel, Modifier.weight(1f), primary = false, onClick = onRetake)
            if (canPrint) {
                BigButton(if (printing) "Tisknu…" else "Vytisknout", Modifier.weight(1.2f), enabled = !printing, onClick = onPrint)
            }
            BigButton("Poslat na e-mail", Modifier.weight(1.4f), onClick = onSend)
        }
    }
}

/**
 * Vodoznak „NÁHLED“ přes fotku: šikmé řádky textu jen v ploše obrázku (ContentScale.Fit → spočítat
 * obdélník fotky v prvku). Kreslí se na plátno (drawWithCache), samotná fotka zůstává bez vodoznaku.
 */
private fun Modifier.watermark(imageW: Int, imageH: Int): Modifier = drawWithCache {
    val scale = minOf(size.width / imageW, size.height / imageH)
    val w = imageW * scale
    val h = imageH * scale
    val left = (size.width - w) / 2
    val top = (size.height - h) / 2
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(110, 255, 255, 255)
        textSize = maxOf(w, h) / 9f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setShadowLayer(6f, 0f, 2f, android.graphics.Color.argb(90, 0, 0, 0))
    }
    val text = "NÁHLED"
    val step = paint.textSize * 2.2f
    val textW = paint.measureText(text)
    onDrawWithContent {
        drawContent()
        drawIntoCanvas { canvas ->
            val c = canvas.nativeCanvas
            c.save()
            c.clipRect(left, top, left + w, top + h)
            c.rotate(-30f, left + w / 2, top + h / 2)
            val span = maxOf(w, h) * 1.5f
            var y = top + h / 2 - span
            var row = 0
            while (y < top + h / 2 + span) {
                var x = left + w / 2 - span + (row % 2) * step
                while (x < left + w / 2 + span) {
                    c.drawText(text, x, y, paint)
                    x += textW + step
                }
                y += step
                row++
            }
            c.restore()
        }
    }
}

/**
 * Samoobsluha – platba: částka, co se platí, stav terminálu, Zrušit. U zkušebního terminálu
 * navíc tlačítka pro simulaci výsledku (než se napojí skutečný terminál).
 */
@Composable
private fun PaymentScreen(request: PaymentRequest, terminal: PaymentTerminal, onCancel: () -> Unit) {
    BackHandler(onBack = onCancel)
    Column(
        Modifier.fillMaxSize().background(Dark).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Platba kartou", fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(request.description, fontSize = 28.sp, textAlign = TextAlign.Center, color = Color.White.copy(alpha = 0.8f))
        Spacer(Modifier.height(24.dp))
        Text(Pricing.format(request.amount, request.currency), color = Accent, fontSize = 96.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(24.dp))
        val dots = rememberInfiniteTransition(label = "pay")
        val pulse by dots.animateFloat(0.4f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
        Text(
            terminal.status.ifEmpty { "Čekám na platbu…" } + "\nPřilož kartu nebo telefon k terminálu.",
            fontSize = 30.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.graphicsLayer { alpha = pulse },
        )
        Spacer(Modifier.height(48.dp))
        if (terminal is MockTerminal) {
            Text("Zkušební terminál – nic se neúčtuje", fontSize = 20.sp, color = Color(0xFFFFB74D))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.widthIn(max = 1000.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                BigButton("Simulovat úspěšnou platbu", Modifier.weight(1.4f)) { terminal.simulate(PaymentResult.Paid) }
                BigButton("Simulovat chybu", Modifier.weight(1f), primary = false) { terminal.simulate(PaymentResult.Failed("terminál odmítl kartu (simulace)")) }
            }
            Spacer(Modifier.height(24.dp))
        }
        BigButton("Zrušit", Modifier.widthIn(max = 500.dp).fillMaxWidth(), primary = false, onClick = onCancel)
    }
}

/** Náhledy galerie se dekódují nejvýš dva najednou (rychlé posouvání by jinak spustilo desítky naráz). */
private val ThumbnailDispatcher = java.util.concurrent.Executors.newFixedThreadPool(2).asCoroutineDispatcher()

/**
 * Galerie pro obsluhu: mřížka fotek (nejnovější první), klepnutí = otevřít jako po vyfocení (tisk / e-mail).
 * Mazat (jednu fotku nebo všechny, vždy s potvrzením) jde jen při [canDelete] = galerie chráněná PINem.
 */
@Composable
private fun GalleryScreen(keepDays: Int, canDelete: Boolean, onActivity: () -> Unit, onOpen: (File) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)
    var files by remember { mutableStateOf<List<File>?>(null) }
    var askDelete by remember { mutableStateOf<File?>(null) }
    var askDeleteAll by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        files = withContext(Dispatchers.IO) { runCatching { PhotoStore.gallery(context, keepDays) }.getOrDefault(emptyList()) }
    }
    Column(Modifier.fillMaxSize().background(Dark).padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Galerie", fontSize = 40.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (canDelete && !files.isNullOrEmpty()) {
                BigButton("🗑 Smazat vše", Modifier.width(280.dp), primary = false) { onActivity(); askDeleteAll = true }
            }
            BigButton("Zavřít", Modifier.width(220.dp), primary = false, onClick = onClose)
        }
        deleteError?.let { Text(it, fontSize = 22.sp, color = Color(0xFFFF6E6E), modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))
        val list = files
        when {
            list == null -> Text("Načítám fotky…", fontSize = 26.sp)
            list.isEmpty() -> Text("Zatím tu nejsou žádné fotky.", fontSize = 26.sp)
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(240.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list, key = { it.name }) { file ->
                    GalleryItem(file, onClick = { onOpen(file) }, onDelete = if (canDelete) ({ onActivity(); askDelete = file }) else null)
                }
            }
        }
    }

    askDelete?.let { file ->
        ConfirmDialog(
            title = "Smazat tuto fotku?",
            text = "Fotka z ${galleryTime(file)} zmizí z galerie. Vrátit to nejde.",
            confirm = "Smazat",
            onCancel = { askDelete = null },
        ) {
            askDelete = null
            scope.launch {
                val ok = withContext(Dispatchers.IO) { PhotoStore.deleteFromGallery(context, file) }
                if (ok) files = files?.minus(file) else deleteError = "Fotku se nepodařilo smazat."
            }
        }
    }
    if (askDeleteAll) {
        ConfirmDialog(
            title = "Smazat celou galerii?",
            text = "Smažou se všechny fotky v galerii (${files?.size ?: 0}). Vrátit to nejde.",
            confirm = "Smazat vše",
            onCancel = { askDeleteAll = false },
        ) {
            askDeleteAll = false
            scope.launch {
                files = withContext(Dispatchers.IO) {
                    PhotoStore.clearGallery(context)
                    runCatching { PhotoStore.gallery(context, keepDays) }.getOrDefault(emptyList())
                }
                if (!files.isNullOrEmpty()) deleteError = "Některé fotky se nepodařilo smazat."
            }
        }
    }
}

private fun galleryTime(file: File): String =
    java.text.SimpleDateFormat("d. M. HH:mm", java.util.Locale.getDefault()).format(java.util.Date(file.lastModified()))

/** Potvrzovací dialog ve stylu kiosku (velké písmo a tlačítka). */
@Composable
private fun ConfirmDialog(title: String, text: String, confirm: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(620.dp).background(Dark, RoundedCornerShape(28.dp)).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Text(text, fontSize = 24.sp, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                BigButton("Zrušit", Modifier.weight(1f), primary = false, onClick = onCancel)
                BigButton(confirm, Modifier.weight(1f), onClick = onConfirm)
            }
        }
    }
}

@Composable
private fun GalleryItem(file: File, onClick: () -> Unit, onDelete: (() -> Unit)?) {
    // Náhled se načte zmenšený na pozadí, až když je položka vidět.
    val thumb by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file) {
        value = withContext(ThumbnailDispatcher) { runCatching { PhotoStore.thumbnail(file, 400)?.asImageBitmap() }.getOrNull() }
    }
    val time = remember(file) { galleryTime(file) }
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).background(KeyColor).clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
            thumb?.let { Image(it, contentDescription = "Fotka $time", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            if (onDelete != null) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .clickable(onClick = onDelete),
                    contentAlignment = Alignment.Center,
                ) { Text("🗑", fontSize = 28.sp) }
            }
        }
        Text(time, fontSize = 20.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Dialog s počtem kopií: − / + (1 až [max]) a potvrzení. */
@Composable
/** [price] = samoobsluha: text ceny za počet kopií (null z lambdy = cena chybí → nejde zaplatit). */
private fun CopiesDialog(max: Int, price: ((Int) -> String?)? = null, onCancel: () -> Unit, onConfirm: (Int) -> Unit) {
    var copies by remember { mutableIntStateOf(1) }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(560.dp)
                .background(Dark, RoundedCornerShape(28.dp))
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Kolik kopií vytisknout?", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                RoundKey("−", enabled = copies > 1) { copies-- }
                Text(copies.toString(), fontSize = 80.sp, fontWeight = FontWeight.Black, color = Accent, modifier = Modifier.width(90.dp), textAlign = TextAlign.Center)
                RoundKey("+", enabled = copies < max) { copies++ }
            }
            Text("max. $max", fontSize = 20.sp, color = Color.White.copy(alpha = 0.6f))
            val priceText = price?.invoke(copies)
            if (price != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    priceText ?: "Cena není nastavená – zavolej obsluhu",
                    fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    color = if (priceText != null) Accent else Color(0xFFFF6E6E),
                )
            }
            Spacer(Modifier.height(28.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                BigButton("Zrušit", Modifier.weight(1f), primary = false, onClick = onCancel)
                BigButton(
                    if (price != null) "Zaplatit" else "Vytisknout", Modifier.weight(1.3f),
                    enabled = price == null || priceText != null,
                ) { onConfirm(copies) }
            }
        }
    }
}

@Composable
private fun RoundKey(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(110.dp)
            .clip(CircleShape)
            .background(if (enabled) KeyColor else KeyColor.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 56.sp, fontWeight = FontWeight.Bold, color = if (enabled) Color.White else Color.Gray)
    }
}

@Composable
private fun ConfirmScreen(email: String, price: String? = null, onEdit: () -> Unit, onConfirm: () -> Unit) {
    BackHandler(onBack = onEdit)
    Column(
        Modifier.fillMaxSize().background(Dark).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Pošleme fotku na:", fontSize = 34.sp)
        Spacer(Modifier.height(16.dp))
        Text(email, color = Accent, fontSize = 46.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text("Je adresa správně?", fontSize = 30.sp)
        if (price != null) {
            Spacer(Modifier.height(24.dp))
            Text(price, color = Accent, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(48.dp))
        Row(
            Modifier.widthIn(max = 900.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            BigButton("Opravit", Modifier.weight(1f), primary = false, onClick = onEdit)
            BigButton(if (price != null) "Zaplatit a poslat" else "Ano, poslat", Modifier.weight(1f), onClick = onConfirm)
        }
    }
}

@Composable
private fun SentScreen(onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Dark).clickable(onClick = onDone).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Hotovo!", color = Accent, fontSize = 80.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(24.dp))
        Text(
            "Fotka je na cestě do tvého e-mailu.\nKdyby nedorazila, mrkni i do spamu.",
            fontSize = 32.sp,
            textAlign = TextAlign.Center,
        )
    }
}
