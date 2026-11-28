<?php
/**
 * Galerie (jen správce): fotky zálohované z automatů na Google Disk, po dnech (podsložky RRRR-MM-DD).
 * Obrázky jdou přes server (?thumb= / ?view= / ?download=) – na Disku zůstávají soukromé.
 */
declare(strict_types=1);
require __DIR__ . '/inc/bootstrap.php';
$user = require_admin();
// Obrázky se načítají souběžně – session (zámek souboru) hned uvolnit, jinak by šly jeden po druhém.
if (isset($_GET['thumb']) || isset($_GET['view']) || isset($_GET['download'])) {
    session_write_close();
}

/** Obrázek z Disku do prohlížeče (náhled, zobrazení, nebo stažení). */
function send_drive_image(string $id, ?int $thumb, bool $download): never
{
    @set_time_limit(120);
    try {
        $bytes = preg_match(DRIVE_ID_PATTERN, $id) ? drive_image($id, $thumb) : null;
    } catch (RuntimeException $e) {
        error_log('Fotokiosek: galerie – ' . $e->getMessage());
        $bytes = null;
    }
    if ($bytes === null) {
        http_response_code(404);
        exit;
    }
    $mime = str_starts_with($bytes, "\xFF\xD8\xFF") ? 'image/jpeg' : (str_starts_with($bytes, "\x89PNG") ? 'image/png' : 'image/webp');
    header('Content-Type: ' . $mime);
    header('Content-Length: ' . strlen($bytes));
    header('X-Content-Type-Options: nosniff');
    header('Cache-Control: private, max-age=86400');
    if ($download) {
        $name = preg_replace('/[^A-Za-z0-9._-]/', '_', is_string($_GET['name'] ?? null) ? $_GET['name'] : 'fotka.jpg');
        header('Content-Disposition: attachment; filename="' . (str_ends_with($name, '.jpg') ? $name : 'fotka.jpg') . '"');
    }
    echo $bytes;
    exit;
}

if (isset($_GET['thumb'])) {
    send_drive_image((string)$_GET['thumb'], 400, false);
}
if (isset($_GET['view'])) {
    send_drive_image((string)$_GET['view'], null, false);
}
if (isset($_GET['download'])) {
    send_drive_image((string)$_GET['download'], null, true);
}

$err = '';
$days = [];
$photos = ['files' => [], 'next' => null];
$day = null;
if (drive_connected()) {
    try {
        $days = drive_days();
        $want = (string)($_GET['day'] ?? '');
        foreach ($days as $d) {
            if ($d['name'] === $want) {
                $day = $d;
            }
        }
        $day ??= $days[0] ?? null;
        if ($day !== null) {
            $page = (string)($_GET['page'] ?? '');
            $photos = drive_photos($day['id'], $page !== '' && strlen($page) < 500 ? $page : null);
        }
    } catch (RuntimeException $e) {
        $err = $e->getMessage();
    }
}

page_header('Galerie', $user);
?>
<h1>Galerie</h1>
<?php if ($err !== ''): ?><p class="error"><?= h($err) ?></p><?php endif; ?>

<?php if (!drive_connected()): ?>
<section class="card">
    <p class="muted">Galerie ukazuje fotky zálohované z automatů na Google Disk. Nejdřív Disk připoj v
        <a href="settings.php#drive">Nastavení → Google Disk</a> a v automatu zapni zálohu.</p>
</section>
<?php elseif (!$days && $err === ''): ?>
<section class="card">
    <p class="muted">Na Disku zatím nejsou žádné fotky. Zkontroluj, že má automat v menu správce zapnuté
        <em>Zálohovat fotky na Google Disk</em> (sekce Účetnictví).</p>
</section>
<?php elseif ($day !== null): ?>
<form method="get" class="card filters no-print">
    <label>Den
        <select name="day">
            <?php foreach ($days as $d): ?>
                <option value="<?= h($d['name']) ?>" <?= $d['name'] === $day['name'] ? 'selected' : '' ?>><?= h(fmt_date((int)strtotime($d['name']))) ?></option>
            <?php endforeach; ?>
        </select>
    </label>
    <button class="primary">Zobrazit</button>
    <span class="quick">
        <a href="https://drive.google.com/drive/folders/<?= h(rawurlencode($day['id'])) ?>" target="_blank" rel="noopener">Otevřít tento den na Google Disku</a>
    </span>
</form>

<section class="card">
    <h2><?= h(fmt_date((int)strtotime($day['name']))) ?></h2>
    <?php if (!$photos['files']): ?>
        <p class="muted">V tomhle dni nejsou žádné fotky.</p>
    <?php else: ?>
    <div class="photo-grid">
        <?php foreach ($photos['files'] as $f):
            $id = (string)$f['id'];
            $name = (string)$f['name'];
            $isAi = str_contains($name, '_ai_');
            $time = preg_match('/_(\d{2})-(\d{2})-\d{2}_/', $name, $m) ? $m[1] . ':' . $m[2] : '';
        ?>
        <figure class="photo-card">
            <a href="gallery.php?view=<?= h(rawurlencode($id)) ?>" target="_blank" rel="noopener">
                <img src="gallery.php?thumb=<?= h(rawurlencode($id)) ?>" alt="Fotka <?= h($time) ?>" loading="lazy">
            </a>
            <figcaption>
                <span><?= h($time) ?><?= $isAi ? ' <span class="badge">AI</span>' : '' ?></span>
                <a href="gallery.php?download=<?= h(rawurlencode($id)) ?>&amp;name=<?= h(rawurlencode($name)) ?>">Stáhnout</a>
            </figcaption>
        </figure>
        <?php endforeach; ?>
    </div>
    <?php if ($photos['next']): ?>
        <p><a href="?day=<?= h(rawurlencode($day['name'])) ?>&amp;page=<?= h(rawurlencode((string)$photos['next'])) ?>">Další fotky →</a></p>
    <?php endif; ?>
    <?php endif; ?>
</section>
<?php endif; ?>
<?php page_footer();
