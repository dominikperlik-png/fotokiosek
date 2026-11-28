<?php
/** Přehled: fotky, tisky, AI úpravy a e-maily v období, souhrn po dnech, seznam záznamů, vystavení účtenky. */
declare(strict_types=1);
require __DIR__ . '/inc/bootstrap.php';
$user = require_login();

$range = parse_range($_GET, strtotime(date('Y-m-01')), strtotime(date('Y-m-d')));
$from = $range['from'];
$toEnd = $range['end'];

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
$shots = 0;
$mails = 0;
$revenue = 0.0;
$prices = [];
foreach ($events as $e) {
    $day = date('Y-m-d', (int)$e['happened_at']);
    $days[$day] ??= ['photo' => 0, 'copies' => 0, 'jobs' => 0, 'ai' => 0, 'email' => 0, 'revenue' => 0.0];
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
    } elseif ($e['type'] === 'photo') {
        $days[$day]['photo']++;
        $shots++;
    } elseif ($e['type'] === 'email') {
        $days[$day]['email']++;
        $mails++;
    }
}
krsort($days);

$sourceLabel = ['foto' => 'nová fotka', 'galerie' => 'z galerie', 'ai' => 'AI'];
$typeLabel = ['print' => 'tisk', 'ai' => 'AI úprava', 'photo' => 'vyfoceno', 'email' => 'e-mail'];

page_header('Přehled', $user);
?>
<h1>Přehled</h1>

<form method="get" class="card filters no-print">
    <label>Od
        <span class="dt">
            <input type="date" name="from" value="<?= h($range['from_day']) ?>" aria-label="Datum od">
            <input type="time" name="from_time" value="<?= h($range['from_time']) ?>" aria-label="Čas od">
        </span>
    </label>
    <label>Do
        <span class="dt">
            <input type="date" name="to" value="<?= h($range['to_day']) ?>" aria-label="Datum do">
            <input type="time" name="to_time" value="<?= h($range['to_time']) ?>" aria-label="Čas do">
        </span>
    </label>
    <button class="primary">Zobrazit</button>
    <span class="quick">
        <a href="?from=<?= h(date('Y-m-d')) ?>&amp;to=<?= h(date('Y-m-d')) ?>">Dnes</a>
        <a href="?from=<?= h(date('Y-m-d', strtotime('-1 day'))) ?>&amp;to=<?= h(date('Y-m-d', strtotime('-1 day'))) ?>">Včera</a>
        <a href="?from=<?= h(date('Y-m-01')) ?>&amp;to=<?= h(date('Y-m-d')) ?>">Tento měsíc</a>
        <a href="?from=<?= h(date('Y-m-01', strtotime('first day of last month'))) ?>&amp;to=<?= h(date('Y-m-t', strtotime('first day of last month'))) ?>">Minulý měsíc</a>
    </span>
</form>

<section class="stats">
    <div class="stat"><span class="num"><?= $shots ?></span><span>vyfocených fotek</span></div>
    <div class="stat"><span class="num"><?= $copies ?></span><span>vytištěných fotek</span></div>
    <div class="stat"><span class="num"><?= $jobs ?></span><span>tiskových úloh</span></div>
    <div class="stat"><span class="num"><?= $ai ?></span><span>AI úprav</span></div>
    <div class="stat"><span class="num"><?= $mails ?></span><span>fotek k odeslání e-mailem</span></div>
    <div class="stat main"><span class="num"><?= h(money($revenue)) ?></span><span>tržba (<?= count($prices) > 1 ? 'víc cen' : h(money($prices ? (float)array_key_first($prices) : $price)) . ' / fotka' ?>)</span></div>
</section>

<section class="card no-print">
    <h2>Účtenka za <?= h(fmt_period($range['from'], $range['last'])) ?></h2>
    <?php if ($copies > 0): ?>
        <form method="post" action="receipts.php" class="receipt-cta">
            <?= csrf_field() ?>
            <input type="hidden" name="from" value="<?= h($range['from_day']) ?>">
            <input type="hidden" name="from_time" value="<?= h($range['from_time']) ?>">
            <input type="hidden" name="to" value="<?= h($range['to_day']) ?>">
            <input type="hidden" name="to_time" value="<?= h($range['to_time']) ?>">
            <input type="hidden" name="nonce" value="<?= h(bin2hex(random_bytes(16))) ?>">
            <p><span class="muted"><?= $copies ?> fotek</span><br><span class="sum"><?= h(money($revenue)) ?></span></p>
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
        <div class="table-wrap"><table class="stack">
            <thead><tr><th>Den</th><th class="r">Vyfoceno</th><th class="r">Vytištěno</th><th class="r">Úloh</th><th class="r">AI úprav</th><th class="r">E-mailů</th><th class="r">Tržba</th></tr></thead>
            <tbody>
            <?php foreach ($days as $day => $d): ?>
                <tr>
                    <td class="head"><a href="?from=<?= h($day) ?>&amp;to=<?= h($day) ?>"><?= h(fmt_date(strtotime($day))) ?></a></td>
                    <td class="r" data-label="Vyfoceno"><?= $d['photo'] ?></td>
                    <td class="r" data-label="Vytištěno"><?= $d['copies'] ?></td>
                    <td class="r" data-label="Tiskových úloh"><?= $d['jobs'] ?></td>
                    <td class="r" data-label="AI úprav"><?= $d['ai'] ?></td>
                    <td class="r" data-label="E-mailů"><?= $d['email'] ?></td>
                    <td class="r" data-label="Tržba"><?= h(money($d['revenue'])) ?></td>
                </tr>
            <?php endforeach; ?>
            </tbody>
        </table></div>
    <?php endif; ?>
</section>

<?php if ($events): ?>
<section class="card">
    <h2>Všechny záznamy (<?= count($events) ?>)</h2>
    <div class="table-wrap"><table class="stack">
        <thead><tr><th>Datum a čas</th><th>Automat</th><th>Co</th><th class="r">Kopií</th><th>Odkud</th><th>AI styl</th></tr></thead>
        <tbody>
        <?php foreach (array_slice($events, 0, 500) as $e): ?>
            <tr>
                <td class="head"><?= h(fmt_time((int)$e['happened_at'])) ?></td>
                <td data-label="Automat"><?= h($e['device_label'] !== '' ? $e['device_label'] : $e['device_name']) ?></td>
                <td data-label="Co"><span class="badge"><?= h($typeLabel[$e['type']] ?? $e['type']) ?></span></td>
                <td class="r" data-label="Kopií"><?= $e['type'] === 'print' ? (int)$e['copies'] : '' ?></td>
                <td data-label="Odkud"><?= h($sourceLabel[$e['source']] ?? $e['source']) ?></td>
                <td data-label="AI styl"><?= h($e['style']) ?></td>
            </tr>
        <?php endforeach; ?>
        </tbody>
    </table></div>
    <?php if (count($events) > 500): ?><p class="muted">Zobrazeno prvních 500 – zkrať období.</p><?php endif; ?>
</section>
<?php endif; ?>
<?php page_footer();
