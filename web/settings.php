<?php
/** Nastavení: cena a údaje na účtenku, automaty (tokeny), změna hesla. */
declare(strict_types=1);
require __DIR__ . '/inc/bootstrap.php';
$user = require_login();

$msg = '';
$err = '';
$newToken = null;
$newDeviceName = '';

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    check_csrf();
    $action = (string)($_POST['action'] ?? '');

    if (in_array($action, ['billing', 'add_device', 'revoke_device'], true) && !is_admin($user)) {
        http_response_code(403);
        exit('Jen pro správce.');
    }
    if ($action === 'billing') {
        $price = parse_price(post_str('price'));
        if ($price === null || $price < 0 || $price > 100000) {
            $err = 'Cena musí být číslo (např. 100 nebo 99,50).';
        } else {
            set_setting('price', (string)round($price, 2));
            foreach (['currency' => 10, 'seller_name' => 120, 'seller_id' => 20, 'seller_vat' => 20, 'seller_address' => 300, 'receipt_note' => 300] as $key => $len) {
                set_setting($key, clip(trim((string)($_POST[$key] ?? '')), $len));
            }
            $msg = 'Uloženo.';
        }
    } elseif ($action === 'add_device') {
        $name = clip(trim((string)($_POST['name'] ?? '')), 60);
        if ($name === '') {
            $err = 'Zadej jméno automatu.';
        } else {
            $newToken = bin2hex(random_bytes(24)); // 48 znaků
            $newDeviceName = $name;
            db()->prepare('INSERT INTO devices(name, token_hash, created_at) VALUES(?, ?, ?)')
                ->execute([$name, token_hash($newToken), time()]);
            $msg = 'Automat přidán – načti jeho QR kód v automatu (níže). Token i QR kód jsou vidět jen teď.';
        }
    } elseif ($action === 'revoke_device') {
        db()->prepare('UPDATE devices SET revoked = 1 WHERE id = ?')->execute([(int)($_POST['id'] ?? 0)]);
        $msg = 'Token zrušen – automat už nemůže posílat záznamy (staré zůstávají).';
    } elseif ($action === 'password') {
        $st = db()->prepare('SELECT password_hash FROM users WHERE id = ?');
        $st->execute([$user['id']]);
        $hash = (string)$st->fetchColumn();
        $old = post_str('old');
        $new = post_str('new');
        if (!password_verify($old, $hash)) {
            $err = 'Současné heslo nesedí.';
        } elseif (($problem = password_problem($new, post_str('new2'))) !== null) {
            $err = $problem;
        } else {
            // Změna hesla odhlásí ostatní zařízení; tohle zůstane přihlášené.
            db()->prepare('UPDATE users SET password_hash = ?, password_changed = ? WHERE id = ?')
                ->execute([password_hash($new, PASSWORD_DEFAULT), time(), $user['id']]);
            login_user((int)$user['id']);
            $_SESSION['login_at'] = time() + 1;
            $msg = 'Heslo změněno. Ostatní zařízení s tímhle účtem se odhlásila.';
        }
    }
}

$devices = db()->query('SELECT * FROM devices ORDER BY revoked, id')->fetchAll();
/** Obsah QR pro automat; jméno se zkrátí nebo vynechá, aby se kód vešel (max. 213 bajtů – generátor umí verzi 10). */
function report_qr_payload(string $url, string $token, string $name): string
{
    $base = 'fotokiosek:report?u=' . rawurlencode($url) . '&t=' . $token;
    $name = clip($name, 40); // automat si stejně nechá max. 40 znaků
    while ($name !== '') {
        $payload = $base . '&n=' . rawurlencode($name);
        if (strlen($payload) <= 213) {
            return $payload;
        }
        $len = function_exists('mb_strlen') ? mb_strlen($name) : strlen($name);
        $name = clip($name, max(0, $len - 1));
    }
    return $base;
}

$apiUrl = ((!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off') || (($_SERVER['HTTP_X_FORWARDED_PROTO'] ?? '') === 'https') ? 'https' : 'http')
    . '://' . ($_SERVER['HTTP_HOST'] ?? 'tvuj-server') . rtrim(dirname($_SERVER['SCRIPT_NAME'] ?? '/'), '/\\') . '/api.php';

page_header('Nastavení', $user);
?>
<h1>Nastavení</h1>
<?php if (isset($_GET['welcome'])): ?><p class="ok">Účet je založený. Nastav cenu fotky a přidej automat.</p><?php endif; ?>
<?php if ($msg !== ''): ?><p class="ok"><?= h($msg) ?></p><?php endif; ?>
<?php if ($err !== ''): ?><p class="error"><?= h($err) ?></p><?php endif; ?>

<?php if (is_admin($user)): ?>
<section class="card">
    <h2>Cena a údaje na účtenku</h2>
    <form method="post" class="grid">
        <?= csrf_field() ?><input type="hidden" name="action" value="billing">
        <label>Cena za 1 vytištěnou fotku <input name="price" value="<?= h(setting('price')) ?>" inputmode="decimal" required></label>
        <label>Měna <input name="currency" value="<?= h(setting('currency')) ?>"></label>
        <label>Prodávající (jméno / firma) <input name="seller_name" value="<?= h(setting('seller_name')) ?>"></label>
        <label>IČO <input name="seller_id" value="<?= h(setting('seller_id')) ?>"></label>
        <label>DIČ (jen plátce DPH) <input name="seller_vat" value="<?= h(setting('seller_vat')) ?>"></label>
        <label class="wide">Adresa <textarea name="seller_address" rows="2"><?= h(setting('seller_address')) ?></textarea></label>
        <label class="wide">Poznámka dole na účtence <textarea name="receipt_note" rows="2"><?= h(setting('receipt_note')) ?></textarea></label>
        <button class="primary">Uložit</button>
    </form>
    <p class="muted">Změna ceny platí pro fotky vytištěné od teď – starší tisky i vystavené účtenky si drží cenu z doby tisku.</p>
</section>

<section class="card">
    <h2>Automaty</h2>
    <?php if ($newToken !== null): ?>
        <div class="token">
            <div class="qr-box">
                <div class="qr" data-qr="<?= h(report_qr_payload($apiUrl, $newToken, $newDeviceName)) ?>"></div>
                <div>
                    <p><strong>Načti QR kód automatem:</strong> menu správce → <strong>Účetnictví (web)</strong> → <em>📷 Načíst adresu a token z QR kódu</em>
                        a podrž telefon nebo monitor s tímhle kódem před kamerou. Vyplní se adresa, token i jméno automatu.</p>
                    <p class="muted">Nebo ručně:</p>
                    <p>Adresa API: <code><?= h($apiUrl) ?></code></p>
                    <p>Token: <code class="big"><?= h($newToken) ?></code></p>
                </div>
            </div>
            <?php if (str_starts_with($apiUrl, 'http://')): ?>
                <p class="error">Pozor: adresa je bez https – token by šel sítí nešifrovaně. Otevři účetnictví přes https:// a přidej automat znovu.</p>
            <?php endif; ?>
            <p class="muted">Token ani QR kód se už znovu neukážou. Když ho ztratíš, zruš token a přidej automat znovu.</p>
        </div>
    <?php endif; ?>
    <?php if ($devices): ?>
        <div class="table-wrap"><table class="stack">
            <thead><tr><th>Jméno</th><th>Přidán</th><th>Naposledy se ozval</th><th>Stav</th><th></th></tr></thead>
            <tbody>
            <?php foreach ($devices as $d): ?>
                <tr>
                    <td class="head"><?= h($d['name']) ?></td>
                    <td data-label="Přidán"><?= h(fmt_date((int)$d['created_at'])) ?></td>
                    <td data-label="Naposledy se ozval"><?= $d['last_seen'] ? h(fmt_time((int)$d['last_seen'])) : '–' ?></td>
                    <td data-label="Stav"><span class="badge <?= $d['revoked'] ? 'off' : 'ok' ?>"><?= $d['revoked'] ? 'zrušen' : 'aktivní' ?></span></td>
                    <td class="r" data-label=""><?php if (!$d['revoked']): ?>
                        <form method="post" class="inline confirm" data-confirm="Opravdu zrušit token automatu <?= h($d['name']) ?>?">
                            <?= csrf_field() ?><input type="hidden" name="action" value="revoke_device"><input type="hidden" name="id" value="<?= (int)$d['id'] ?>">
                            <button class="link danger">Zrušit token</button>
                        </form>
                    <?php endif; ?></td>
                </tr>
            <?php endforeach; ?>
            </tbody>
        </table></div>
    <?php endif; ?>
    <form method="post" class="row">
        <?= csrf_field() ?><input type="hidden" name="action" value="add_device">
        <label>Jméno nového automatu <input name="name" placeholder="např. Fotokiosek OC Chodov" required></label>
        <button class="primary">Přidat automat a vytvořit token</button>
    </form>
</section>

<section class="card">
    <h2>QR kód pro Gemini API klíč</h2>
    <p class="muted">Vlož klíč z Google AI Studio a vytvoř QR kód. Vznikne jen tady v prohlížeči – klíč se nikam neposílá ani neukládá.
        V automatu pak v <strong>AI &amp; API nastavení</strong> klepni na <em>📷 Načíst klíč z QR kódu</em>.</p>
    <div class="row">
        <label>Gemini API klíč <input type="password" id="gemini-key" autocomplete="off" spellcheck="false"></label>
        <button type="button" id="gemini-qr-btn">Vytvořit QR kód</button>
    </div>
    <p class="error" id="gemini-qr-err" hidden></p>
    <div class="qr" id="gemini-qr" hidden></div>
</section>

<?php endif; ?>

<section class="card">
    <h2>Změna hesla</h2>
    <p class="muted">Přihlášen jako <strong><?= h($user['username']) ?></strong> (<?= h(ROLES[$user['role']] ?? $user['role']) ?>).</p>
    <form method="post" class="grid">
        <?= csrf_field() ?><input type="hidden" name="action" value="password">
        <label>Současné heslo <input type="password" name="old" required autocomplete="current-password"></label>
        <label>Nové heslo (aspoň 10 znaků) <input type="password" name="new" required autocomplete="new-password"></label>
        <label>Nové heslo znovu <input type="password" name="new2" required autocomplete="new-password"></label>
        <button>Změnit heslo</button>
    </form>
</section>
<script src="assets/confirm.js"></script>
<script src="assets/qr.js"></script>
<script src="assets/qrtools.js"></script>
<?php page_footer();
