# Fotokiosek – webové účetnictví

Malá webová aplikace (PHP + SQLite, bez dalších knihoven). Automat do ní posílá každý tisk a každou AI úpravu.
Po přihlášení je vidět přehled po dnech (datum, čas, počet fotek, tržba) a jde vystavit a vytisknout účtenku.

## Požadavky
- PHP **8.1 nebo novější** s rozšířením **pdo_sqlite** (`php -m | grep -i sqlite`). `mbstring` je vhodné, ne nutné.
- Web server: Apache (stačí `.htaccess`) nebo nginx + PHP-FPM.
- **HTTPS** (např. Let's Encrypt). Automat posílá token v hlavičce, bez HTTPS by šel sítí čitelně.
  Android 7.0 kořenový certifikát Let's Encrypt (ISRG Root X1) nezná – aplikace v automatu ho proto má přibalený, takže
  Let's Encrypt funguje. Ověř to v automatu tlačítkem „Otestovat spojení s účetnictvím“.
- Databáze na **místním disku** (ne NFS/síťová složka – SQLite v režimu WAL tam nefunguje spolehlivě).

## Nasazení
1. Zkopíruj obsah složky `web/` na server, např. do `/var/www/fotokiosek/` → adresa `https://tvuj-server.cz/fotokiosek/`.
2. Zkopíruj `config.example.php` jako `config.php` a nastav `SETUP_CODE` (dlouhý náhodný kód, jen pro založení prvního účtu).
3. Složka `data/` musí být zapisovatelná pro PHP (`chown www-data data && chmod 770 data`) a **nesmí být dostupná z webu**:
   - **Nejbezpečnější:** dej databázi úplně mimo web – proměnná prostředí `FOTOKIOSEK_DATA_DIR=/var/lib/fotokiosek`
     (PHP-FPM: `env[FOTOKIOSEK_DATA_DIR] = /var/lib/fotokiosek` v poolu; složku vytvoř a dej ji uživateli PHP).
   - Apache: hlídá to `.htaccess` – musí být povolené `AllowOverride All` (aspoň `AuthConfig FileInfo Options`).
     **Ověř to:** `https://tvuj-server.cz/fotokiosek/data/fotokiosek.sqlite` musí vrátit 403/404, ne soubor ke stažení.
   - nginx: přidej do `server { … }` **nad** blok `location ~ \.php$` (prefixové `^~` mají přednost před regulárními):
     ```nginx
     location ^~ /fotokiosek/data/ { deny all; }
     location ^~ /fotokiosek/inc/ { deny all; }
     location = /fotokiosek/config.php { deny all; }
     location = /fotokiosek/config.example.php { deny all; }
     ```
   - Za reverzní proxy (Cloudflare, další nginx) nastav v `config.php` `TRUSTED_PROXY` – jinak se limit špatných přihlášení
     počítá pro všechny dohromady.
4. Otevři `https://tvuj-server.cz/fotokiosek/login.php`, zadej instalační kód a založ si účet (jméno + heslo, aspoň 10 znaků).
5. **Nastavení** → vyplň cenu za fotku a údaje na účtenku → **Přidat automat** → ukáže se adresa API a token.
   Další lidi přidáš v **Uživatelé** (jméno, role, heslo) – jméno a heslo jim pak pošli, heslo si změní v Nastavení.
6. V automatu: menu správce → **Účetnictví (web)** → vlož adresu API a token → **Otestovat spojení s účetnictvím** → Uložit.
7. Vyfoť a vytiskni fotku – za chvíli se objeví v Přehledu.

## Co je kde
| Soubor | K čemu |
|---|---|
| `api.php` | Příjem událostí z automatu (POST JSON, `Authorization: Bearer <token>`); duplicitní `uuid` se ignoruje. |
| `login.php`, `logout.php` | Přihlášení (první spuštění = založení účtu s instalačním kódem), max. 10 špatných pokusů / 15 min z jedné IP. |
| `index.php` | Přehled: filtr období, součty, tabulka po dnech, všechny záznamy, vystavení účtenky. |
| `receipts.php`, `receipt.php` | Seznam účtenek, vystavení (číslo `rok-pořadí`), tisk (80 mm, jde i na A4). Účtenka si pamatuje údaje z doby vystavení. |
| `settings.php` | Cena, měna, údaje prodávajícího, automaty a jejich tokeny (uložené jen jako SHA-256) – jen správce; změna vlastního hesla – všichni. |
| `users.php` | Uživatelé (jen správce): přidat, role, nové heslo, smazat. Role **Správce** = vše, **Přehled a účtenky** = přehled + vystavení a tisk účtenek. Sebe ani posledního správce smazat nejde; nové heslo odhlásí uživatele na všech zařízeních. |
| `inc/bootstrap.php` | Databáze (tabulky se vytvoří samy), session, CSRF, pomocné funkce. |
| `data/` | Databáze `fotokiosek.sqlite` – **zálohuj ji** (stačí zkopírovat soubor). |

## Formát události z automatu
```json
{"uuid":"…36 znaků…","type":"print","copies":2,"source":"foto|galerie","style":"Komiks","mode":"selphy_usb","device":"Fotokiosek","time":"2026-11-25T14:03:00+0100"}
```
`type` je `print` (tisk), `ai` (povedená AI úprava) nebo `ping` (test spojení, neukládá se). Odpověď `{"ok":true}`.
Automat neodeslané události drží ve frontě a posílá je znovu, dokud je server nepřijme.

## Účtenka a zákon
Účtenka je jednoduchý doklad o tržbě za období (číslo, prodávající, IČO/DIČ, počet, cena, celkem). Každý tisk si pamatuje
cenu platnou v době tisku, takže změna ceny nepřepočítá minulost. Evidence tržeb (EET) byla v ČR zrušena k 1. 1. 2023 –
ověř aktuální stav, mluví se o jejím obnovení. Pokud jsi plátce DPH a potřebuješ daňový doklad, doplň do Nastavení DIČ a ověř s účetní,
co vše na dokladu musí být (sazba a výše DPH se teď nevypisují).
