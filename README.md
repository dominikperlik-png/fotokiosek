# Fotokiosek

Android aplikace, která z bývalého prodejního automatu (Android deska s dotykovým displejem) dělá **fotokoutek**:
VYFOTIT → odpočet → náhled → poslat fotku e-mailem a/nebo vytisknout.

- Kamera automatu (CameraX) nebo **zrcadlovka Canon EOS** přes USB (PTP, živý náhled)
- Odesílání přes SMTP (Gmail, Seznam, Brevo…) s frontou – při výpadku internetu se fotky pošlou později
- Tisk na síťovou tiskárnu (IPP, bez dialogů) nebo přes systémový tisk Androidu
- Vlastní logo na úvodní obrazovce a na fotkách
- Kioskový režim (aplikace je správce zařízení a domovská obrazovka)
- **Aktualizace jedním tlačítkem** z GitHub Releases

Kompletní návod pro instalaci na automat je v [POSTUP.html](POSTUP.html). Kontext pro vývoj je v [CLAUDE.md](CLAUDE.md).

## Jak fungují aktualizace

1. Push do větve `main` spustí workflow [`.github/workflows/release.yml`](.github/workflows/release.yml).
2. GitHub sestaví APK podepsané vydávacím klíčem a vydá ho jako release `vNNN` (NNN = 100 + počet commitů = `versionCode`).
3. Na automatu: menu správce → **Aktualizace → Aktualizovat aplikaci**. Aplikace stáhne `fotokiosek.apk`
   z posledního releasu, nainstaluje ho přes `PackageInstaller` (jako správce zařízení bez potvrzování) a sama se restartuje.
   Nastavení, logo a log jsou v interní paměti aplikace a aktualizace je nemaže.

### Secrets pro GitHub Actions

Settings → Secrets and variables → Actions:

| Secret | Hodnota |
|---|---|
| `KEYSTORE_BASE64` | `fotokiosek-release.jks` v base64 |
| `SIGNING_STORE_PASSWORD` | heslo ke klíči |
| `SIGNING_KEY_ALIAS` | `fotokiosek` |
| `SIGNING_KEY_PASSWORD` | heslo k aliasu |

Klíč a hesla **nejsou v repozitáři** (`.gitignore`). Lokálně je čte `app/build.gradle.kts` ze souboru `keystore.properties`.

## Sestavení lokálně

```
gradlew.bat assembleRelease
```

Výsledek: `app/build/outputs/apk/release/app-release.apk`. Bez `keystore.properties` se podepíše ladicím klíčem
(takové APK pak nejde aktualizovat verzí z GitHubu).
