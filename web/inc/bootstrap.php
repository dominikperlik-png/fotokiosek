<?php
/**
 * Fotokiosek – webové účetnictví. Společný základ: databáze (SQLite), přihlášení, CSRF, pomocné funkce.
 * Požadavky: PHP 8.1+, rozšíření pdo_sqlite, HTTPS.
 */
declare(strict_types=1);

date_default_timezone_set('Europe/Prague');
error_reporting(E_ALL);
ini_set('display_errors', '0');

const APP_NAME = 'Fotokiosek – účetnictví';
/** Složka s databází – nesmí být dostupná z webu (viz .htaccess / README). */
define('DATA_DIR', getenv('FOTOKIOSEK_DATA_DIR') ?: dirname(__DIR__) . '/data');

// Místní nastavení serveru (instalační kód pro založení prvního účtu) – config.php podle config.example.php.
if (is_file(dirname(__DIR__) . '/config.php')) {
    require dirname(__DIR__) . '/config.php';
}
require __DIR__ . '/styles.php';

/** Ořízne text na $len znaků (bez mbstring po bajtech, ale bez rozbití UTF-8). */
function clip(mixed $v, int $len): string
{
    $s = is_string($v) ? $v : '';
    if (function_exists('mb_substr')) {
        return mb_substr($s, 0, $len);
    }
    return preg_replace('/.{' . $len . '}\K.*$/us', '', $s) ?? '';
}

function db(): PDO
{
    static $pdo = null;
    if ($pdo instanceof PDO) {
        return $pdo;
    }
    if (!is_dir(DATA_DIR) && !mkdir(DATA_DIR, 0770, true) && !is_dir(DATA_DIR)) {
        http_response_code(500);
        error_log('Fotokiosek: nejde vytvořit ' . DATA_DIR);
        exit('Nejde vytvořit složku pro data – zkontroluj práva (viz README).');
    }
    $pdo = new PDO('sqlite:' . DATA_DIR . '/fotokiosek.sqlite', null, null, [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
    ]);
    $pdo->exec('PRAGMA journal_mode = WAL');
    $pdo->exec('PRAGMA busy_timeout = 5000');
    $pdo->exec('PRAGMA foreign_keys = ON');
    migrate($pdo);
    return $pdo;
}

function migrate(PDO $pdo): void
{
    $pdo->exec(<<<SQL
        CREATE TABLE IF NOT EXISTS users (
            id INTEGER PRIMARY KEY,
            username TEXT NOT NULL UNIQUE,
            password_hash TEXT NOT NULL,
            created_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS devices (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            token_hash TEXT NOT NULL UNIQUE,
            created_at INTEGER NOT NULL,
            last_seen INTEGER,
            revoked INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS events (
            id INTEGER PRIMARY KEY,
            uuid TEXT NOT NULL UNIQUE,
            device_id INTEGER NOT NULL REFERENCES devices(id),
            device_label TEXT NOT NULL DEFAULT '',
            type TEXT NOT NULL,
            copies INTEGER NOT NULL DEFAULT 0,
            source TEXT NOT NULL DEFAULT '',
            style TEXT NOT NULL DEFAULT '',
            mode TEXT NOT NULL DEFAULT '',
            happened_at INTEGER NOT NULL,
            received_at INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS events_time ON events(happened_at);
        CREATE TABLE IF NOT EXISTS receipts (
            id INTEGER PRIMARY KEY,
            number TEXT NOT NULL UNIQUE,
            created_at INTEGER NOT NULL,
            period_from INTEGER NOT NULL,
            period_to INTEGER NOT NULL,
            copies INTEGER NOT NULL,
            unit_price REAL NOT NULL,
            total REAL NOT NULL,
            snapshot TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS settings (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS login_attempts (
            ip TEXT NOT NULL,
            at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS styles (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            icon TEXT NOT NULL DEFAULT '',
            prompt TEXT NOT NULL,
            sort INTEGER NOT NULL DEFAULT 0,
            thumb TEXT,
            updated_at INTEGER NOT NULL
        );
        SQL);
    // Knihovna AI stylů: výchozí styly jen jednou (smazané se pak samy nevrátí).
    $seeded = $pdo->query("SELECT value FROM settings WHERE key = 'styles_seeded'")->fetchColumn();
    if ($seeded === false) {
        seed_styles($pdo);
        $pdo->exec("INSERT OR REPLACE INTO settings(key, value) VALUES('styles_seeded', '1')");
    }
    // Cena za fotku platná v době tisku (změna ceny nepřepočítá minulost). Starší záznamy: NULL = aktuální cena.
    $cols = array_column($pdo->query('PRAGMA table_info(events)')->fetchAll(), 'name');
    if (!in_array('price', $cols, true)) {
        $pdo->exec('ALTER TABLE events ADD COLUMN price REAL');
    }
    // Uživatelé: role (admin = vše, viewer = přehled a účtenky), poslední přihlášení, změna hesla (odhlásí jiná zařízení).
    $ucols = array_column($pdo->query('PRAGMA table_info(users)')->fetchAll(), 'name');
    if (!in_array('role', $ucols, true)) {
        $pdo->exec("ALTER TABLE users ADD COLUMN role TEXT NOT NULL DEFAULT 'admin'");
    }
    if (!in_array('last_login', $ucols, true)) {
        $pdo->exec('ALTER TABLE users ADD COLUMN last_login INTEGER');
    }
    if (!in_array('password_changed', $ucols, true)) {
        $pdo->exec('ALTER TABLE users ADD COLUMN password_changed INTEGER NOT NULL DEFAULT 0');
    }
}

const ROLES = ['admin' => 'Správce', 'viewer' => 'Přehled a účtenky'];

// ---------------------------------------------------------------- nastavení

const SETTING_DEFAULTS = [
    'price' => '100',
    'currency' => 'Kč',
    'seller_name' => '',
    'seller_id' => '',
    'seller_vat' => '',
    'seller_address' => '',
    'receipt_note' => 'Děkujeme za návštěvu fotokoutku!',
];

function setting(string $key): string
{
    $st = db()->prepare('SELECT value FROM settings WHERE key = ?');
    $st->execute([$key]);
    $v = $st->fetchColumn();
    return $v === false ? (SETTING_DEFAULTS[$key] ?? '') : (string)$v;
}

function set_setting(string $key, string $value): void
{
    db()->prepare('INSERT OR REPLACE INTO settings(key, value) VALUES(?, ?)')
        ->execute([$key, $value]);
}

// ---------------------------------------------------------------- session, přihlášení, CSRF

function start_session(): void
{
    if (session_status() === PHP_SESSION_ACTIVE) {
        return;
    }
    $https = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
        || (($_SERVER['HTTP_X_FORWARDED_PROTO'] ?? '') === 'https');
    // Vlastní složka pro session: systémový úklid (např. cron v Debianu po 24 min) by jinak odhlašoval dřív.
    $dir = DATA_DIR . '/sessions';
    if (!is_dir($dir)) {
        @mkdir($dir, 0770, true);
    }
    if (is_dir($dir) && is_writable($dir)) {
        session_save_path($dir);
    }
    ini_set('session.gc_maxlifetime', (string)(8 * 3600));
    ini_set('session.use_strict_mode', '1');
    session_name('fotokiosek');
    session_set_cookie_params([
        'lifetime' => 0,
        'path' => '/',
        'secure' => $https,
        'httponly' => true,
        'samesite' => 'Lax',
    ]);
    session_start();
}

function current_user(): ?array
{
    start_session();
    if (empty($_SESSION['uid'])) {
        return null;
    }
    // Odhlášení po 8 h nečinnosti.
    if (time() - (int)($_SESSION['seen'] ?? 0) > 8 * 3600) {
        logout();
        return null;
    }
    $_SESSION['seen'] = time();
    $st = db()->prepare('SELECT id, username, role, password_changed FROM users WHERE id = ?');
    $st->execute([$_SESSION['uid']]);
    $user = $st->fetch();
    // Smazaný uživatel nebo změněné heslo (po přihlášení na tomhle zařízení) = odhlásit.
    if (!$user || (int)$user['password_changed'] > (int)($_SESSION['login_at'] ?? 0)) {
        logout();
        return null;
    }
    return $user;
}

function is_admin(array $user): bool
{
    return ($user['role'] ?? '') === 'admin';
}

/** Jen pro správce – ostatním stránka „nemáš oprávnění“. */
function require_admin(): array
{
    $user = require_login();
    if (!is_admin($user)) {
        http_response_code(403);
        page_header('Bez oprávnění', $user);
        echo '<section class="card"><h1>Bez oprávnění</h1><p class="muted">Tohle může dělat jen správce. <a href="index.php">Zpět na přehled</a></p></section>';
        page_footer();
        exit;
    }
    return $user;
}

/** Ověření nového hesla; vrací chybovou hlášku nebo null. */
function password_problem(string $password, string $again): ?string
{
    if (strlen($password) < 10) {
        return 'Heslo musí mít aspoň 10 znaků.';
    }
    if ($password !== $again) {
        return 'Hesla se neshodují.';
    }
    return null;
}

function valid_username(string $name): bool
{
    return (bool)preg_match('/^[\p{L}0-9._@-]{3,40}$/u', $name);
}

function require_login(): array
{
    $user = current_user();
    if ($user === null) {
        header('Location: login.php');
        exit;
    }
    return $user;
}

function login_user(int $uid): void
{
    start_session();
    session_regenerate_id(true);
    $_SESSION['uid'] = $uid;
    $_SESSION['seen'] = time();
    $_SESSION['login_at'] = time();
    db()->prepare('UPDATE users SET last_login = ? WHERE id = ?')->execute([time(), $uid]);
}

function logout(): void
{
    start_session();
    $_SESSION = [];
    session_destroy();
}

function csrf_token(): string
{
    start_session();
    if (empty($_SESSION['csrf'])) {
        $_SESSION['csrf'] = bin2hex(random_bytes(32));
    }
    return $_SESSION['csrf'];
}

function csrf_field(): string
{
    return '<input type="hidden" name="csrf" value="' . h(csrf_token()) . '">';
}

function check_csrf(): void
{
    $sent = $_POST['csrf'] ?? '';
    if (!is_string($sent) || !hash_equals(csrf_token(), $sent)) {
        http_response_code(400);
        exit('Neplatný formulář (vypršel?). Vrať se zpět a zkus to znovu.');
    }
}

/** Po 10 špatných pokusech z jedné IP 15 minut pauza. */
function login_blocked(string $ip): bool
{
    db()->prepare('DELETE FROM login_attempts WHERE at < ?')->execute([time() - 900]);
    $st = db()->prepare('SELECT COUNT(*) FROM login_attempts WHERE ip = ?');
    $st->execute([$ip]);
    return (int)$st->fetchColumn() >= 10;
}

function login_failed(string $ip): void
{
    db()->prepare('INSERT INTO login_attempts(ip, at) VALUES(?, ?)')->execute([$ip, time()]);
}

// ---------------------------------------------------------------- pomocné

function h(?string $s): string
{
    return htmlspecialchars($s ?? '', ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
}

function money(float $amount): string
{
    return number_format($amount, 2, ',', ' ') . ' ' . setting('currency');
}

function fmt_time(int $ts): string
{
    return date('j. n. Y H:i', $ts);
}

function fmt_date(int $ts): string
{
    return date('j. n. Y', $ts);
}

/** Datum z formuláře (YYYY-MM-DD) → časové razítko začátku dne; neplatné → $fallback. */
function parse_day(mixed $value, int $fallback): int
{
    if (is_string($value) && preg_match('/^(\d{4})-(\d{2})-(\d{2})$/', $value, $p) && checkdate((int)$p[2], (int)$p[3], (int)$p[1])) {
        $ts = strtotime($value . ' 00:00:00');
        if ($ts !== false) {
            return $ts;
        }
    }
    return $fallback;
}

/** Text z formuláře (pole místo textu = prázdný řetězec, žádná chyba). */
function post_str(string $key): string
{
    $v = $_POST[$key] ?? '';
    return is_string($v) ? $v : '';
}

/** „1 000,50“, „99,5“, „100“ → číslo; neplatné → null. */
function parse_price(string $s): ?float
{
    $s = str_replace([' ', "\u{00A0}", "\u{202F}", ','], ['', '', '', '.'], trim($s));
    return is_numeric($s) ? (float)$s : null;
}

/** Cena za fotku nastavená teď. */
function current_price(): float
{
    return parse_price(setting('price')) ?? 0.0;
}

/** IP klienta; za reverzní proxy (TRUSTED_PROXY v config.php) první adresa z X-Forwarded-For. */
function client_ip(): string
{
    $ip = $_SERVER['REMOTE_ADDR'] ?? '?';
    if (defined('TRUSTED_PROXY') && $ip === TRUSTED_PROXY && !empty($_SERVER['HTTP_X_FORWARDED_FOR'])) {
        $first = trim(explode(',', (string)$_SERVER['HTTP_X_FORWARDED_FOR'])[0]);
        if (filter_var($first, FILTER_VALIDATE_IP)) {
            return $first;
        }
    }
    return $ip;
}

/** Čas z formuláře (HH:MM) → „HH:MM“; neplatný → $default. */
function parse_clock(mixed $value, string $default): string
{
    if (is_string($value) && preg_match('/^([01]\d|2[0-3]):([0-5]\d)$/', $value)) {
        return $value;
    }
    return $default;
}

/**
 * Období z formuláře: from + from_time, to + to_time (výchozí celé dny 00:00–23:59).
 * Vrací from (začátek), end (konec – výhradně, minuta „do“ se ještě počítá) a hodnoty pro formulář.
 */
function parse_range(array $src, int $defaultFrom, int $defaultTo): array
{
    $fromDay = parse_day($src['from'] ?? null, $defaultFrom);
    $toDay = parse_day($src['to'] ?? null, $defaultTo);
    $fromTime = parse_clock($src['from_time'] ?? null, '00:00');
    $toTime = parse_clock($src['to_time'] ?? null, '23:59');
    $from = strtotime(date('Y-m-d', $fromDay) . ' ' . $fromTime);
    $last = strtotime(date('Y-m-d', $toDay) . ' ' . $toTime);
    if ($last < $from) {
        [$from, $last] = [$last, $from];
    }
    return [
        'from' => $from,
        'end' => $last + 60,
        'last' => $last,
        'from_day' => date('Y-m-d', $from),
        'from_time' => date('H:i', $from),
        'to_day' => date('Y-m-d', $last),
        'to_time' => date('H:i', $last),
    ];
}

/** Období čitelně: celé dny jen data, jinak i časy. */
function fmt_period(int $from, int $last): string
{
    $wholeDays = date('H:i', $from) === '00:00' && in_array(date('H:i', $last), ['00:00', '23:59'], true);
    if ($wholeDays) {
        return date('Y-m-d', $from) === date('Y-m-d', $last) ? fmt_date($from) : fmt_date($from) . ' – ' . fmt_date($last);
    }
    if (date('Y-m-d', $from) === date('Y-m-d', $last)) {
        return fmt_date($from) . ' ' . date('H:i', $from) . ' – ' . date('H:i', $last);
    }
    return fmt_time($from) . ' – ' . fmt_time($last);
}

function token_hash(string $token): string
{
    return hash('sha256', $token);
}

/** Automat podle hlavičky Authorization: Bearer <token> (aktivní token), jinak null. Zapíše „naposledy se ozval“. */
function device_from_bearer(): ?array
{
    // Některé servery hlavičku Authorization skryjí – proto i REDIRECT_HTTP_AUTHORIZATION.
    $auth = $_SERVER['HTTP_AUTHORIZATION'] ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '';
    if (!preg_match('/^Bearer\s+([A-Za-z0-9_\-]{20,128})$/', trim((string)$auth), $m)) {
        return null;
    }
    $st = db()->prepare('SELECT id, name FROM devices WHERE token_hash = ? AND revoked = 0');
    $st->execute([token_hash($m[1])]);
    $device = $st->fetch();
    if (!$device) {
        return null;
    }
    db()->prepare('UPDATE devices SET last_seen = ? WHERE id = ?')->execute([time(), $device['id']]);
    return $device;
}

function page_header(string $title, ?array $user = null): void
{
    // Session (a CSRF token) musí vzniknout před prvním výpisem – kvůli cookie.
    csrf_token();
    header('X-Frame-Options: DENY');
    header('Cache-Control: no-store');
    header('Referrer-Policy: same-origin');
    header('X-Content-Type-Options: nosniff');
    header("Content-Security-Policy: default-src 'self'; style-src 'self'; script-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
    echo '<!DOCTYPE html><html lang="cs"><head><meta charset="utf-8">';
    echo '<meta name="viewport" content="width=device-width, initial-scale=1">';
    echo '<title>' . h($title) . ' · ' . h(APP_NAME) . '</title>';
    echo '<link rel="stylesheet" href="assets/style.css"></head><body>';
    if ($user !== null) {
        $current = basename((string)($_SERVER['SCRIPT_NAME'] ?? ''));
        if ($current === 'receipt.php') {
            $current = 'receipts.php';
        }
        echo '<header class="top no-print"><span class="brand"><span class="dot"></span>Fotokiosek</span><nav>';
        $items = ['index.php' => 'Přehled', 'receipts.php' => 'Účtenky'];
        if (is_admin($user)) {
            $items['styles.php'] = 'AI styly';
            $items['users.php'] = 'Uživatelé';
        }
        $items['settings.php'] = 'Nastavení';
        foreach ($items as $href => $label) {
            echo '<a href="' . $href . '"' . ($current === $href ? ' aria-current="page"' : '') . '>' . $label . '</a>';
        }
        echo '</nav><form method="post" action="logout.php" class="logout">' . csrf_field()
            . '<button class="link" title="Přihlášen: ' . h($user['username']) . '">Odhlásit</button></form>';
        echo '</header>';
    }
    echo '<main>';
}

function page_footer(): void
{
    echo '</main></body></html>';
}
