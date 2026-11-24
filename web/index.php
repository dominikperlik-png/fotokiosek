<?php
/** Přehled: tisky a AI úpravy v období, souhrn po dnech, seznam záznamů, vystavení účtenky. */
declare(strict_types=1);
require __DIR__ . '/inc/bootstrap.php';
$user = require_login();

$from = parse_day($_GET['from'] ?? null, strtotime(date('Y-m-01')));
$to = parse_day($_GET['to'] ?? null, strtotime(date('Y-m-d')));
if ($to < $from) {
    [$from, $to] = [$to, $from];
}
$toEnd = strtotime('+1 day', $to); // konec dne „do“

$st = db()->prepare(
    'SELECT e.*, d.name AS device_name FROM events e JOIN devices d ON d.id = e.device_id
     WHERE e.happened_at >= ? AND e.happened_at < ? ORDER BY e.happened_at DESC'
);
$st->execute([$from, $toEnd]);
$events = $st->fetchAll();

$price = current_price();
$days = [];
$copies = 0;
$jobs = 0;
$ai = 0;
$revenue = 0.0;
$prices = [];
foreach ($events as $e) {
    $day = date('Y-m-d', (int)$e['happened_at']);
    $days[$day] ??= ['copies' => 0, 'jobs' => 0, 'ai' => 0, 'revenue' => 0.0];
    if ($e['type'] === 'print') {
        $p = $e['price'] === null ? $price : (float)$e['price'];
        $prices[(string)$p] = true;
        $days[$day]['copies'] += (int)$e['copies'];
        $days[$day]['jobs']++;
        $days[$day]['revenue'] += (int)$e['copies'] * $p;
        $copies += (int)$e['copies'];
        $revenue += (int)$e['copies'] * $p;
        $jobs++;
    } elseif ($e['type'] === 'ai') {
        $days[$day]['ai']++;
        $ai++;
    }
}
krsort($days);

$sourceLabel = ['foto' => 'nová fotka', 'galerie' => 'z galerie', 'ai' => 'AI'];

page_header('Přehled', $user);
?>
<h1>Přehled</h1>

<form method="get" class="card filters no-print">
    <label>Od <input type="date" name="from" value="<?= h(date('Y-m-d', $from)) ?>"></label>
    <label>Do <input type="date" name="to" value="<?= h(date('Y-m-d', $to)) ?>"></label>
    <button>Zobrazit</button>
    <span class="quick">
        <a href="?from=<?= h(date('Y-m-d')) ?>&amp;to=<?= h(date('Y-m-d')) ?>">Dnes</a>
        <a href="?from=<?= h(date('Y-m-d', strtotime('-1 day'))) ?>&amp;to=<?= h(date('Y-m-d', strtotime('-1 day'))) ?>">Včera</a>
        <a href="?from=<?= h(date('Y-m-01')) ?>&amp;to=<?= h(date('Y-m-d')) ?>">Tento měsíc</a>
        <a href="?from=<?= h(date('Y-m-01', strtotime('first day of last month'))) ?>&amp;to=<?= h(date('Y-m-t', strtotime('first day of last month'))) ?>">Minulý měsíc</a>
    </span>
</form>

<section class="stats">
    <div class="stat"><span class="num"><?= $copies ?></span><span>vytištěných fotek</span></div>
    <div class="stat"><span class="num"><?= $jobs ?></span><span>tiskových úloh</span></div>
    <div class="stat"><span class="num"><?= $ai ?></span><span>AI úprav</span></div>
    <div class="stat"><span class="num"><?= h(money($revenue)) ?></span><span>tržba (<?= count($prices) > 1 ? 'víc cen' : h(money($prices ? (float)array_key_first($prices) : $price)) . ' / fotka' ?>)</span></div>
</section>

<section class="card no-print">
    <h2>Účtenka za období <?= h(fmt_date($from)) ?> – <?= h(fmt_date($to)) ?></h2>
    <?php if ($copies > 0): ?>
        <form method="post" action="receipts.php">
            <?= csrf_field() ?>
            <input type="hidden" name="from" value="<?= h(date('Y-m-d', $from)) ?>">
            <input type="hidden" name="to" value="<?= h(date('Y-m-d', $to)) ?>">
            <input type="hidden" name="nonce" value="<?= h(bin2hex(random_bytes(16))) ?>">
            <p><?= $copies ?> fotek, celkem <strong><?= h(money($revenue)) ?></strong></p>
            <button class="primary">Vystavit a vytisknout účtenku</button>
        </form>
    <?php else: ?>
        <p class="muted">V tomhle období se nic netisklo.</p>
    <?php endif; ?>
</section>

<section class="card">
    <h2>Po dnech</h2>
    <?php if (!$days): ?>
        <p class="muted">Žádné záznamy. Zkontroluj, že má automat v menu správce vyplněné „Účetnictví (web)“.</p>
    <?php else: ?>
        <table>
            <thead><tr><th>Den</th><th class="r">Fotek</th><th class="r">Úloh</th><th class="r">AI úprav</th><th class="r">Tržba</th></tr></thead>
            <tbody>
            <?php foreach ($days as $day => $d): ?>
                <tr>
                    <td><a href="?from=<?= h($day) ?>&amp;to=<?= h($day) ?>"><?= h(fmt_date(strtotime($day))) ?></a></td>
                    <td class="r"><?= $d['copies'] ?></td>
                    <td class="r"><?= $d['jobs'] ?></td>
                    <td class="r"><?= $d['ai'] ?></td>
                    <td class="r"><?= h(money($d['revenue'])) ?></td>
                </tr>
            <?php endforeach; ?>
            </tbody>
        </table>
    <?php endif; ?>
</section>

<?php if ($events): ?>
<section class="card">
    <h2>Všechny záznamy (<?= count($events) ?>)</h2>
    <table>
        <thead><tr><th>Datum a čas</th><th>Automat</th><th>Co</th><th class="r">Kopií</th><th>Odkud</th><th>AI styl</th></tr></thead>
        <tbody>
        <?php foreach (array_slice($events, 0, 500) as $e): ?>
            <tr>
                <td><?= h(fmt_time((int)$e['happened_at'])) ?></td>
                <td><?= h($e['device_label'] !== '' ? $e['device_label'] : $e['device_name']) ?></td>
                <td><?= $e['type'] === 'print' ? 'tisk' : 'AI úprava' ?></td>
                <td class="r"><?= $e['type'] === 'print' ? (int)$e['copies'] : '' ?></td>
                <td><?= h($sourceLabel[$e['source']] ?? $e['source']) ?></td>
                <td><?= h($e['style']) ?></td>
            </tr>
        <?php endforeach; ?>
        </tbody>
    </table>
    <?php if (count($events) > 500): ?><p class="muted">Zobrazeno prvních 500 – zkrať období.</p><?php endif; ?>
</section>
<?php endif; ?>
<?php page_footer();
