# Fotokiosek – kontext projektu pro Clauda

## O co jde
Android aplikace, která z **bývalého automatu na e-cigarety** (Android deska s dotykovým displejem a kamerou) dělá **fotokoutek**:
úvodní obrazovka se živým obrazem → **VYFOTIT** → odpočet 3-2-1 → náhled fotky → zadání e-mailu → kontrola adresy → fotka odejde e-mailem.
Součástí je skryté **menu správce** (admin) a instalační skript pro Windows, který automat „přeprogramuje“ přes adb.

- Autor/majitel: Dominik (dominik.perlik@gmail.com), Windows PC, projekt leží v `C:\Users\domin\Desktop\Fotokiosek\Fotokiosek\`.
- **GitHub:** veřejný repozitář `dominikperlik-png/fotokiosek` (větev `main`). Push → GitHub Actions sestaví podepsané APK → release `vNNN` → automat se aktualizuje tlačítkem v menu správce.
- Aplikaci i instalaci i návod (POSTUP.html) napsal dřív Claude. Komunikace a všechny texty v aplikaci jsou **česky**.
- Cílový uživatel návodu je laik → texty v UI a v návodu psát jednoduše, bez žargonu.

## Struktura složky
| Soubor / složka | K čemu |
|---|---|
| `app/` | Zdrojáky (Kotlin + Jetpack Compose), balík `cz.fotokiosek` |
| `Fotokiosek.apk` | Hotové sestavené APK (instalace ho bere odsud, případně z `app/build/outputs/apk/debug/app-debug.apk`) |
| `instalace.ps1` + `spustit-instalaci.bat` | Instalační skript (7 kroků, viz níže) |
| `config.example.properties` | Vzor nastavení; uživatel ho zkopíruje jako `config.properties` (je v .gitignore – obsahuje heslo) |
| `POSTUP.html` | Kompletní návod pro uživatele (checklist kroků 1–9, ovládání, řešení problémů, vrácení, úpravy) |
| `platform-tools/` | Přibalené adb.exe atd. (není v Gitu) |
| `.github/workflows/release.yml` | CI: build `assembleRelease` + `gh release create vNNN fotokiosek.apk` |
| `keystore.properties`, `fotokiosek-release.jks`, `github-secrets.txt` | Podpisový klíč – jen lokálně u uživatele + GitHub secrets, **nikdy do Gitu** |
| `README.md` | Přehled pro GitHub |
| `diagnostika.txt`, `zaloha/` | Vznikají při instalaci (v .gitignore) |

## Build
- Gradle 8.9 (wrapper), AGP 8.7.3, Kotlin 2.0.21 + compose plugin, compose-bom 2024.12.01, Java 17.
- `compileSdk 35`, `targetSdk 34`, **`minSdk 21`** (automaty mívají Android 5–11 → nezvyšovat minSdk bez důvodu, hlídat API úrovně přes `Build.VERSION.SDK_INT`).
- **Podpis:** release i debug se podepisují vydávacím klíčem z `keystore.properties` (lokálně) nebo z env `SIGNING_*` (CI). Bez něj debug klíč → takové APK nejde aktualizovat z GitHubu. Bez minifikace.
- **Verze:** `versionCode = 100 + git rev-list --count HEAD` (env `VERSION_CODE` má přednost), `versionName = "1.<code>"`. `BuildConfig.GITHUB_REPO`.
- Závislosti: CameraX 1.4.1 (core, camera2, lifecycle – **bez camera-view**), WorkManager 2.9.1, `com.sun.mail:android-mail` + `android-activation` 1.6.7 (SMTP), `com.caverock:androidsvg-aar:1.4` (SVG logo), coroutines.
- Build nástroje u uživatele: `%LOCALAPPDATA%\FotokiosekBuild` (jdk-17.0.21.0.2+1, sdk, gradle-home):
  ```
  set JAVA_HOME=%LOCALAPPDATA%\FotokiosekBuild\jdk-17.0.21.0.2+1
  set ANDROID_HOME=%LOCALAPPDATA%\FotokiosekBuild\sdk
  set GRADLE_USER_HOME=%LOCALAPPDATA%\FotokiosekBuild\gradle-home
  gradlew.bat assembleDebug
  ```
  Výsledné APK zkopírovat jako `Fotokiosek.apk` do kořene a spustit instalaci znovu (nastavení na automatu zůstane).
- Kód se v cloudu nedá zkompilovat (chybí Android SDK) – chyby kompilace se ukážou v GitHub Actions (souhrn běhu obsahuje řádky `e:`).

## Zdrojový kód (`app/src/main/java/cz/fotokiosek/`)
| Soubor | Co dělá |
|---|---|
| `MainActivity.kt` | Jediná aktivita. Keep-screen-on, skryté lišty, `AppLog.init`, import `config.properties` + `LogoStore.importFromExternal`, `CameraController.start()`, USB intent (`onNewIntent`), výběr loga přes `GetContent` (předtím `Kiosk.pauseLock`). `setContent { KioskApp(settings, camera, lastTouch, pickLogo) }` |
| `KioskSettings.kt` | SharedPreferences `"kiosk"` přes delegáty (`string/int/bool`). `importConfig()` načte a **smaže** `/sdcard/Android/data/cz.fotokiosek/files/config.properties`. |
| `CameraController.kt` | Jeden vstup pro obraz: zdroj ANDROID (KioskCamera) nebo CANON (CanonCamera). `frame: ImageBitmap?` = živý náhled, `takePhoto()`, `prefocus()`, `reconnect()`, stav zrcadlovky (`canonStatus/canonConnected/canonError`). USB attach/detach/permission receiver; žádost o oprávnění pustí lock task. Veškerá PTP komunikace na jednom vlákně `canon-usb`. Při selhání převzetí USB → `Kiosk.releaseUsbFromSystem` (skryje `com.android.mtp`) a nový pokus. |
| `KioskCamera.kt` | Výběr kamery: `camera_id`, jinak **naposledy připojená externí USB** (`LENS_FACING_EXTERNAL`), jinak přední, jinak první. Hot-plug UVC → `CameraController` za 2,5 s přebinduje; když Android webkameru nevidí, pomůže „Restartovat aplikaci“ (`Kiosk.restartApp`), jinak HAL automatu UVC nepodporuje. CameraX **bez PreviewView**: `ImageAnalysis` (RGBA, ~1280×720, ~20 fps) + `ImageCapture` (`MINIMIZE_LATENCY`). Náhled i fotka jdou přes stejnou `orient()` (cropRect + `rotationDegrees` + zrcadlení) → **náhled má vždy stejnou orientaci jako fotka** (oprava otočeného live view). Viewport z velikosti plochy (`setViewSize`) při fill. `lastLatencyMs` = naměřená prodleva spouště. Fotka max **2048 px**. |
| `CanonCamera.kt` | Canon EOS přes PTP: OpenSession, SetRemoteMode/EventMode (0x9114/0x9115), CaptureDestination=PC (0xD11C=4) + PCHDDCapacity, živý náhled (EVFOutputDevice 0xD1B0=2, GetViewFinderData 0x9153, JPEG z záznamu typu 1), `halfPress()` (RemoteReleaseOn 1), `capture()` (RemoteReleaseOn/Off 3 nebo RemoteRelease 0x910F → GetEvent: **0xC186/0xC1A9 RequestObjectTransfer** (cíl RAM) i 0xC181/0xC1A7 ObjectAddedEx (cíl karta), handle +8, velikost +28, jméno +40/+44 → GetObject (3 pokusy) / fallback GetPartialObject 0x9107 po 1 MB → TransferComplete). Timeout 30 s (karta 60 s), loguje všechny přijaté události (u souborových i hex záznamu). Formát/velikost/jméno z události se berou jen když jsou věrohodné; o JPEG se rozhoduje podle stažených dat (FF D8 FF) – RAW se přeskočí a zkusí se další soubor, jen RAW → jasná hláška. `canon_save_to_card` → CaptureDestination 2 (karta) místo 4 (PC). **Historická chyba:** v1.103 čekal jen na 0xC181 → při ukládání do RAM nikdy nepřišlo → „Fotku se nepodařilo pořídit“. `decode()` = zmenšení + `canon_rotation` + zrcadlení. Netestováno na skutečném těle – při problémech koukni do logu. |
| `PtpUsb.kt` | PTP transport přes USB bulk (kontejnery, čtení po 16 kB kvůli Androidu < 9, ZLP), `claimInterface(force=true)`. |
| `PhotoStore.kt` | JPEG (q 90) do `filesDir/photos/`, mazání po `keepDays`. |
| `SendPhotoWorker.kt` | WorkManager fronta, **bez síťové podmínky** (na některých deskách se nikdy nesplní), LINEAR backoff 30 s, max 200 pokusů, tag `send-photo`. Chyba → `lastError` přes `MailDiagnostics.explain`. |
| `Mailer.kt` | From = `from_address` (jinak `smtp_user`) + `from_name`, **Reply-To** = `reply_to` (jinak From). SMTP přes JavaMail: `account()`, `session(settings, debugOut)` (SSL/STARTTLS/NONE, `mail.smtp.localhost`, `smtp_trust_all` → `ssl.trust=*`), `send()` s explicitním `connect` + `sendMessage`. Mailcap registrace ručně. |
| `MailDiagnostics.kt` | Test pošty po krocích: nastavení → DNS → TCP → TLS (u SSL) → přihlášení → (odeslání). `Report(steps, protocolLog)`; `explain(e)` = česká příčina + rada (heslo pro aplikace, certifikát/čas, port×zabezpečení, timeout…). |
| `AppLog.kt` | Log do `filesDir/logs/fotokiosek.log` (256 kB, rotace), `tail()`, `causes()`. Zobrazení v adminu. Hesla se nelogují (`mail.debug.auth=false`). |
| `Printing.kt` | `PrintMedia` (SELPHY CP1500 = 100×148 mm 1181×1748 px, DNP QW410/obecná = 4×6″ 1200×1800 px), `PrintLayout.fit()` = vždy 10×15 při 300 DPI, vyplnit (ořez uprostřed, bez bílých okrajů), `UsbPrinters.list()` (USB třída 7, jen info – Android na USB tiskne jen přes tiskovou službu). IPP posílá `media-col` (rozměr + okraje 0 = borderless), při 0x0400/0x040B zkusí znovu bez něj. `IppClient` (vlastní IPP: Print-Job s `image/jpeg`, `copies`, `print-scaling=fill`; Get-Printer-Attributes pro test; `normalize()` IP→`ipp://IP:631/ipp/print`), `PrinterDiscovery` (NSD `_ipp._tcp`, atribut `rp`), `SystemPrint` (PrintManager + PDF s N stránkami = N kopií). |
| `LogoStore.kt` | Výběr loga `logo_choice`: none / custom / **přibalená loga** `builtin:satisfactory|watt|allin` (`res/drawable-nodpi/logo_*.png`, zdroj: složka `Loga` u uživatele – jen lokálně). `load(context)` podle volby (logo pro fotky a úvodní obrazovku, UI ho načítá na pozadí), `thumbnail()` = zmenšené náhledy pro admin s vlastní mezipamětí (načítat mimo hlavní vlákno), `select()`. Vlastní logo jako PNG v `filesDir/branding/logo.png` (přežije aktualizaci). Import z Uri (PNG/JPG/WEBP/SVG přes AndroidSVG), z `externalFiles/logo.*` (instalace), `applyToPhoto()` (roh `logo_position`, `logo_size` %). `version` = Compose state pro překreslení. |
| `Updater.kt` | GitHub API `releases/latest` → asset `.apk` → stažení (ruční redirecty, token jen na GitHub) → kontrola balíčku → `PackageInstaller` session (`USER_ACTION_NOT_REQUIRED`). `UpdateReceiver`: výsledek instalace + `MY_PACKAGE_REPLACED` → spustí MainActivity. |
| `EmailCheck.kt` | Validace e-mailu, opravy překlepů domén. `RateLimit`: max **5 fotek na adresu za hodinu**. |
| `Kiosk.kt` | Device owner: lock task (+ `com.android.printspooler` v allowlistu), persistentní HOME, keyguard, kamera, status bar. `pauseLock()`, `openSystemScreen()`, `releaseUsbFromSystem()`, `removeDeviceOwner()` (vrací i skrytý MTP host). |
| `ui/KioskApp.kt` | Hlavní Compose UI + stavový automat obrazovek (viz níže), `LivePreview`, `CopiesDialog`, `Notice`. |
| `ui/EmailScreen.kt` | Zadání e-mailu **vlastní on-screen klávesnicí** (systémová klávesnice na automatech bývá nepoužitelná). |
| `ui/AdminScreen.kt` | `AdminCorner` (skrytý roh), `PinPad`, `AdminScreen` (menu správce). |
| `ui/Common.kt` | Barvy `Accent` #FFB300 (oranžovo-žlutá), `Dark` #141414, `KeyColor` #2C2C2C; `KioskTheme` (tmavé Material3); `BigButton` (velké zaoblené tlačítko, min. 84 dp, text 28 sp; `primary=false` = průhledné bílé). |

### UI – obrazovky (`sealed interface Screen` v KioskApp.kt)
`Idle` → `Countdown(n)` → `Review(photo)` → `Email(photo, email)` → `Confirm(photo, email)` → `Sent`; plus `Admin`.
- **Živý náhled** (`LivePreview`: `Image(camera.frame)`, Crop při fill, jinak Fit) běží pořád pod vším, ostatní obrazovky ho překrývají. Nic se v UI neotáčí – snímky jsou už orientované.
- **Idle:** logo vpravo nahoře (`logo_on_screen`), uvítací text nahoře (44 sp, poloprůhledné pozadí), dole pulzující kulaté tlačítko **VYFOTIT** (190 dp). Při chybě kamery místo tlačítka červený box s chybou + nápověda pro správce.
- **Countdown:** časování podle absolutního konce `end`: 800 ms před koncem `camera.prefocus()` (Canon namáčkne spoušť), `shutter_lead_ms` před koncem se spustí `takePhoto()` (async → `Result`, chyba nesmí shodit scope), v čase `end` „Úsměv!“ + záblesk. Fotka → `LogoStore.applyToPhoto` → Review. Chyba → hláška a zpět na Idle.
- **Review:** „Jak se ti líbí?“, fotka (už s logem), tlačítka **Znovu** / **Vytisknout** (jen když `print_enabled`) / **Poslat na e-mail**. Vytisknout → `CopiesDialog` (− / +, 1…`print_max_copies`) → IPP tisk na pozadí nebo systémový dialog; výsledek jako zelená/červená `Notice`.
- **Email:** pole s kurzorem, placeholder `tvuj@email.cz`; klávesnice: řada domén (@gmail.com, @seznam.cz, @email.cz, @centrum.cz, @icloud.com – nahradí vše za @), čísla + QWERTZ bez diakritiky, řada `@ . - _ .cz .com ⌫`. Max 80 znaků, jen jeden `@`. Návrh opravy překlepu („Nemyslel(a) jsi …? Klepni sem.“). Validace při **Pokračovat** (trim + lowercase), jinak „Tohle nevypadá jako e-mailová adresa.“ Dole poznámka „E-mail použijeme jen k odeslání této fotky.“
- **Confirm:** „Pošleme fotku na: <adresa> – Je adresa správně?“ → **Opravit** / **Ano, poslat**.
- **send():** `RateLimit.allow` (jinak hláška a zůstane na Confirm) → Sent; na IO vlákně `PhotoStore.save` + `SendPhotoWorker.enqueue` (chyba uložení → `lastError`).
- **Sent:** „Hotovo! Fotka je na cestě… mrkni i do spamu.“ Po 8 s nebo klepnutí zpět na Idle.
- **Časovače:** nečinnost → Idle po `idleTimeout` (min 10 s), v Admin menu po 180 s; během odpočtu se neruší. PIN pad zmizí po 30 s nečinnosti. Chybové hlášky (červený pruh nahoře) zmizí po 5 s.
- **Vstup do správy:** neviditelný čtverec 110 dp vlevo nahoře, 5 klepnutí do 3 s → `PinPad` (číselník, max 8 číslic, „Špatný PIN“) → `Screen.Admin`. Roh není aktivní, když je otevřený Admin.

### Menu správce (AdminScreen)
Sekce: stavový box (verze, device owner, `camera.statusLine`, fronta, odesláno, vytištěno, poslední chyba) → **Kamera** (zdroj auto/android/canon, stav zrcadlovky s tečkou + **Znovu připojit**, kamery Androidu, otočení zrcadlovky, zrcadlení, fill, předstih spouště + naměřená prodleva) → **Kiosek** → **Logo** (náhled, nahrát/nahradit/smazat, na obrazovce, na fotce, roh, velikost) → **Tisk** (povolit, režim ipp/system, vyhledat, výběr, ruční adresa, otestovat, max kopií) → **E-mail** (předvolby, pole, tolerovat certifikát, **Otestovat připojení k mailu** / **…a poslat testovací e-mail**, výsledky po krocích s radou, komunikace se serverem) → **Aktualizace** (verze, nová verze na GitHubu, **Aktualizovat aplikaci** s průběhem, repo, token) → **Log** (zobrazit/smazat) → **Systém**. Zavřít / Uložit. `save()` zapíše vše a zavolá `camera.applySettings()`.
Pomocné komponenty: `ChoiceRow` (čipy), `Section`, `SwitchRow`, `Field`.

Resources: `strings.xml` (jen `app_name`), `themes.xml` (`Theme.Material.NoActionBar.Fullscreen`, černé pozadí), `drawable/ic_launcher.xml`, `xml/device_admin.xml` (prázdné policies).

Manifest: oprávnění CAMERA, INTERNET, ACCESS_NETWORK_STATE; `camera.any` není povinná; MainActivity je LAUNCHER **i HOME** (singleTask), `allowBackup=false`, `largeHeap=true`.

## Nastavení (klíče v config.properties = klíče v SharedPreferences)
E-mail: `smtp_host` (smtp.gmail.com), `smtp_port` (465), `smtp_security` (SSL|STARTTLS|NONE), `smtp_user`, `smtp_password`, `smtp_trust_all` (false), `from_address`, `from_name`, `reply_to`, `subject`, `body` (`\n`). (Android nemá env proměnné – SMTP_HOST apod. = tyto klíče.) V adminu předvolba „Vlastní / firemní“, port 465/587 sám přepne SSL/STARTTLS.
Kiosek: `welcome_text`, `pin` (1234; mění se v adminu blokem „Změna PINu“ – nový + potvrzení, 4–8 číslic, `savePin()` = commit, hláška o úspěchu; PinPad čte `settings.pin` při každém zadání), `countdown` (3), `idle_timeout` (45), `keep_days` (7), `shutter_lead_ms` (250).
Kamera: `camera_source` (auto|android|canon), `camera_id` (prázdné = auto, USB přednostně), `mirror` (true), `fill_screen` (true), `canon_rotation` (0), `canon_save_to_card` (false), `preview_rotation` (**180** – jen živý náhled vestavěné kamery; na automatu byl náhled vzhůru nohama, fotka správně; externí USB kamera se neotáčí).
Tisk: `print_enabled` (false), `print_mode` (ipp|system), `printer_url`, `printer_name`, `print_max_copies` (5), `printer_model` (selphy_cp1500|dnp_qw410|generic).
Logo: `logo_choice` (custom), `logo_on_screen`, `logo_on_photo` (true), `logo_position` (TL|TC|TR|BL|BC|BR, BR), `logo_size` (18 %). Soubor loga ≠ nastavení (LogoStore).
Aktualizace: `github_repo` (dominikperlik-png/fotokiosek), `github_token` (jen soukromé repo).
Interní: `sent_count`, `print_count`, `last_error`.
Když přidáváš nové nastavení: delegát v `KioskSettings`, položka v `AdminScreen` (state + `save()`), řádek v `config.example.properties` a zmínka v `POSTUP.html`.

SMTP předvolby: Gmail `smtp.gmail.com:465 SSL` (heslo pro aplikace, ~500/den), Seznam `smtp.seznam.cz:465 SSL`, Brevo `smtp-relay.brevo.com:587 STARTTLS` (300/den zdarma).

## Instalace (`instalace.ps1`, 7 kroků, dá se pouštět opakovaně)
1. Připojení přes USB nebo IP (`adb connect ip:5555`), více zařízení → ANDROID_SERIAL.
2. Diagnostika → `diagnostika.txt` (Android/SDK, model, deska, displej, kamery, /dev/video*, USB, root, HOME app, device owner, účty, 3rd-party aplikace).
3. Odebrání cizího device ownera (`dpm remove-active-admin`, jinak přes root smazání `/data/system/device_owner_2.xml` + `device_policies.xml` a reboot).
4. Výběr a vypnutí původních aplikací (`pm disable-user`, fallback `pm uninstall -k --user 0`), APK záloha do `zaloha/`.
5. `adb install -r [-g]` APK.
6. `dpm set-device-owner cz.fotokiosek/.KioskAdminReceiver` (selže s přihlášeným účtem nebo jiným ownerem).
7. Push `config.properties` a `logo.*` do `/sdcard/Android/data/cz.fotokiosek/files/`, restart aplikace, volitelně reboot automatu.

Krok 5 nabízí stažení `releases/latest/download/fotokiosek.apk`; při `UPDATE_INCOMPATIBLE` (starý podpis) vysvětlí jednorázovou přeinstalaci (nejdřív v adminu „Odebrat správce zařízení“).

## Známé otevřené věci / možná budoucí práce
- Canon PTP, IPP tisk, aktualizace a nový náhled nejsou otestované na skutečném hardwaru automatu – první ostré nasazení sledovat přes Log v adminu.
- Starší verze (před GitHubem) byla podepsaná jiným klíčem → jednou ruční přeinstalace.
- USB (UVC) webkamery, které Android sám nevidí.
- Neznámý hardware automatu – reálná data budou v `diagnostika.txt`.

## Zásady pro úpravy
- Při psaní, kontrole nebo úpravách kódu použij skill **karpathy-guidelines** (nejdřív přemýšlet, co nejjednodušší řešení, měnit jen to nutné, ověřitelný cíl).
- Zachovat kompatibilitu s Androidem 5 (API 21); novější API obalit kontrolou verze a `runCatching` jako v `Kiosk.kt`.
- Ochrana osobních údajů: fotky jen v interní paměti, mazání po `keep_days`, e-mail jen ve frontě WorkManageru. Nic neukládat navíc.
- Hesla nikdy necommitovat (`config.properties` je v .gitignore).
- UI je pro dotykový displej automatu: velká písma (26–44 sp), velká tlačítka (`BigButton`), tmavé pozadí, akcent `Accent`. Nové obrazovky stavět ve stejném stylu a přidat je do `Screen`.
- Po změně kódu: sestavit, přepsat `Fotokiosek.apk`, případně aktualizovat `POSTUP.html`.
