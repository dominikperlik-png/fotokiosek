<?php
/**
 * Záloha fotek na Google Disk majitele: jednorázové přihlášení Googlem (OAuth, oprávnění drive.file =
 * aplikace vidí jen soubory, které sama vytvořila), refresh token v databázi (tabulka settings),
 * Client ID a Secret v config.php (GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET). Fotky jdou do složky
 * „Fotokiosek“ a v ní do podsložek RRRR-MM-DD. Nic se nemaže.
 */
declare(strict_types=1);

const DRIVE_SCOPE = 'https://www.googleapis.com/auth/drive.file';
const DRIVE_FOLDER_MIME = 'application/vnd.google-apps.folder';
const DRIVE_ROOT_NAME = 'Fotokiosek';
/** ID souboru / složky na Disku (pro adresy galerie). */
const DRIVE_ID_PATTERN = '/^[A-Za-z0-9_-]{10,200}$/D';

/** Adresy Googlu; DRIVE_TEST_BASE v config.php = jen pro místní test s napodobeným Googlem. */
function drive_url(string $what): string
{
    $test = defined('DRIVE_TEST_BASE') ? rtrim((string)DRIVE_TEST_BASE, '/') : null;
    return match ($what) {
        'auth' => ($test ?? 'https://accounts.google.com') . '/o/oauth2/v2/auth',
        'token' => ($test ?? 'https://oauth2.googleapis.com') . '/token',
        'revoke' => ($test ?? 'https://oauth2.googleapis.com') . '/revoke',
        'api' => ($test ?? 'https://www.googleapis.com') . '/drive/v3',
        'upload' => ($test ?? 'https://www.googleapis.com') . '/upload/drive/v3/files',
    };
}

/** Jsou v config.php vyplněné údaje z Google Cloud? */
function drive_configured(): bool
{
    return defined('GOOGLE_CLIENT_ID') && defined('GOOGLE_CLIENT_SECRET')
        && trim((string)GOOGLE_CLIENT_ID) !== '' && trim((string)GOOGLE_CLIENT_SECRET) !== '';
}

function drive_connected(): bool
{
    return drive_configured() && setting('drive_refresh') !== '';
}

/** Adresa, kam Google vrací po přihlášení (musí být v Google Cloud mezi Authorized redirect URIs). */
function drive_redirect_uri(): string
{
    return site_url('drive.php');
}

function drive_auth_url(string $state): string
{
    return drive_url('auth') . '?' . http_build_query([
        'client_id' => GOOGLE_CLIENT_ID,
        'redirect_uri' => drive_redirect_uri(),
        'response_type' => 'code',
        'scope' => DRIVE_SCOPE,
        'access_type' => 'offline',
        'prompt' => 'consent', // vždy vrátit refresh token
        'include_granted_scopes' => 'true',
        'state' => $state,
    ]);
}

/**
 * HTTP požadavek (curl, jinak streams). Vrací [kód, tělo]. Výjimka jen při nedostupnosti serveru.
 * @param array<int, string> $headers
 */
function http_request(string $method, string $url, array $headers = [], ?string $body = null, int $timeout = 60): array
{
    if (function_exists('curl_init')) {
        $ch = curl_init($url);
        $opts = [
            CURLOPT_CUSTOMREQUEST => $method,
            CURLOPT_HTTPHEADER => $headers,
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_CONNECTTIMEOUT => 15,
            CURLOPT_TIMEOUT => $timeout,
            CURLOPT_FOLLOWLOCATION => false,
        ];
        if ($body !== null) {
            $opts[CURLOPT_POSTFIELDS] = $body;
        }
        curl_setopt_array($ch, $opts);
        $resp = curl_exec($ch);
        if ($resp === false) {
            throw new RuntimeException('Google nedostupný: ' . curl_error($ch));
        }
        return [(int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE), (string)$resp];
    }
    $ctx = stream_context_create(['http' => [
        'method' => $method,
        'header' => implode("\r\n", $headers),
        'content' => $body ?? '',
        'timeout' => $timeout,
        'ignore_errors' => true,
        'follow_location' => 0,
    ]]);
    $resp = @file_get_contents($url, false, $ctx);
    if ($resp === false) {
        throw new RuntimeException('Google nedostupný (server nemá curl ani povolené allow_url_fopen?).');
    }
    $code = 0;
    foreach ($http_response_header ?? [] as $line) {
        if (preg_match('#^HTTP/\S+\s+(\d{3})#', $line, $m)) {
            $code = (int)$m[1];
        }
    }
    return [$code, $resp];
}

/** Výměna kódu z přihlášení za tokeny; uloží refresh token. */
function drive_connect_with_code(string $code): void
{
    [$status, $body] = http_request('POST', drive_url('token'), ['Content-Type: application/x-www-form-urlencoded'], http_build_query([
        'code' => $code,
        'client_id' => GOOGLE_CLIENT_ID,
        'client_secret' => GOOGLE_CLIENT_SECRET,
        'redirect_uri' => drive_redirect_uri(),
        'grant_type' => 'authorization_code',
    ]), 30);
    $data = json_decode($body, true);
    if ($status !== 200 || !is_array($data) || empty($data['access_token'])) {
        $why = is_array($data) ? (string)($data['error_description'] ?? $data['error'] ?? '') : '';
        throw new RuntimeException('Google přihlášení nepřijal (HTTP ' . $status . ($why !== '' ? ': ' . $why : '') . '). Zkontroluj Client ID, Secret a adresu pro přesměrování.');
    }
    if (empty($data['refresh_token'])) {
        throw new RuntimeException('Google neposlal trvalý přístup. V účtu Google (myaccount.google.com → Zabezpečení → Aplikace třetích stran) odeber „Fotokiosek záloha“ a připoj znovu.');
    }
    if (!str_contains(' ' . (string)($data['scope'] ?? DRIVE_SCOPE) . ' ', ' ' . DRIVE_SCOPE . ' ')) {
        throw new RuntimeException('Při přihlášení nebyl povolen přístup k Disku – připoj znovu a nech zaškrtnutý přístup k souborům.');
    }
    set_setting('drive_refresh', (string)$data['refresh_token']);
    set_setting('drive_lost', '');
    set_setting('drive_access', (string)$data['access_token']);
    set_setting('drive_access_exp', (string)(time() + (int)($data['expires_in'] ?? 3600)));
    // Účet jen pro zobrazení (nevadí, když nejde zjistit).
    try {
        [$s, $b] = drive_api('GET', '/about', ['fields' => 'user(emailAddress)']);
        $email = $s === 200 ? (string)(json_decode($b, true)['user']['emailAddress'] ?? '') : '';
        set_setting('drive_account', clip($email, 120));
    } catch (RuntimeException) {
        set_setting('drive_account', '');
    }
    try {
        drive_root(); // složku Fotokiosek vytvořit hned (majitel ji uvidí na Disku)
    } catch (RuntimeException $e) {
        // Připojeno je – složka vznikne s první fotkou.
        error_log('Fotokiosek: Google Disk připojen, ale složku zatím nejde založit: ' . $e->getMessage());
    }
}

function drive_disconnect(): void
{
    $token = setting('drive_refresh');
    if ($token !== '') {
        try {
            http_request('POST', drive_url('revoke'), ['Content-Type: application/x-www-form-urlencoded'], http_build_query(['token' => $token]), 15);
        } catch (RuntimeException) {
            // Odvolání v Googlu je jen bonus – hlavní je zapomenout token tady.
        }
    }
    foreach (['drive_refresh', 'drive_access', 'drive_access_exp', 'drive_account'] as $key) {
        set_setting($key, '');
    }
}

/** Platný přístupový token (obnoví se z refresh tokenu, když vyprší). */
function drive_access_token(bool $force = false): string
{
    if (!drive_connected()) {
        throw new RuntimeException('Google Disk není připojený (účetnictví → Nastavení → Google Disk).');
    }
    if (!$force && setting('drive_access') !== '' && (int)setting('drive_access_exp') > time() + 60) {
        return setting('drive_access');
    }
    [$status, $body] = http_request('POST', drive_url('token'), ['Content-Type: application/x-www-form-urlencoded'], http_build_query([
        'client_id' => GOOGLE_CLIENT_ID,
        'client_secret' => GOOGLE_CLIENT_SECRET,
        'refresh_token' => setting('drive_refresh'),
        'grant_type' => 'refresh_token',
    ]), 30);
    $data = json_decode($body, true);
    if ($status === 400 && is_array($data) && ($data['error'] ?? '') === 'invalid_grant') {
        // Přístup odvolaný v účtu Google, změněné heslo, aplikace ve stavu Testing (7 dní)…
        set_setting('drive_refresh', '');
        set_setting('drive_access', '');
        set_setting('drive_lost', (string)time());
        error_log('Fotokiosek: Google Disk – refresh token neplatný (invalid_grant), je potřeba připojit znovu');
        throw new RuntimeException('Přístup ke Google Disku vypršel nebo byl zrušen – připoj Google Disk znovu (Nastavení).');
    }
    if ($status !== 200 || !is_array($data) || empty($data['access_token'])) {
        throw new RuntimeException('Google nevydal přístupový token (HTTP ' . $status . ').');
    }
    set_setting('drive_access', (string)$data['access_token']);
    set_setting('drive_access_exp', (string)(time() + (int)($data['expires_in'] ?? 3600)));
    return (string)$data['access_token'];
}

/**
 * Volání Drive API (cesta za /drive/v3, nebo celá adresa). Při 401 jednou obnoví token.
 * @param array<string, string> $query
 * @param array<int, string> $headers
 */
function drive_api(string $method, string $path, array $query = [], ?string $body = null, array $headers = [], int $timeout = 60): array
{
    $url = (str_starts_with($path, 'http') ? $path : drive_url('api') . $path) . ($query ? '?' . http_build_query($query) : '');
    for ($try = 0; ; $try++) {
        $auth = 'Authorization: Bearer ' . drive_access_token($try > 0);
        [$status, $resp] = http_request($method, $url, array_merge([$auth], $headers), $body, $timeout);
        if ($status !== 401 || $try > 0) {
            return [$status, $resp];
        }
    }
}

/** Text chyby z odpovědi Drive API. */
function drive_error(int $status, string $body): string
{
    $msg = (string)(json_decode($body, true)['error']['message'] ?? '');
    return 'Google Disk odpověděl HTTP ' . $status . ($msg !== '' ? ': ' . clip($msg, 200) : '');
}

/** Složka (id) ve složce $parent podle jména, nebo null. */
function drive_find_folder(string $name, string $parent): ?string
{
    [$s, $b] = drive_api('GET', '/files', [
        'q' => "name = '" . str_replace(["\\", "'"], ["\\\\", "\\'"], $name) . "' and '" . $parent . "' in parents"
            . " and mimeType = '" . DRIVE_FOLDER_MIME . "' and trashed = false",
        'fields' => 'files(id)',
        'pageSize' => '1',
        'spaces' => 'drive',
    ]);
    if ($s !== 200) {
        throw new RuntimeException(drive_error($s, $b));
    }
    return json_decode($b, true)['files'][0]['id'] ?? null;
}

function drive_create_folder(string $name, ?string $parent): string
{
    $meta = ['name' => $name, 'mimeType' => DRIVE_FOLDER_MIME];
    if ($parent !== null) {
        $meta['parents'] = [$parent];
    }
    [$s, $b] = drive_api('POST', '/files', ['fields' => 'id'], json_encode($meta), ['Content-Type: application/json; charset=utf-8']);
    $id = json_decode($b, true)['id'] ?? null;
    if ($s !== 200 || !is_string($id)) {
        throw new RuntimeException(drive_error($s, $b));
    }
    return $id;
}

/** Zámek kolem zakládání složek (dvě fotky naráz nesmí založit dvě stejné složky). */
function drive_locked(callable $fn): mixed
{
    $fh = fopen(DATA_DIR . '/drive.lock', 'c');
    if ($fh === false) {
        return $fn();
    }
    try {
        flock($fh, LOCK_EX);
        return $fn();
    } finally {
        flock($fh, LOCK_UN);
        fclose($fh);
    }
}

/** Hlavní složka „Fotokiosek“ (id). Když ji majitel smazal (koš), založí se nová. */
function drive_root(): string
{
    return drive_locked(function (): string {
        $id = setting('drive_root_id');
        if ($id !== '') {
            [$s, $b] = drive_api('GET', '/files/' . rawurlencode($id), ['fields' => 'id,trashed']);
            if ($s === 200 && empty(json_decode($b, true)['trashed'])) {
                return $id;
            }
            if ($s !== 404 && $s !== 200) {
                throw new RuntimeException(drive_error($s, $b));
            }
        }
        $id = drive_create_folder(DRIVE_ROOT_NAME, null);
        set_setting('drive_root_id', $id);
        db()->exec("DELETE FROM settings WHERE key LIKE 'drive_day:%'");
        return $id;
    });
}

/** Je složka v koši (nebo pryč)? Nahrání do složky v koši by fotku poslalo do koše (Google ho po 30 dnech maže). */
function drive_folder_gone(string $id): bool
{
    [$s, $b] = drive_api('GET', '/files/' . rawurlencode($id), ['fields' => 'id,trashed']);
    if ($s === 404) {
        return true;
    }
    if ($s !== 200) {
        throw new RuntimeException(drive_error($s, $b));
    }
    return !empty(json_decode($b, true)['trashed']);
}

/**
 * Podsložka dne RRRR-MM-DD v hlavní složce (id), založí se při první fotce dne. V mezipaměti (settings)
 * jako „id|čas kontroly“; po 10 minutách se ověří, že ji (ani hlavní složku) nikdo nedal do koše.
 */
function drive_day_folder(string $day, bool $fresh = false): string
{
    $key = 'drive_day:' . $day;
    $cached = function () use ($key): ?array {
        $v = setting($key);
        return $v !== '' && str_contains($v, '|') ? explode('|', $v, 2) : null;
    };
    $c = $cached();
    if (!$fresh && $c !== null && (int)$c[1] > time() - 600) {
        return $c[0];
    }
    $failed = $fresh && $c !== null ? $c[0] : null;
    $root = drive_root(); // ověří i koš hlavní složky
    return drive_locked(function () use ($day, $key, $root, $cached, $failed): string {
        // Mezitím (čekání na zámek) ji mohl založit jiný požadavek – vyhledávání Disku ji ještě nemusí vidět.
        $c = $cached();
        if ($c !== null && $c[0] !== $failed && (int)$c[1] > time() - 600) {
            return $c[0];
        }
        $id = ($c !== null && $c[0] !== $failed && !drive_folder_gone($c[0])) ? $c[0] : null;
        $id ??= drive_find_folder($day, $root) ?? drive_create_folder($day, $root);
        set_setting($key, $id . '|' . time());
        return $id;
    });
}

/** Nahraje JPEG do složky; vrací id souboru. */
function drive_upload_jpeg(string $folder, string $name, string $bytes): string
{
    $boundary = 'fotokiosek' . bin2hex(random_bytes(8));
    $body = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"
        . json_encode(['name' => $name, 'parents' => [$folder]]) . "\r\n"
        . "--$boundary\r\nContent-Type: image/jpeg\r\n\r\n" . $bytes . "\r\n--$boundary--";
    [$s, $b] = drive_api('POST', drive_url('upload'), ['uploadType' => 'multipart', 'fields' => 'id'], $body,
        ['Content-Type: multipart/related; boundary=' . $boundary], 180);
    $id = json_decode($b, true)['id'] ?? null;
    if ($s !== 200 || !is_string($id)) {
        throw new RuntimeException(drive_error($s, $b), $s);
    }
    return $id;
}

/** Datové složky v hlavní složce, nejnovější první: [[id, name], …]. */
function drive_days(): array
{
    $root = drive_root();
    $days = [];
    $token = null;
    do {
        $q = [
            'q' => "'" . $root . "' in parents and mimeType = '" . DRIVE_FOLDER_MIME . "' and trashed = false",
            'fields' => 'nextPageToken,files(id,name)',
            'orderBy' => 'name desc',
            'pageSize' => '1000',
        ];
        if ($token !== null) {
            $q['pageToken'] = $token;
        }
        [$s, $b] = drive_api('GET', '/files', $q);
        if ($s !== 200) {
            throw new RuntimeException(drive_error($s, $b));
        }
        $data = json_decode($b, true);
        foreach ($data['files'] ?? [] as $f) {
            if (preg_match('/^\d{4}-\d{2}-\d{2}$/', (string)$f['name'])) {
                $days[] = ['id' => (string)$f['id'], 'name' => (string)$f['name']];
            }
        }
        $token = $data['nextPageToken'] ?? null;
    } while ($token !== null && count($days) < 5000);
    return $days;
}

/** Fotky ve složce dne (stránkovaně): ['files' => [[id, name, size, created], …], 'next' => token|null]. */
function drive_photos(string $folder, ?string $pageToken): array
{
    $q = [
        'q' => "'" . $folder . "' in parents and trashed = false and mimeType != '" . DRIVE_FOLDER_MIME . "'",
        'fields' => 'nextPageToken,files(id,name,size,createdTime)',
        'orderBy' => 'name desc',
        'pageSize' => '60',
    ];
    if ($pageToken !== null) {
        $q['pageToken'] = $pageToken;
    }
    [$s, $b] = drive_api('GET', '/files', $q);
    if ($s !== 200) {
        throw new RuntimeException(drive_error($s, $b));
    }
    $data = json_decode($b, true);
    return ['files' => $data['files'] ?? [], 'next' => $data['nextPageToken'] ?? null];
}

/** Bajty souboru (celý obrázek), nebo náhledu ($thumb = šířka v px). Null = nenalezeno. */
function drive_image(string $fileId, ?int $thumb): ?string
{
    if (!preg_match(DRIVE_ID_PATTERN, $fileId)) {
        return null;
    }
    if ($thumb !== null) {
        [$s, $b] = drive_api('GET', '/files/' . rawurlencode($fileId), ['fields' => 'thumbnailLink']);
        $link = $s === 200 ? (string)(json_decode($b, true)['thumbnailLink'] ?? '') : '';
        // Jen adresy Googlu (token se nesmí poslat jinam); jeden pokus, bez obnovy tokenu.
        if ($link !== '' && preg_match('#^https://[a-z0-9.-]+\.(googleusercontent|google)\.com/#', $link)) {
            [$ts, $tb] = http_request('GET', (string)preg_replace('/=s\d+$/', '=s' . $thumb, $link),
                ['Authorization: Bearer ' . drive_access_token()], null, 30);
            if ($ts === 200 && $tb !== '') {
                return $tb;
            }
        }
        // Náhled Google ještě nepřipravil (čerstvě nahraná fotka) → bez náhledu (ne celé 2 MB do mřížky).
        return defined('DRIVE_TEST_BASE') ? drive_image($fileId, null) : null;
    }
    [$s, $b] = drive_api('GET', '/files/' . rawurlencode($fileId), ['alt' => 'media'], null, [], 120);
    return $s === 200 ? $b : null;
}
