<?php
/** Jedna účtenka k tisku (úzký formát 80 mm, jde vytisknout i na A4). Údaje se berou z doby vystavení. */
declare(strict_types=1);
require __DIR__ . '/inc/bootstrap.php';
$user = require_login();

$st = db()->prepare('SELECT * FROM receipts WHERE id = ?');
$st->execute([(int)($_GET['id'] ?? 0)]);
$r = $st->fetch();
if (!$r) {
    http_response_code(404);
    page_header('Účtenka nenalezena', $user);
    echo '<p>Účtenka neexistuje. <a href="receipts.php">Zpět</a></p>';
    page_footer();
    exit;
}
$snap = json_decode($r['snapshot'], true) ?: [];
$cur = (string)($snap['currency'] ?? '');
$m = static fn(float $v): string => number_format($v, 2, ',', ' ') . ' ' . $cur;

page_header('Účtenka ' . $r['number'], $user);
?>
<div class="no-print actions">
    <a href="receipts.php">‹ Účtenky</a>
    <button class="primary" id="print-btn">Vytisknout</button>
</div>
<article class="receipt"<?= isset($_GET['print']) ? ' data-autoprint="1"' : '' ?>>
    <header>
        <?php if (($snap['seller_name'] ?? '') !== ''): ?><strong><?= h($snap['seller_name']) ?></strong><br><?php endif; ?>
        <?php if (($snap['seller_address'] ?? '') !== ''): ?><?= nl2br(h($snap['seller_address'])) ?><br><?php endif; ?>
        <?php if (($snap['seller_id'] ?? '') !== ''): ?>IČO: <?= h($snap['seller_id']) ?><br><?php endif; ?>
        <?php if (($snap['seller_vat'] ?? '') !== ''): ?>DIČ: <?= h($snap['seller_vat']) ?><br><?php endif; ?>
    </header>
    <h2>Účtenka č. <?= h($r['number']) ?></h2>
    <p>Vystaveno: <?= h(fmt_time((int)$r['created_at'])) ?><br>
       Období: <?= h(fmt_period((int)$r['period_from'], (int)$r['period_to'])) ?></p>
    <table>
        <thead><tr><th>Den</th><th class="r">Fotek</th></tr></thead>
        <tbody>
        <?php foreach (($snap['days'] ?? []) as $day => $count): ?>
            <tr><td><?= h(fmt_date(strtotime((string)$day))) ?></td><td class="r"><?= (int)$count ?></td></tr>
        <?php endforeach; ?>
        </tbody>
    </table>
    <hr>
    <p class="line"><span>Tisk fotografie 10×15</span></p>
    <?php foreach (($snap['lines'] ?? [['price' => (float)$r['unit_price'], 'copies' => (int)$r['copies']]]) as $line): ?>
        <p class="line"><span><?= (int)$line['copies'] ?> × <?= h($m((float)$line['price'])) ?></span><span><?= h($m((int)$line['copies'] * (float)$line['price'])) ?></span></p>
    <?php endforeach; ?>
    <hr>
    <p class="line total"><span>CELKEM</span><span><?= h($m((float)$r['total'])) ?></span></p>
    <?php if (($snap['note'] ?? '') !== ''): ?><p class="note"><?= nl2br(h($snap['note'])) ?></p><?php endif; ?>
</article>
<script src="assets/print.js"></script>
<?php page_footer();
