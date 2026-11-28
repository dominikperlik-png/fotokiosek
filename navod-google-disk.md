# Návod: příprava Google Disku pro zálohu fotek (fáze 4)

Tohle uděláš jednou. Zabere to asi 15 minut. Na konci budeš mít dva údaje, **Client ID** a **Client Secret**,
které vložíš do `config.php` na serveru. **Mně je neposílej.**

Jak to bude fungovat: automat pošle každou fotku na tvůj web účetnictví a web ji nahraje na tvůj Google Disk
do složky `Fotokiosek/RRRR-MM-DD/`. Web k tomu potřebuje tvoje jednorázové svolení (přihlášení Googlem).
Aplikace vidí **jen soubory, které sama vytvořila**, zbytek tvého Disku ne.

> Google občas mírně mění názvy v konzoli. Když něco nenajdeš přesně pod tímhle názvem, hledej podobný.

---

## 1. Projekt v Google Cloud

1. Otevři <https://console.cloud.google.com/> a přihlas se **účtem Google, na jehož Disk se mají fotky ukládat**.
2. Nahoře vedle loga Google Cloud klikni na výběr projektu → **Nový projekt** (New project).
3. Název: `Fotokiosek` → **Vytvořit**. Počkej pár vteřin a pak tenhle projekt nahoře vyber.
4. Když konzole chce platební údaje, nic nevyplňuj. Disk API je pro tohle použití zdarma a účtování není potřeba.

## 2. Zapnout Google Drive API

1. Vlevo v menu (☰) → **APIs & Services** → **Library** (Knihovna).
2. Vyhledej `Google Drive API` → otevři → **Enable** (Povolit).

## 3. Obrazovka souhlasu (Google Auth Platform)

1. Menu (☰) → **APIs & Services** → **OAuth consent screen**. Konzole tě může přesměrovat na **Google Auth Platform**.
   Klikni na **Get started** (Začít).
2. **App information:** název aplikace `Fotokiosek záloha`, *User support email* = tvůj e-mail → Next.
3. **Audience:** zvol **External** (Externí) → Next.
4. **Contact information:** tvůj e-mail → Next → zaškrtni souhlas s pravidly → **Create**.
5. Vlevo **Data Access** (Přístup k datům) → **Add or remove scopes**:
   - do filtru napiš `drive.file`,
   - zaškrtni `.../auth/drive.file` (*See, edit, create, and delete only the specific Google Drive files you use with this app*),
   - **Update** → dole **Save**.
   - Nic jiného nepřidávej. Hlavně ne `.../auth/drive`, to je přístup k celému Disku a Google by ho musel schvalovat.
6. Vlevo **Audience** → u *Publishing status* klikni **Publish app** → potvrď (**In production**).
   - **Tohle je důležité.** Ve stavu *Testing* přihlášení po 7 dnech vyprší a záloha by se zastavila.
   - S oprávněním `drive.file` Google aplikaci schvalovat nemusí.

## 4. Přihlašovací klient (Client ID a Secret)

1. Vlevo **Clients** (Klienti), případně *APIs & Services → Credentials → Create credentials → OAuth client ID*.
2. **Create client:**
   - *Application type:* **Web application**
   - *Name:* `Fotokiosek web`
   - *Authorized redirect URIs* → **Add URI** a vlož adresu webu účetnictví, jen místo `api.php` na konci napiš `drive.php`:
     ```
     https://TVOJE-ADRESA/…/drive.php
     ```
     Je to stejná adresa jako *Adresa API* v účetnictví (Nastavení → Automaty), jen se jiným koncem.
     Musí začínat `https://`. Na velikosti písmen záleží.
   - **Create**.
3. Ukáže se **Client ID** (končí na `.apps.googleusercontent.com`) a **Client secret** (začíná obvykle `GOCSPX-`).
   Okno nech otevřené, nebo si údaje stáhni (Download JSON) a ulož na bezpečné místo. **Nikam je neposílej a nedávej je do Gitu.**

## 5. Vložit údaje na server

Na server do souboru `config.php` (je vedle `index.php` a do Gitu se nedává) přidej na konec dva řádky:

```php
const GOOGLE_CLIENT_ID = 'sem-vlož-client-id.apps.googleusercontent.com';
const GOOGLE_CLIENT_SECRET = 'sem-vlož-client-secret';
```

Klidně to udělej už teď. Dokud nebude nahraná verze webu s fází 4, nic se nestane.

## 6. Připojit Disk a zapnout zálohu

1. Web účetnictví musí mít verzi se zálohou (stránka **Galerie** v menu).
2. V účetnictví → **Nastavení → Google Disk** → **Připojit Google Disk** → přihlas se stejným účtem Google → **Povolit**.
   - Může se ukázat varování *„Google tuto aplikaci neověřil“*. Je to tvoje vlastní aplikace, takže klikni na **Rozšířené** a pak **Přejít na Fotokiosek záloha**.
3. Na Disku vznikne složka `Fotokiosek`. Klidně ji pak přesuň nebo přejmenuj, web ji najde podle ID.
   **Nedávej ji do koše** – web by pak založil novou.
4. V automatu: menu správce → **Účetnictví (web)** → zapni **Zálohovat fotky na Google Disk** → Uložit.
5. Vyfoť zkušební fotku. Za chvíli je na Disku ve složce `Fotokiosek/RRRR-MM-DD/` a v účetnictví v **Galerii**.

Když se v Nastavení objeví „Přístup ke Google Disku vypršel“, stačí znovu kliknout na **Připojit Google Disk**.
Automaty mezitím fotky drží a pošlou je potom.
