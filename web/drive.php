<?php
/**
 * Připojení Google Disku (jen správce): odkaz GET ?action=connect&csrf=… → přesměrování na přihlášení Googlem
 * (odkaz, ne formulář: CSP form-action 'self' by přesměrování odeslaného formuláře na Google zablokovalo),
 * Google vrátí GET ?code=&state= sem (adresa musí být v Google Cloud mezi Authorized redirect URIs),
 * POST action=disconnect → odpojit. Výsledek se ukáže v Nastavení.
 */
declare(strict_types=1);
require __DIR__ . '/inc/bootstrap.php';
$user = require_admin();

function back_to_settings(string $result, string $detail = ''): never
{
    if ($detail !== '') {
        $_SESSION['drive_msg'] = $detail;
    }
    header('Location: settings.php?drive=' . rawurlencode($result) . '#drive');
    exit;
}

if (!drive_configured()) {
    back_to_settings('error', 'Na serveru chybí GOOGLE_CLIENT_ID a GOOGLE_CLIENT_SECRET v config.php.');
}

if (($_GET['action'] ?? '') === 'connect') {
    // Jednorázový kód odkazu z Nastavení (ne CSRF token formulářů – ten by zůstal v historii a logech).
    $sent = $_GET['csrf'] ?? '';
    $link = (string)($_SESSION['drive_link'] ?? '');
    unset($_SESSION['drive_link']);
    if (!is_string($sent) || $link === '' || !hash_equals($link, $sent)) {
        back_to_settings('error', 'Odkaz vypršel – načti Nastavení a klikni znovu.');
    }
    $state = bin2hex(random_bytes(16));
    $_SESSION['drive_state'] = $state;
    header('Location: ' . drive_auth_url($state));
    exit;
}

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    check_csrf();
    $action = post_str('action');
    if ($action === 'disconnect') {
        drive_disconnect();
        back_to_settings('off');
    }
    back_to_settings('error', 'Neznámá akce.');
}

// Návrat z Googlu.
$state = is_string($_GET['state'] ?? null) ? $_GET['state'] : '';
$expected = (string)($_SESSION['drive_state'] ?? '');
unset($_SESSION['drive_state']);
if ($expected === '' || !hash_equals($expected, $state)) {
    back_to_settings('error', 'Přihlášení vypršelo nebo nepatří k tomuto webu – zkus „Připojit Google Disk“ znovu.');
}
if (isset($_GET['error'])) {
    $e = is_string($_GET['error']) ? $_GET['error'] : '';
    back_to_settings('error', $e === 'access_denied' ? 'Přístup jsi v Googlu nepovolil.' : 'Google vrátil chybu: ' . clip($e, 80));
}
$code = is_string($_GET['code'] ?? null) ? $_GET['code'] : '';
if ($code === '' || strlen($code) > 2000) {
    back_to_settings('error', 'Google nevrátil kód přihlášení.');
}
try {
    drive_connect_with_code($code);
} catch (RuntimeException $e) {
    back_to_settings('error', $e->getMessage());
}
back_to_settings('ok');
