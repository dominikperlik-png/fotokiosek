<?php
/**
 * Záloha fotek z automatu na Google Disk (PhotoUploadWorker.kt).
 * POST surový JPEG (Content-Type: image/jpeg), hlavička Authorization: Bearer <token automatu>,
 * v adrese ?uuid=<36 znaků>&kind=photo|ai&taken=<unix čas pořízení>.
 * Fotka jde do Google Disku: Fotokiosek/RRRR-MM-DD/RRRR-MM-DD_HH-MM-SS_<automat>_<photo|ai>_<uuid8>.jpg.
 * Stejné uuid podruhé = hotovo (automat posílá znovu, když nedostane odpověď).
 * Odpovědi: 200 hotovo, 4xx špatná data (automat fotku zahodí), 503 Disk nepřipojený / nedostupný (zkusí později).
 * GET (s tokenem) = jen zjistit, jestli záloha jde (200 / 503) – automat ho volá před odesláním fotky,
 * aby při nepřipojeném Disku neposílal pořád dokola celé fotky.
 */
declare(strict_types=1);
require __DIR__ . '/inc/bootstrap.php';

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

function reply(int $code, array $body): never
{
    http_response_code($code);
    echo json_encode($body, JSON_UNESCAPED_UNICODE);
    exit;
}

const MAX_PHOTO_BYTES = 15_000_000;

if (!in_array($_SERVER['REQUEST_METHOD'], ['GET', 'POST'], true)) {
    reply(405, ['ok' => false, 'error' => 'Použij POST']);
}
$device = device_from_bearer();
if ($device === null) {
    reply(401, ['ok' => false, 'error' => 'Chybí, neznámý nebo zrušený token']);
}
if ($_SERVER['REQUEST_METHOD'] === 'GET') {
    drive_connected()
        ? reply(200, ['ok' => true])
        : reply(503, ['ok' => false, 'error' => 'Google Disk není připojený (účetnictví → Nastavení → Google Disk)']);
}

$uuid = strtolower((string)($_GET['uuid'] ?? ''));
$kind = (string)($_GET['kind'] ?? '');
$taken = (int)($_GET['taken'] ?? 0);
if (!preg_match('/^[0-9a-f-]{36}$/D', $uuid)) {
    reply(422, ['ok' => false, 'error' => 'Chybí uuid']);
}
if (!in_array($kind, ['photo', 'ai'], true)) {
    reply(422, ['ok' => false, 'error' => 'Neznámý druh fotky']);
}
// Nesmyslný čas (špatné hodiny automatu) → teď.
if ($taken > time() + 86400 || $taken < strtotime('2020-01-01')) {
    $taken = time();
}

$st = db()->prepare('SELECT drive_id, created_at FROM uploads WHERE uuid = ?');
$st->execute([$uuid]);
$prev = $st->fetch();
if ($prev && $prev['drive_id'] !== '') {
    reply(200, ['ok' => true, 'duplicate' => true]);
}
if ($prev && (int)$prev['created_at'] > time() - 600) {
    // Stejná fotka se právě nahrává (opakované odeslání po vypršení času) – ať se nenahraje dvakrát.
    reply(503, ['ok' => false, 'error' => 'Fotka se právě nahrává']);
}

$length = (int)($_SERVER['CONTENT_LENGTH'] ?? 0);
if ($length > MAX_PHOTO_BYTES) {
    reply(413, ['ok' => false, 'error' => 'Fotka je moc velká']);
}
$bytes = (string)file_get_contents('php://input', false, null, 0, MAX_PHOTO_BYTES + 1);
if (strlen($bytes) > MAX_PHOTO_BYTES) {
    reply(413, ['ok' => false, 'error' => 'Fotka je moc velká']);
}
if ($bytes === '' && $length > 0) {
    // PHP tělo zahodilo (post_max_size) – chyba serveru, ne fotky: automat to zkusí znovu.
    error_log('Fotokiosek: záloha fotky – tělo požadavku zahozeno (zvyš post_max_size, nyní ' . ini_get('post_max_size') . ')');
    reply(503, ['ok' => false, 'error' => 'Fotka je větší než limit serveru (post_max_size ' . ini_get('post_max_size') . ')']);
}
if (strlen($bytes) < 100 || !str_starts_with($bytes, "\xFF\xD8\xFF")) {
    reply(415, ['ok' => false, 'error' => 'Tělo požadavku není JPEG']);
}

@set_time_limit(180); // nahrání na Disk může trvat

if (!drive_connected()) {
    reply(503, ['ok' => false, 'error' => 'Google Disk není připojený (účetnictví → Nastavení → Google Disk)']);
}

// Rezervace (proti dvojímu nahrání při souběžném opakování); po chybě se uvolní.
db()->prepare('INSERT OR REPLACE INTO uploads(uuid, device_id, kind, name, drive_id, taken_at, created_at) VALUES(?, ?, ?, ?, \'\', ?, ?)')
    ->execute([$uuid, $device['id'], $kind, '', $taken, time()]);

$day = date('Y-m-d', $taken);
$name = date('Y-m-d_H-i-s', $taken) . '_' . ascii_slug($device['name'], 30, 'automat') . '_' . $kind . '_' . substr($uuid, 0, 8) . '.jpg';

try {
    try {
        $fileId = drive_upload_jpeg(drive_day_folder($day), $name, $bytes);
    } catch (RuntimeException $e) {
        // Složku dne mezitím někdo smazal → najít / založit znovu a zkusit ještě jednou.
        if ($e->getCode() !== 404) {
            throw $e;
        }
        $fileId = drive_upload_jpeg(drive_day_folder($day, true), $name, $bytes);
    }
} catch (RuntimeException $e) {
    db()->prepare("DELETE FROM uploads WHERE uuid = ? AND drive_id = ''")->execute([$uuid]);
    error_log('Fotokiosek: záloha fotky na Google Disk selhala: ' . $e->getMessage());
    reply(503, ['ok' => false, 'error' => $e->getMessage()]);
}

db()->prepare('UPDATE uploads SET name = ?, drive_id = ?, created_at = ? WHERE uuid = ?')
    ->execute([$name, $fileId, time(), $uuid]);
reply(200, ['ok' => true, 'name' => $name]);
