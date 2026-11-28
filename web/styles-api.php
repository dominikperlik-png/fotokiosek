<?php
/**
 * Knihovna AI stylů pro automaty (StyleSync.kt). Hlavička Authorization: Bearer <token automatu>.
 * GET                          → {"ok":true,"styles":[{"id","name","icon","prompt","thumb":"comic-9f3e1a2b.jpg"|null,"updated"}],
 *                                  "config":{"max_copies"|null,"price_photo","price_ai","price_email","currency"}}  (kiosk_config())
 * GET ?thumb=comic-9f3e1a2b.jpg → obrázek náhledu (jen soubory, které patří nějakému stylu).
 */
declare(strict_types=1);
require __DIR__ . '/inc/bootstrap.php';

header('Cache-Control: no-store');

function reply(int $code, array $body): never
{
    http_response_code($code);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode($body, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    reply(405, ['ok' => false, 'error' => 'Použij GET']);
}
if (device_from_bearer() === null) {
    reply(401, ['ok' => false, 'error' => 'Chybí, neznámý nebo zrušený token']);
}

if (isset($_GET['thumb'])) {
    $file = (string)$_GET['thumb'];
    $st = db()->prepare('SELECT 1 FROM styles WHERE thumb = ?');
    $st->execute([$file]);
    $path = styles_dir() . '/' . $file;
    if (!preg_match(THUMB_PATTERN, $file) || $st->fetchColumn() === false || !is_file($path)) {
        reply(404, ['ok' => false, 'error' => 'Náhled nenalezen']);
    }
    send_image_file($path);
}

$styles = array_map(fn(array $s) => [
    'id' => $s['id'],
    'name' => $s['name'],
    'icon' => $s['icon'],
    'prompt' => $s['prompt'],
    // Soubor chybí (např. obnovená jen databáze) → bez náhledu, ať automat nečeká na 404.
    'thumb' => ($s['thumb'] && is_file(styles_dir() . '/' . $s['thumb'])) ? $s['thumb'] : null,
    'updated' => (int)$s['updated_at'],
], all_styles());
reply(200, ['ok' => true, 'styles' => $styles, 'config' => kiosk_config()]);
