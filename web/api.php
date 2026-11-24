<?php
/**
 * Příjem událostí z automatu (ReportWorker.kt).
 * POST JSON, hlavička Authorization: Bearer <token automatu>.
 * {"uuid", "type": "print"|"ai"|"ping", "copies", "source", "style", "mode", "device", "time": "2026-11-25T14:03:00+0100"}
 * Stejné uuid podruhé = ignorovat (automat posílá znovu, když nedostane odpověď).
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

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    reply(405, ['ok' => false, 'error' => 'Použij POST']);
}

// Token automatu (některé servery hlavičku Authorization skryjí – proto i REDIRECT_HTTP_AUTHORIZATION).
$auth = $_SERVER['HTTP_AUTHORIZATION'] ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '';
if (!preg_match('/^Bearer\s+([A-Za-z0-9_\-]{20,128})$/', trim($auth), $m)) {
    reply(401, ['ok' => false, 'error' => 'Chybí nebo neplatný token']);
}
$st = db()->prepare('SELECT id, name FROM devices WHERE token_hash = ? AND revoked = 0');
$st->execute([token_hash($m[1])]);
$device = $st->fetch();
if (!$device) {
    reply(401, ['ok' => false, 'error' => 'Neznámý nebo zrušený token']);
}
db()->prepare('UPDATE devices SET last_seen = ? WHERE id = ?')->execute([time(), $device['id']]);

$raw = file_get_contents('php://input', false, null, 0, 16384);
$data = json_decode($raw ?: '', true);
if (!is_array($data)) {
    reply(400, ['ok' => false, 'error' => 'Neplatný JSON']);
}

$type = (string)($data['type'] ?? '');
if ($type === 'ping') {
    reply(200, ['ok' => true, 'device' => $device['name']]);
}
if (!in_array($type, ['print', 'ai'], true)) {
    reply(422, ['ok' => false, 'error' => 'Neznámý typ události']);
}

$uuid = (string)($data['uuid'] ?? '');
if (!preg_match('/^[0-9a-fA-F-]{36}$/', $uuid)) {
    reply(422, ['ok' => false, 'error' => 'Chybí uuid']);
}
$copies = (int)($data['copies'] ?? 0);
if ($type === 'print' && ($copies < 1 || $copies > 99)) {
    reply(422, ['ok' => false, 'error' => 'Neplatný počet kopií']);
}

// Čas z automatu; nesmyslný (špatně nastavené hodiny) → čas příjmu.
$happened = strtotime((string)($data['time'] ?? '')) ?: time();
if ($happened > time() + 86400 || $happened < strtotime('2020-01-01')) {
    $happened = time();
}

$ins = db()->prepare(
    'INSERT OR IGNORE INTO events(uuid, device_id, device_label, type, copies, source, style, mode, happened_at, received_at, price)
     VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)'
);
$ins->execute([
    strtolower($uuid),
    $device['id'],
    clip($data['device'] ?? '', 60),
    $type,
    $type === 'print' ? $copies : 0,
    clip($data['source'] ?? '', 20),
    clip($data['style'] ?? '', 60),
    clip($data['mode'] ?? '', 20),
    $happened,
    time(),
    $type === 'print' ? current_price() : null,
]);
reply(200, ['ok' => true, 'duplicate' => $ins->rowCount() === 0]);
