<?php
/** Účtenky: POST = vystavit novou za období (z přehledu), GET = seznam vystavených. */
declare(strict_types=1);
require __DIR__ . '/inc/bootstrap.php';
$user = require_login();

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    check_csrf();
    $from = parse_day($_POST['from'] ?? null, strtotime(date('Y-m-d')));
    $to = parse_day($_POST['to'] ?? null, $from);
    if ($to < $from) {
        [$from, $to] = [$to, $from];
    }
    $toEnd = strtotime('+1 day', $to);

    // Stejný formulář podruhé (dvojklik, obnovení stránky) → už vystavená účtenka, ne nová.
    $nonce = post_str('nonce');
    if ($nonce !== '' && isset($_SESSION['receipt_nonce'][$nonce])) {
        header('Location: receipt.php?id=' . (int)$_SESSION['receipt_nonce'][$nonce]);
        exit;
    }

    $st = db()->prepare("SELECT happened_at, copies, price FROM events WHERE type = 'print' AND happened_at >= ? AND happened_at < ?");
    $st->execute([$from, $toEnd]);
    $days = [];
    $lines = []; // cena → počet fotek
    $copies = 0;
    $total = 0.0;
    $now = current_price();
    foreach ($st->fetchAll() as $e) {
        $day = date('Y-m-d', (int)$e['happened_at']);
        $p = $e['price'] === null ? $now : (float)$e['price'];
        $days[$day] = ($days[$day] ?? 0) + (int)$e['copies'];
        $lines[(string)$p] = ($lines[(string)$p] ?? 0) + (int)$e['copies'];
        $copies += (int)$e['copies'];
        $total += (int)$e['copies'] * $p;
    }
    if ($copies === 0) {
        header('Location: index.php?from=' . date('Y-m-d', $from) . '&to=' . date('Y-m-d', $to));
        exit;
    }
    ksort($days);
    $total = round($total, 2);
    $price = round($total / $copies, 2); // průměrná cena (pro seznam účtenek)
    $snapshot = [
        'seller_name' => setting('seller_name'),
        'seller_id' => setting('seller_id'),
        'seller_vat' => setting('seller_vat'),
        'seller_address' => setting('seller_address'),
        'currency' => setting('currency'),
        'note' => setting('receipt_note'),
        'days' => $days,
        'lines' => array_map(static fn($p, $c) => ['price' => (float)$p, 'copies' => $c], array_keys($lines), array_values($lines)),
        'issued_by' => $user['username'],
    ];

    $pdo = db();
    // IMMEDIATE = zámek hned, dvě souběžná vystavení nedostanou stejné číslo.
    $pdo->exec('BEGIN IMMEDIATE');
    try {
        // Číslo účtenky: rok-pořadí (2026-0001).
        $year = date('Y');
        $st = $pdo->prepare('SELECT MAX(CAST(substr(number, 6) AS INTEGER)) FROM receipts WHERE number LIKE ?');
        $st->execute([$year . '-%']);
        $number = sprintf('%s-%04d', $year, (int)$st->fetchColumn() + 1);
        $pdo->prepare(
            'INSERT INTO receipts(number, created_at, period_from, period_to, copies, unit_price, total, snapshot)
             VALUES(?, ?, ?, ?, ?, ?, ?, ?)'
        )->execute([$number, time(), $from, $to, $copies, $price, $total, json_encode($snapshot, JSON_UNESCAPED_UNICODE)]);
        $id = (int)$pdo->lastInsertId();
        $pdo->exec('COMMIT');
    } catch (Throwable $e) {
        $pdo->exec('ROLLBACK');
        throw $e;
    }
    if ($nonce !== '') {
        $_SESSION['receipt_nonce'][$nonce] = $id;
    }
    header('Location: receipt.php?id=' . $id . '&print=1');
    exit;
}

$receipts = db()->query('SELECT * FROM receipts ORDER BY id DESC LIMIT 200')->fetchAll();
page_header('Účtenky', $user);
?>
<h1>Vystavené účtenky</h1>
<p class="muted no-print">Novou účtenku vystavíš na <a href="index.php">Přehledu</a> – vyber období a klepni na „Vystavit a vytisknout účtenku“.</p>
<section class="card">
    <?php if (!$receipts): ?>
        <p class="muted">Zatím žádné.</p>
    <?php else: ?>
        <table>
            <thead><tr><th>Číslo</th><th>Vystaveno</th><th>Období</th><th class="r">Fotek</th><th class="r">Celkem</th><th></th></tr></thead>
            <tbody>
            <?php foreach ($receipts as $r): $snap = json_decode($r['snapshot'], true) ?: []; ?>
                <tr>
                    <td><?= h($r['number']) ?></td>
                    <td><?= h(fmt_time((int)$r['created_at'])) ?></td>
                    <td><?= h(fmt_date((int)$r['period_from'])) ?> – <?= h(fmt_date((int)$r['period_to'])) ?></td>
                    <td class="r"><?= (int)$r['copies'] ?></td>
                    <td class="r"><?= h(number_format((float)$r['total'], 2, ',', ' ') . ' ' . ($snap['currency'] ?? '')) ?></td>
                    <td><a href="receipt.php?id=<?= (int)$r['id'] ?>">Zobrazit / tisk</a></td>
                </tr>
            <?php endforeach; ?>
            </tbody>
        </table>
    <?php endif; ?>
</section>
<?php page_footer();
