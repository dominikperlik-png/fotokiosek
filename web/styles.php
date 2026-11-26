<?php
/**
 * Knihovna AI stylů (jen správce): název, ikona, prompt a náhled. Automaty si ji stahují (styles-api.php)
 * a v menu správce si každý zapne, které styly nabídne zákazníkům.
 */
declare(strict_types=1);
require __DIR__ . '/inc/bootstrap.php';
$user = require_admin();

// Obrázky pro tuhle stránku (data/ není z webu dostupná).
if (isset($_GET['thumb'])) {
    $file = (string)$_GET['thumb'];
    if (preg_match(THUMB_PATTERN, $file) && is_file(styles_dir() . '/' . $file)) {
        send_image_file(styles_dir() . '/' . $file);
    }
    http_response_code(404);
    exit;
}
if (isset($_GET['ref'])) {
    if (is_file(reference_path())) {
        send_image_file(reference_path());
    }
    http_response_code(404);
    exit;
}

/** Nahraný soubor z pole $field → bajty (max. 10 MB). */
function uploaded_bytes(string $field): string
{
    $f = $_FILES[$field] ?? null;
    if (!is_array($f) || ($f['error'] ?? UPLOAD_ERR_NO_FILE) === UPLOAD_ERR_NO_FILE) {
        throw new RuntimeException('Vyber obrázek.');
    }
    if ($f['error'] === UPLOAD_ERR_INI_SIZE || $f['error'] === UPLOAD_ERR_FORM_SIZE || (int)$f['size'] > 10_000_000) {
        throw new RuntimeException('Obrázek je moc velký (max. 10 MB, případně limit upload_max_filesize na serveru).');
    }
    if ($f['error'] !== UPLOAD_ERR_OK || !is_uploaded_file((string)$f['tmp_name'])) {
        throw new RuntimeException('Nahrání se nepovedlo (chyba ' . (int)$f['error'] . ').');
    }
    return (string)file_get_contents((string)$f['tmp_name']);
}

$msg = '';
$err = '';
$editId = isset($_GET['edit']) ? (string)$_GET['edit'] : null;
$isNew = isset($_GET['new']);

// Nahrání větší než post_max_size: PHP zahodí celý formulář (i CSRF) – říct to srozumitelně.
if ($_SERVER['REQUEST_METHOD'] === 'POST' && empty($_POST) && (int)($_SERVER['CONTENT_LENGTH'] ?? 0) > 0) {
    $err = 'Soubor je na server moc velký (limit post_max_size = ' . ini_get('post_max_size') . '). Zmenši ho, nebo zvyš limit.';
} elseif ($_SERVER['REQUEST_METHOD'] === 'POST') {
    check_csrf();
    $action = post_str('action');
    $id = post_str('id');
    $style = $id !== '' ? find_style($id) : null;
    try {
        if (in_array($action, ['delete', 'move', 'upload_thumb', 'generate_thumb', 'remove_thumb'], true) && $style === null) {
            throw new RuntimeException('Styl nenalezen (mezitím smazaný?).');
        }
        if ($action === 'save') {
            $name = clip(trim(post_str('name')), 40);
            $icon = clip(trim(post_str('icon')), 8);
            $prompt = clip(trim(post_str('prompt')), 2000);
            if ($name === '' || $prompt === '') {
                $editId = $id !== '' ? $id : null;
                $isNew = $id === '';
                throw new RuntimeException('Vyplň název i prompt.');
            }
            if ($style !== null) {
                db()->prepare('UPDATE styles SET name = ?, icon = ?, prompt = ?, updated_at = ? WHERE id = ?')
                    ->execute([$name, $icon, $prompt, time(), $style['id']]);
                $msg = 'Styl „' . $name . '“ uložen. Automaty si změnu stáhnou při další synchronizaci.';
            } else {
                $newId = style_slug($name);
                $sort = (int)db()->query('SELECT COALESCE(MAX(sort), 0) FROM styles')->fetchColumn() + 10;
                db()->prepare('INSERT INTO styles(id, name, icon, prompt, sort, updated_at) VALUES(?, ?, ?, ?, ?, ?)')
                    ->execute([$newId, $name, $icon, $prompt, $sort, time()]);
                $editId = $newId; // rovnou přidat náhled
                $msg = 'Styl „' . $name . '“ přidán. Teď mu nahraj nebo vygeneruj náhled. Na automatech bude zatím vypnutý.';
            }
        } elseif ($action === 'delete') {
            db()->prepare('DELETE FROM styles WHERE id = ?')->execute([$style['id']]);
            if ($style['thumb'] && preg_match(THUMB_PATTERN, $style['thumb'])) {
                @unlink(styles_dir() . '/' . $style['thumb']);
            }
            $msg = 'Styl „' . $style['name'] . '“ smazán. Z automatů zmizí při další synchronizaci.';
        } elseif ($action === 'move') {
            $ids = array_column(all_styles(), 'id');
            $i = array_search($style['id'], $ids, true);
            $j = post_str('dir') === 'up' ? $i - 1 : $i + 1;
            if ($j >= 0 && $j < count($ids)) {
                [$ids[$i], $ids[$j]] = [$ids[$j], $ids[$i]];
                $upd = db()->prepare('UPDATE styles SET sort = ? WHERE id = ?');
                db()->beginTransaction();
                foreach ($ids as $n => $sid) {
                    $upd->execute([($n + 1) * 10, $sid]);
                }
                db()->commit();
            }
        } elseif ($action === 'upload_thumb') {
            $editId = $style['id'];
            set_style_thumb($style['id'], store_thumb($style['id'], uploaded_bytes('image')));
            $msg = 'Náhled nahrán.';
        } elseif ($action === 'generate_thumb') {
            $editId = $style['id'];
            if (!is_file(reference_path())) {
                throw new RuntimeException('Nejdřív nastav referenční fotku (dole na stránce).');
            }
            $image = gemini_image(setting('gemini_key'), setting('gemini_model') ?: GEMINI_DEFAULT_MODEL,
                STYLE_PRESERVE . $style['prompt'], (string)file_get_contents(reference_path()));
            set_style_thumb($style['id'], store_thumb($style['id'], $image));
            $msg = 'Náhled vygenerován.';
        } elseif ($action === 'remove_thumb') {
            $editId = $style['id'];
            set_style_thumb($style['id'], null);
            $msg = 'Náhled odebrán – automat ukáže jen ikonu.';
        } elseif ($action === 'ai_settings') {
            $key = trim(post_str('gemini_key'));
            if ($key !== '') {
                if (!preg_match('/^\S{20,300}$/', $key)) {
                    throw new RuntimeException('Tohle nevypadá jako API klíč.');
                }
                set_setting('gemini_key', $key);
            }
            if (post_str('delete_key') === '1') {
                set_setting('gemini_key', '');
            }
            $model = trim(post_str('gemini_model'));
            set_setting('gemini_model', preg_match('/^[A-Za-z0-9._\/-]{3,80}$/', $model) ? $model : GEMINI_DEFAULT_MODEL);
            $msg = 'Nastavení generování uloženo.';
        } elseif ($action === 'upload_ref') {
            store_reference(uploaded_bytes('image'));
            $msg = 'Referenční fotka nahrána.';
        } elseif ($action === 'generate_ref') {
            store_reference(gemini_image(setting('gemini_key'), setting('gemini_model') ?: GEMINI_DEFAULT_MODEL, REFERENCE_PROMPT, null));
            $msg = 'Vymyšlená postava vygenerována. Když se ti nelíbí, vygeneruj ji znovu.';
        }
    } catch (RuntimeException $e) {
        if (db()->inTransaction()) {
            db()->rollBack();
        }
        $err = $e->getMessage();
    }
}

$styles = all_styles();
$editing = $editId !== null ? find_style($editId) : null;
$hasKey = setting('gemini_key') !== '';
$hasRef = is_file(reference_path());
$model = setting('gemini_model') ?: GEMINI_DEFAULT_MODEL;

function thumb_url(?string $file): ?string
{
    return $file ? 'styles.php?thumb=' . rawurlencode($file) : null;
}

page_header('AI styly', $user);
?>
<h1>AI styly</h1>
<p class="muted">Knihovna stylů pro všechny automaty. Automat si ji stáhne sám (po startu a pak každých 6 hodin, nebo tlačítkem
    <em>Synchronizovat styly</em> v menu správce) a tam si zapneš, které styly zákazník uvidí. Nový styl je na automatech zatím vypnutý.</p>
<?php if ($msg !== ''): ?><p class="ok"><?= h($msg) ?></p><?php endif; ?>
<?php if ($err !== ''): ?><p class="error"><?= h($err) ?></p><?php endif; ?>

<?php if ($editing !== null || $isNew): ?>
<section class="card" id="edit">
    <h2><?= $editing ? 'Upravit styl' : 'Nový styl' ?></h2>
    <form method="post" class="grid">
        <?= csrf_field() ?><input type="hidden" name="action" value="save">
        <input type="hidden" name="id" value="<?= h($editing['id'] ?? '') ?>">
        <label>Název (vidí ho zákazník) <input name="name" maxlength="40" required value="<?= h($editing['name'] ?? post_str('name')) ?>"></label>
        <label>Ikona (emoji, když chybí náhled) <input name="icon" maxlength="8" value="<?= h($editing['icon'] ?? (post_str('icon') ?: '✨')) ?>"></label>
        <label class="wide">Prompt (anglicky – co změnit: styl, oblečení, pozadí)
            <textarea name="prompt" rows="5" maxlength="2000" required><?= h($editing['prompt'] ?? post_str('prompt')) ?></textarea></label>
        <p class="muted wide">Pokyn zachovat obličeje, výrazy, počet lidí a pózu přidá automat ke každému stylu sám.</p>
        <div class="row wide"><button class="primary">Uložit</button><a href="styles.php">Zpět na seznam</a></div>
    </form>

    <?php if ($editing): ?>
    <h2 class="sub">Náhled</h2>
    <div class="style-edit-thumb">
        <?php if ($editing['thumb']): ?>
            <img src="<?= h(thumb_url($editing['thumb'])) ?>" alt="Náhled stylu <?= h($editing['name']) ?>">
        <?php else: ?>
            <div class="style-ph"><?= h($editing['icon'] ?: '✨') ?></div>
        <?php endif; ?>
        <div>
            <form method="post" enctype="multipart/form-data" class="row">
                <?= csrf_field() ?><input type="hidden" name="action" value="upload_thumb"><input type="hidden" name="id" value="<?= h($editing['id']) ?>">
                <label>Nahrát obrázek (JPEG/PNG/WebP, ořízne se na 3:2) <input type="file" name="image" accept="image/jpeg,image/png,image/webp" required></label>
                <button>Nahrát</button>
            </form>
            <form method="post" class="row busy" data-busy="Generuji… (až minutu)">
                <?= csrf_field() ?><input type="hidden" name="action" value="generate_thumb"><input type="hidden" name="id" value="<?= h($editing['id']) ?>">
                <button class="primary" <?= $hasKey && $hasRef ? '' : 'disabled' ?>>✨ Vygenerovat z referenční fotky</button>
                <?php if (!$hasKey || !$hasRef): ?><span class="muted">Nejdřív dole nastav API klíč a referenční fotku.</span><?php endif; ?>
            </form>
            <?php if ($editing['thumb']): ?>
            <form method="post" class="row">
                <?= csrf_field() ?><input type="hidden" name="action" value="remove_thumb"><input type="hidden" name="id" value="<?= h($editing['id']) ?>">
                <button class="link danger">Odebrat náhled</button>
            </form>
            <?php endif; ?>
        </div>
    </div>
    <?php endif; ?>
</section>
<?php endif; ?>

<section class="card">
    <div class="receipt-cta"><h2>Styly (<?= count($styles) ?>)</h2><a href="styles.php?new=1#edit">+ Přidat styl</a></div>
    <div class="style-grid">
        <?php foreach ($styles as $n => $s): ?>
        <div class="style-card">
            <a href="styles.php?edit=<?= h(rawurlencode($s['id'])) ?>#edit" class="style-img">
                <?php if ($s['thumb']): ?>
                    <img src="<?= h(thumb_url($s['thumb'])) ?>" alt="" loading="lazy">
                <?php else: ?>
                    <div class="style-ph"><?= h($s['icon'] ?: '✨') ?></div>
                <?php endif; ?>
            </a>
            <div class="style-name"><?= h($s['name']) ?></div>
            <div class="style-actions">
                <a href="styles.php?edit=<?= h(rawurlencode($s['id'])) ?>#edit">Upravit</a>
                <form method="post" class="inline"><?= csrf_field() ?><input type="hidden" name="action" value="move"><input type="hidden" name="id" value="<?= h($s['id']) ?>"><input type="hidden" name="dir" value="up">
                    <button class="link" title="Posunout dopředu" <?= $n === 0 ? 'disabled' : '' ?>>↑</button></form>
                <form method="post" class="inline"><?= csrf_field() ?><input type="hidden" name="action" value="move"><input type="hidden" name="id" value="<?= h($s['id']) ?>"><input type="hidden" name="dir" value="down">
                    <button class="link" title="Posunout dozadu" <?= $n === count($styles) - 1 ? 'disabled' : '' ?>>↓</button></form>
                <form method="post" class="inline confirm" data-confirm="Opravdu smazat styl <?= h($s['name']) ?>? Zmizí ze všech automatů.">
                    <?= csrf_field() ?><input type="hidden" name="action" value="delete"><input type="hidden" name="id" value="<?= h($s['id']) ?>">
                    <button class="link danger">Smazat</button></form>
            </div>
        </div>
        <?php endforeach; ?>
    </div>
    <?php if (!$styles): ?><p class="muted">Zatím žádné styly – přidej první.</p><?php endif; ?>
</section>

<section class="card">
    <h2>Generování náhledů</h2>
    <p class="muted">Náhledy se vygenerují z jedné referenční fotky – na všech je tak stejná postava a styly jdou snadno porovnat.
        Nepoužívej fotky zákazníků: vygeneruj vymyšlenou postavu, nebo nahraj fotku někoho, kdo s tím souhlasí.
        Každé vygenerování se u Google platí (asi 0,05–0,13 $).</p>
    <div class="style-edit-thumb">
        <?php if ($hasRef): ?>
            <img src="styles.php?ref=1&amp;v=<?= (int)filemtime(reference_path()) ?>" alt="Referenční fotka">
        <?php else: ?>
            <div class="style-ph">👤</div>
        <?php endif; ?>
        <div>
            <form method="post" class="row busy" data-busy="Generuji… (až minutu)">
                <?= csrf_field() ?><input type="hidden" name="action" value="generate_ref">
                <button <?= $hasKey ? '' : 'disabled' ?>>👤 Vygenerovat vymyšlenou postavu</button>
            </form>
            <form method="post" enctype="multipart/form-data" class="row">
                <?= csrf_field() ?><input type="hidden" name="action" value="upload_ref">
                <label>Nebo nahrát referenční fotku <input type="file" name="image" accept="image/jpeg,image/png,image/webp" required></label>
                <button>Nahrát</button>
            </form>
        </div>
    </div>
    <form method="post" class="grid">
        <?= csrf_field() ?><input type="hidden" name="action" value="ai_settings">
        <label>Gemini API klíč <?= $hasKey ? '(uložený – nový ho nahradí)' : '' ?>
            <input type="password" name="gemini_key" autocomplete="off" spellcheck="false" placeholder="<?= $hasKey ? '••••••••' . h(substr(setting('gemini_key'), -4)) : 'AIza…' ?>"></label>
        <label>Model
            <input name="gemini_model" list="gemini-models" value="<?= h($model) ?>">
            <datalist id="gemini-models"><?php foreach (GEMINI_MODELS as $m): ?><option value="<?= h($m) ?>"><?php endforeach; ?></datalist></label>
        <?php if ($hasKey): ?><label class="wide check"><input type="checkbox" name="delete_key" value="1"> Smazat uložený klíč</label><?php endif; ?>
        <button>Uložit</button>
    </form>
    <p class="muted">Klíč se ukládá jen na tento server (databáze ve složce data/) a používá se jen tady pro náhledy. Automat má svůj klíč v menu správce.</p>
</section>
<script src="assets/confirm.js"></script>
<script src="assets/busy.js"></script>
<?php page_footer();
