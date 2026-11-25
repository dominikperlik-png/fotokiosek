<?php
/**
 * Uživatelé (jen správce): přidat, změnit roli, nastavit nové heslo, smazat.
 * Role: Správce = vše; Přehled a účtenky = vidí přehled a vystavuje/tiskne účtenky, nemění nastavení ani uživatele.
 * Pojistky: sebe nejde smazat ani si vzít správce; poslední správce zůstane vždy.
 */
declare(strict_types=1);
require __DIR__ . '/inc/bootstrap.php';
$user = require_admin();

$msg = '';
$err = '';

function admin_count(): int
{
    return (int)db()->query("SELECT COUNT(*) FROM users WHERE role = 'admin'")->fetchColumn();
}

function find_user(int $id): ?array
{
    $st = db()->prepare('SELECT * FROM users WHERE id = ?');
    $st->execute([$id]);
    return $st->fetch() ?: null;
}

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    check_csrf();
    $action = post_str('action');
    $target = find_user((int)post_str('id'));

    if ($action === 'add') {
        $name = trim(post_str('username'));
        $role = post_str('role');
        $exists = db()->prepare('SELECT COUNT(*) FROM users WHERE username = ?');
        $exists->execute([$name]);
        if (!valid_username($name)) {
            $err = 'Jméno: 3–40 znaků (písmena, čísla, . _ @ -).';
        } elseif ((int)$exists->fetchColumn() > 0) {
            $err = 'Uživatel s tímhle jménem už existuje.';
        } elseif (!isset(ROLES[$role])) {
            $err = 'Vyber roli.';
        } elseif (($problem = password_problem(post_str('password'), post_str('password2'))) !== null) {
            $err = $problem;
        } else {
            db()->prepare('INSERT INTO users(username, password_hash, created_at, role) VALUES(?, ?, ?, ?)')
                ->execute([$name, password_hash(post_str('password'), PASSWORD_DEFAULT), time(), $role]);
            $msg = 'Uživatel ' . $name . ' přidán. Pošli mu jméno a heslo – heslo si pak může změnit v Nastavení.';
        }
    } elseif ($target === null) {
        $err = 'Uživatel nenalezen.';
    } elseif ($action === 'role') {
        $role = post_str('role');
        if (!isset(ROLES[$role])) {
            $err = 'Neznámá role.';
        } elseif ((int)$target['id'] === (int)$user['id']) {
            $err = 'Svou vlastní roli změnit nejde (ať se omylem nezamkneš).';
        } elseif ($target['role'] === 'admin' && $role !== 'admin' && admin_count() <= 1) {
            $err = 'Musí zůstat aspoň jeden správce.';
        } else {
            db()->prepare('UPDATE users SET role = ? WHERE id = ?')->execute([$role, $target['id']]);
            $msg = 'Role uživatele ' . $target['username'] . ' změněna na „' . ROLES[$role] . '“.';
        }
    } elseif ($action === 'password') {
        if (($problem = password_problem(post_str('password'), post_str('password2'))) !== null) {
            $err = $problem;
        } else {
            // Nové heslo odhlásí všechna jeho zařízení.
            db()->prepare('UPDATE users SET password_hash = ?, password_changed = ? WHERE id = ?')
                ->execute([password_hash(post_str('password'), PASSWORD_DEFAULT), time(), $target['id']]);
            if ((int)$target['id'] === (int)$user['id']) {
                login_user((int)$user['id']);
                $_SESSION['login_at'] = time() + 1;
            }
            $msg = 'Heslo uživatele ' . $target['username'] . ' změněno.';
        }
    } elseif ($action === 'delete') {
        if ((int)$target['id'] === (int)$user['id']) {
            $err = 'Sám sebe smazat nejde.';
        } elseif ($target['role'] === 'admin' && admin_count() <= 1) {
            $err = 'Posledního správce smazat nejde.';
        } else {
            db()->prepare('DELETE FROM users WHERE id = ?')->execute([$target['id']]);
            $msg = 'Uživatel ' . $target['username'] . ' smazán.';
        }
    }
}

$users = db()->query('SELECT id, username, role, created_at, last_login FROM users ORDER BY username COLLATE NOCASE')->fetchAll();
$edit = (int)($_GET['edit'] ?? 0);

page_header('Uživatelé', $user);
?>
<h1>Uživatelé</h1>
<?php if ($msg !== ''): ?><p class="ok"><?= h($msg) ?></p><?php endif; ?>
<?php if ($err !== ''): ?><p class="error"><?= h($err) ?></p><?php endif; ?>

<section class="card">
    <div class="table-wrap"><table class="stack">
        <thead><tr><th>Jméno</th><th>Role</th><th>Naposledy přihlášen</th><th></th></tr></thead>
        <tbody>
        <?php foreach ($users as $u): $self = (int)$u['id'] === (int)$user['id']; ?>
            <tr>
                <td class="head"><?= h($u['username']) ?><?= $self ? ' <span class="badge">ty</span>' : '' ?></td>
                <td data-label="Role"><span class="badge <?= $u['role'] === 'admin' ? 'ok' : '' ?>"><?= h(ROLES[$u['role']] ?? $u['role']) ?></span></td>
                <td data-label="Naposledy přihlášen"><?= $u['last_login'] ? h(fmt_time((int)$u['last_login'])) : 'zatím nikdy' ?></td>
                <td class="r" data-label=""><a href="?edit=<?= (int)$u['id'] ?>#upravit">Upravit ›</a></td>
            </tr>
        <?php endforeach; ?>
        </tbody>
    </table></div>
</section>

<?php foreach ($users as $u): if ((int)$u['id'] !== $edit) continue; $self = (int)$u['id'] === (int)$user['id']; ?>
<section class="card" id="upravit">
    <h2>Upravit: <?= h($u['username']) ?></h2>
    <?php if (!$self): ?>
        <form method="post" class="row">
            <?= csrf_field() ?><input type="hidden" name="action" value="role"><input type="hidden" name="id" value="<?= (int)$u['id'] ?>">
            <label>Role
                <select name="role">
                    <?php foreach (ROLES as $key => $label): ?>
                        <option value="<?= h($key) ?>"<?= $u['role'] === $key ? ' selected' : '' ?>><?= h($label) ?></option>
                    <?php endforeach; ?>
                </select>
            </label>
            <button>Uložit roli</button>
        </form>
    <?php endif; ?>
    <form method="post" class="grid">
        <?= csrf_field() ?><input type="hidden" name="action" value="password"><input type="hidden" name="id" value="<?= (int)$u['id'] ?>">
        <label>Nové heslo (aspoň 10 znaků) <input type="password" name="password" required autocomplete="new-password"></label>
        <label>Nové heslo znovu <input type="password" name="password2" required autocomplete="new-password"></label>
        <button>Nastavit nové heslo</button>
    </form>
    <p class="muted">Nové heslo uživatele odhlásí na všech zařízeních.</p>
    <?php if (!$self): ?>
        <form method="post" class="confirm" data-confirm="Opravdu smazat uživatele <?= h($u['username']) ?>?">
            <?= csrf_field() ?><input type="hidden" name="action" value="delete"><input type="hidden" name="id" value="<?= (int)$u['id'] ?>">
            <button class="danger">Smazat uživatele</button>
        </form>
    <?php endif; ?>
</section>
<?php endforeach; ?>

<section class="card">
    <h2>Přidat uživatele</h2>
    <form method="post" class="grid">
        <?= csrf_field() ?><input type="hidden" name="action" value="add">
        <label>Jméno <input name="username" required autocomplete="off" value="<?= $err !== '' && post_str('action') === 'add' ? h(post_str('username')) : '' ?>"></label>
        <label>Role
            <select name="role">
                <option value="viewer" selected><?= h(ROLES['viewer']) ?></option>
                <option value="admin"><?= h(ROLES['admin']) ?></option>
            </select>
        </label>
        <label>Heslo (aspoň 10 znaků) <input type="password" name="password" required autocomplete="new-password"></label>
        <label>Heslo znovu <input type="password" name="password2" required autocomplete="new-password"></label>
        <button class="primary">Přidat uživatele</button>
    </form>
    <p class="muted"><strong>Přehled a účtenky:</strong> vidí přehled tisků a vystavuje a tiskne účtenky. <strong>Správce:</strong> navíc nastavuje cenu, automaty a uživatele.</p>
</section>
<script src="assets/confirm.js"></script>
<?php page_footer();
