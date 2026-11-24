<?php
/** Přihlášení. Když ještě není žádný uživatel, stránka nabídne jeho založení (první spuštění). */
declare(strict_types=1);
require __DIR__ . '/inc/bootstrap.php';

$firstRun = (int)db()->query('SELECT COUNT(*) FROM users')->fetchColumn() === 0;
$ip = client_ip();
$error = '';

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    check_csrf();
    $username = trim(post_str('username'));
    $password = post_str('password');

    if ($firstRun) {
        $password2 = post_str('password2');
        $code = post_str('setup_code');
        if (!defined('SETUP_CODE') || strlen((string)SETUP_CODE) < 8 || SETUP_CODE === 'ZMEN-MNE-na-dlouhy-nahodny-kod') {
            $error = 'Nejdřív na serveru vytvoř config.php (podle config.example.php) s instalačním kódem.';
        } elseif (login_blocked($ip)) {
            $error = 'Moc neúspěšných pokusů. Zkus to za 15 minut.';
        } elseif (!hash_equals((string)SETUP_CODE, $code)) {
            login_failed($ip);
            $error = 'Špatný instalační kód.';
        } elseif (!preg_match('/^[\p{L}0-9._@-]{3,40}$/u', $username)) {
            $error = 'Jméno: 3–40 znaků (písmena, čísla, . _ @ -).';
        } elseif (strlen($password) < 10) {
            $error = 'Heslo musí mít aspoň 10 znaků.';
        } elseif ($password !== $password2) {
            $error = 'Hesla se neshodují.';
        } else {
            db()->prepare('INSERT INTO users(username, password_hash, created_at) VALUES(?, ?, ?)')
                ->execute([$username, password_hash($password, PASSWORD_DEFAULT), time()]);
            login_user((int)db()->lastInsertId());
            header('Location: settings.php?welcome=1');
            exit;
        }
    } elseif (login_blocked($ip)) {
        $error = 'Moc neúspěšných pokusů. Zkus to za 15 minut.';
    } else {
        $st = db()->prepare('SELECT id, password_hash FROM users WHERE username = ?');
        $st->execute([$username]);
        $row = $st->fetch();
        // Ověřit heslo i u neexistujícího jména (stejná doba odpovědi – neprozradí, které jméno existuje).
        $ok = password_verify($password, $row['password_hash'] ?? password_hash('neexistujici-ucet', PASSWORD_DEFAULT));
        if ($row && $ok) {
            if (password_needs_rehash($row['password_hash'], PASSWORD_DEFAULT)) {
                db()->prepare('UPDATE users SET password_hash = ? WHERE id = ?')
                    ->execute([password_hash($password, PASSWORD_DEFAULT), $row['id']]);
            }
            login_user((int)$row['id']);
            header('Location: index.php');
            exit;
        }
        login_failed($ip);
        $error = 'Špatné jméno nebo heslo.';
    }
}

if (!$firstRun && current_user() !== null) {
    header('Location: index.php');
    exit;
}

page_header($firstRun ? 'První spuštění' : 'Přihlášení');
?>
<section class="card narrow">
    <h1><?= $firstRun ? 'Vítej! Založ si účet' : 'Přihlášení' ?></h1>
    <?php if ($firstRun): ?>
        <p class="muted">Tohle je první spuštění účetnictví. Zadej instalační kód z <code>config.php</code> na serveru a zvol si přihlašovací jméno a heslo (aspoň 10 znaků).</p>
    <?php endif; ?>
    <?php if ($error !== ''): ?><p class="error"><?= h($error) ?></p><?php endif; ?>
    <form method="post" autocomplete="on">
        <?= csrf_field() ?>
        <?php if ($firstRun): ?>
            <label>Instalační kód <input type="password" name="setup_code" required autocomplete="off"></label>
        <?php endif; ?>
        <label>Jméno <input name="username" required autofocus autocomplete="username" value="<?= h(post_str('username')) ?>"></label>
        <label>Heslo <input type="password" name="password" required autocomplete="<?= $firstRun ? 'new-password' : 'current-password' ?>"></label>
        <?php if ($firstRun): ?>
            <label>Heslo znovu <input type="password" name="password2" required autocomplete="new-password"></label>
        <?php endif; ?>
        <button class="primary"><?= $firstRun ? 'Založit účet' : 'Přihlásit' ?></button>
    </form>
</section>
<?php page_footer();
