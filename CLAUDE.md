# Fotokiosek – kontext projektu pro Clauda

## O co jde
Android aplikace, která z **bývalého automatu na e-cigarety** (Android deska s dotykovým displejem a kamerou) dělá **fotokoutek**:
úvodní obrazovka se živým obrazem → **VYFOTIT** → odpočet 3-2-1 → náhled fotky → zadání e-mailu → kontrola adresy → fotka odejde e-mailem.
Součástí je skryté **menu správce** (admin) a instalační skript pro Windows, který automat „přeprogramuje“ přes adb.

- Autor/majitel: Dominik (dominik.perlik@gmail.com), Windows PC, projekt leží v `C:\Users\domin\Desktop\Fotokiosek\Fotokiosek\`.
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
| `platform-tools/` | Přibalené adb.exe atd. |
| `diagnostika.txt`, `zaloha/` | Vznikají při instalaci (v .gitignore) |

## Build
- Gradle 8.9 (wrapper), AGP 8.7.3, Kotlin 2.0.21 + compose plugin, compose-bom 2024.12.01, Java 17.
- `compileSdk 35`, `targetSdk 34`, **`minSdk 21`** (automaty mívají Android 5–11 → nezvyšovat minSdk bez důvodu, hlídat API úrovně přes `Build.VERSION.SDK_INT`).
- Release je podepsaný debug klíčem, bez minifikace.
- Závislosti: CameraX 1.4.1 (core, camera2, lifecycle, view), WorkManager 2.9.1, `com.sun.mail:android-mail` + `android-activation` 1.6.7 (SMTP), coroutines.
- Build nástroje u uživatele: `%LOCALAPPDATA%\FotokiosekBuild` (jdk-17.0.21.0.2+1, sdk, gradle-home):
  ```
  set JAVA_HOME=%LOCALAPPDATA%\FotokiosekBuild\jdk-17.0.21.0.2+1
  set ANDROID_HOME=%LOCALAPPDATA%\FotokiosekBuild\sdk
  set GRADLE_USER_HOME=%LOCALAPPDATA%\FotokiosekBuild\gradle-home
  gradlew.bat assembleDebug
  ```
  Výsledné APK zkopírovat jako `Fotokiosek.apk` do kořene a spustit instalaci znovu (nastavení na automatu zůstane).
- `versionCode = 1`, `versionName = "1.0"` – při nové verzi zvednout.

## Zdrojový kód (`app/src/main/java/cz/fotokiosek/`)
| Soubor | Co dělá |
|---|---|
| `MainActivity.kt` | Jediná aktivita. Keep-screen-on, skryté systémové lišty, import `config.properties`, setup kiosku, úklid starých fotek, žádost o CAMERA, Zpět nedělá nic. Sleduje `lastTouch` (pro návrat na úvod po nečinnosti). `setContent { KioskApp(settings, camera, lastTouch) }` |
| `KioskSettings.kt` | Nastavení v SharedPreferences `"kiosk"` přes property delegáty (`string/int/bool`). `importConfig()` načte `/sdcard/Android/data/cz.fotokiosek/files/config.properties` (UTF-8, odstraní BOM) a **soubor smaže**. Obsahuje i `sentCount` a `lastError`. |
| `KioskCamera.kt` | CameraX: výběr kamery podle `cameraId` (jinak přední, jinak první), zrcadlení, FILL/FIT náhled, viewport ořez = fotka oříznutá jako náhled. `takePhoto()` → Bitmap otočená/zrcadlená, delší strana max **2048 px**. `cameras()` vrací seznam pro admin menu. Chyby do `error` (Compose state). USB kamery jen pokud je vidí Android (UVC knihovna zatím není). |
| `PhotoStore.kt` | Ukládá JPEG (q 90) do `filesDir/photos/fotka_yyyyMMdd_HHmmss_SSS.jpg`, maže starší než `keepDays`. |
| `SendPhotoWorker.kt` | WorkManager fronta: odeslání při připojení k síti, exponenciální backoff od 30 s, max 50 pokusů, tag `send-photo`. Úspěch → `sentCount++`, chyba → `lastError` s časem. |
| `Mailer.kt` | SMTP přes JavaMail. SSL / STARTTLS / NONE, timeouty, u Gmailu odstraní mezery z app hesla. Ruční registrace mailcap (jinak „no object DCH for MIME type“ na Androidu). Bez přílohy = testovací e-mail. |
| `EmailCheck.kt` | Validace e-mailu (regex), návrhy oprav překlepů v doménách (gmial.com→gmail.com, seznam.com→seznam.cz…). `RateLimit`: max **5 fotek na adresu za hodinu** (v paměti). |
| `Kiosk.kt` | `KioskAdminReceiver` (DeviceAdminReceiver) + objekt `Kiosk`: když je app **device owner** → lock task, persistentní HOME, vypnutý keyguard, automaticky povolená kamera, vypnutý status bar. `openSystemScreen()` dočasně pustí do Wi-Fi/Nastavení (Domů vrátí zpět). `removeDeviceOwner()` vše vrátí (před odinstalací). |
| `ui/KioskApp.kt` | Hlavní Compose UI + stavový automat obrazovek (viz níže). |
| `ui/EmailScreen.kt` | Zadání e-mailu **vlastní on-screen klávesnicí** (systémová klávesnice na automatech bývá nepoužitelná). |
| `ui/AdminScreen.kt` | `AdminCorner` (skrytý roh), `PinPad`, `AdminScreen` (menu správce). |
| `ui/Common.kt` | Barvy `Accent` #FFB300 (oranžovo-žlutá), `Dark` #141414, `KeyColor` #2C2C2C; `KioskTheme` (tmavé Material3); `BigButton` (velké zaoblené tlačítko, min. 84 dp, text 28 sp; `primary=false` = průhledné bílé). |

### UI – obrazovky (`sealed interface Screen` v KioskApp.kt)
`Idle` → `Countdown(n)` → `Review(photo)` → `Email(photo, email)` → `Confirm(photo, email)` → `Sent`; plus `Admin`.
- **Živý náhled kamery** (`PreviewView`, COMPATIBLE mode) běží pořád pod vším, ostatní obrazovky ho překrývají.
- **Idle:** uvítací text nahoře (44 sp, poloprůhledné pozadí), dole pulzující kulaté tlačítko **VYFOTIT** (190 dp). Při chybě kamery místo tlačítka červený box s chybou + nápověda pro správce.
- **Countdown:** velká čísla (240 sp) podle `countdown` (0–10), pak „Úsměv!“, bílý záblesk (600 ms), `camera.takePhoto()`. Chyba → hláška „Fotku se nepodařilo pořídit…“ a zpět na Idle.
- **Review:** „Jak se ti líbí?“, fotka, tlačítka **Znovu** / **Poslat na e-mail**.
- **Email:** pole s kurzorem, placeholder `tvuj@email.cz`; klávesnice: řada domén (@gmail.com, @seznam.cz, @email.cz, @centrum.cz, @icloud.com – nahradí vše za @), čísla + QWERTZ bez diakritiky, řada `@ . - _ .cz .com ⌫`. Max 80 znaků, jen jeden `@`. Návrh opravy překlepu („Nemyslel(a) jsi …? Klepni sem.“). Validace při **Pokračovat** (trim + lowercase), jinak „Tohle nevypadá jako e-mailová adresa.“ Dole poznámka „E-mail použijeme jen k odeslání této fotky.“
- **Confirm:** „Pošleme fotku na: <adresa> – Je adresa správně?“ → **Opravit** / **Ano, poslat**.
- **send():** `RateLimit.allow` (jinak hláška a zůstane na Confirm) → Sent; na IO vlákně `PhotoStore.save` + `SendPhotoWorker.enqueue` (chyba uložení → `lastError`).
- **Sent:** „Hotovo! Fotka je na cestě… mrkni i do spamu.“ Po 8 s nebo klepnutí zpět na Idle.
- **Časovače:** nečinnost → Idle po `idleTimeout` (min 10 s), v Admin menu po 180 s; během odpočtu se neruší. PIN pad zmizí po 30 s nečinnosti. Chybové hlášky (červený pruh nahoře) zmizí po 5 s.
- **Vstup do správy:** neviditelný čtverec 110 dp vlevo nahoře, 5 klepnutí do 3 s → `PinPad` (číselník, max 8 číslic, „Špatný PIN“) → `Screen.Admin`. Roh není aktivní, když je otevřený Admin.

### Menu správce (AdminScreen)
- Stavový box: device owner ano/NE, kamera OK (id) / chyba, počet ve frontě (WorkManager tag `send-photo`, nedokončené), odesláno celkem, poslední chyba.
- **Kamera:** radio seznam z `camera.cameras()`, přepínače Zrcadlit a Náhled přes celou obrazovku.
- **Kiosek:** uvítací text, odpočet (0–10), nečinnost (10–600 s), mazání fotek (1–365 dní), PIN (jen číslice, uloží se jen když má ≥ 4).
- **E-mail (SMTP):** předvolby Gmail/Seznam/Brevo, server, port, SSL/STARTTLS/bez šifrování, uživatel, heslo (skryté), adresa a jméno odesílatele, předmět, text. Tlačítko **Uložit a poslat testovací e-mail** – pošle test bez přílohy **na vlastní adresu kiosku** (from_address, jinak smtp_user) a ukáže výsledek/chybu.
- **Systém:** Wi-Fi, Nastavení Androidu (`Kiosk.openSystemScreen`), „Odebrat správce zařízení (před odinstalací)“ s potvrzovacím dialogem (jen když je device owner).
- **Zavřít** (bez uložení) / **Uložit** (uloží a zavře). `save()` zapíše všechno do `KioskSettings` a znovu nabinduje kameru.
- Textová pole v adminu používají systémovou klávesnici (na rozdíl od zákaznické obrazovky).

Resources: `strings.xml` (jen `app_name`), `themes.xml` (`Theme.Material.NoActionBar.Fullscreen`, černé pozadí), `drawable/ic_launcher.xml`, `xml/device_admin.xml` (prázdné policies).

Manifest: oprávnění CAMERA, INTERNET, ACCESS_NETWORK_STATE; `camera.any` není povinná; MainActivity je LAUNCHER **i HOME** (singleTask), `allowBackup=false`, `largeHeap=true`.

## Nastavení (klíče v config.properties = klíče v SharedPreferences)
`smtp_host` (smtp.gmail.com), `smtp_port` (465), `smtp_security` (SSL|STARTTLS|NONE), `smtp_user`, `smtp_password`, `from_address` (prázdné = smtp_user), `from_name` (Fotokoutek), `subject`, `body` (`\n` = nový řádek), `welcome_text`, `pin` (1234), `countdown` (3), `idle_timeout` (45 s), `keep_days` (7), `mirror` (true), `fill_screen` (true), `camera_id` (""). Interní: `sent_count`, `last_error`.
Když přidáváš nové nastavení: delegát v `KioskSettings`, položka v `AdminScreen`, řádek v `config.example.properties` a zmínka v `POSTUP.html`.

SMTP předvolby: Gmail `smtp.gmail.com:465 SSL` (heslo pro aplikace, ~500/den), Seznam `smtp.seznam.cz:465 SSL`, Brevo `smtp-relay.brevo.com:587 STARTTLS` (300/den zdarma).

## Instalace (`instalace.ps1`, 7 kroků, dá se pouštět opakovaně)
1. Připojení přes USB nebo IP (`adb connect ip:5555`), více zařízení → ANDROID_SERIAL.
2. Diagnostika → `diagnostika.txt` (Android/SDK, model, deska, displej, kamery, /dev/video*, USB, root, HOME app, device owner, účty, 3rd-party aplikace).
3. Odebrání cizího device ownera (`dpm remove-active-admin`, jinak přes root smazání `/data/system/device_owner_2.xml` + `device_policies.xml` a reboot).
4. Výběr a vypnutí původních aplikací (`pm disable-user`, fallback `pm uninstall -k --user 0`), APK záloha do `zaloha/`.
5. `adb install -r [-g]` APK.
6. `dpm set-device-owner cz.fotokiosek/.KioskAdminReceiver` (selže s přihlášeným účtem nebo jiným ownerem).
7. Push `config.properties` do `/sdcard/Android/data/cz.fotokiosek/files/`, restart aplikace, volitelně reboot automatu.

## Známé otevřené věci / možná budoucí práce
- Podpora USB (UVC) kamer, které Android sám nevidí.
- Nastavení otočení obrazu (pro kamery bokem / vzhůru nohama).
- Logo / rámeček na fotce, jiné texty.
- Neznámý hardware automatu – reálná data budou v `diagnostika.txt`, až ho uživatel pošle.

## Zásady pro úpravy
- Zachovat kompatibilitu s Androidem 5 (API 21); novější API obalit kontrolou verze a `runCatching` jako v `Kiosk.kt`.
- Ochrana osobních údajů: fotky jen v interní paměti, mazání po `keep_days`, e-mail jen ve frontě WorkManageru. Nic neukládat navíc.
- Hesla nikdy necommitovat (`config.properties` je v .gitignore).
- UI je pro dotykový displej automatu: velká písma (26–44 sp), velká tlačítka (`BigButton`), tmavé pozadí, akcent `Accent`. Nové obrazovky stavět ve stejném stylu a přidat je do `Screen`.
- Po změně kódu: sestavit, přepsat `Fotokiosek.apk`, případně aktualizovat `POSTUP.html`.
