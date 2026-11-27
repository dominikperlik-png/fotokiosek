<?php
/**
 * Knihovna AI stylů: výchozí styly, ukládání náhledů a volání Gemini (náhledy generované na webu).
 * Automaty si seznam stahují přes styles-api.php (StyleSync.kt).
 */
declare(strict_types=1);

/** Složka s náhledy a referenční fotkou (uvnitř chráněné DATA_DIR). */
function styles_dir(): string
{
    $dir = DATA_DIR . '/styles';
    if (!is_dir($dir)) {
        @mkdir($dir, 0770, true);
    }
    return $dir;
}

/** Název souboru náhledu: <id>-<8 hex>.<jpg|png|webp> (hash v názvu = automat stahuje jen změněné). */
const THUMB_PATTERN = '/^[a-z0-9-]{1,40}-[0-9a-f]{8}\.(jpg|png|webp)$/D';
const THUMB_W = 768;
const THUMB_H = 512;
/** Max. délka promptu stylu ve znacích – stejný limit je v automatu (StyleSync.PROMPT_MAX). */
const PROMPT_MAX = 8000;

/** Stejná pevná část promptu jako v automatu (AiStyles.PRESERVE) – náhled vznikne stejně jako fotka zákazníka. */
const STYLE_PRESERVE =
    'Edit the attached photo. Strictly preserve every person exactly as photographed: their faces, identity, '
    . 'facial features, facial expressions, age, skin tone, body proportions and gaze must stay the same, so '
    . 'that each person remains clearly recognizable. Keep exactly the same number of people, the same body '
    . 'poses and positions, and the same composition and framing. Do not add, remove, merge or duplicate people. '
    . 'Do not add any text, letters, logos or watermarks. Change only the artistic style, the clothing and the '
    . 'background, as follows: ';

const REFERENCE_PROMPT =
    'A photorealistic photo of one friendly fictional adult person (not a real or famous person), from the chest up, '
    . 'standing in a bright photo booth with a plain light grey background, natural soft light, looking at the camera '
    . 'with a warm smile. No text, no logos.';

const GEMINI_DEFAULT_MODEL = 'gemini-3.1-flash-image';
const GEMINI_MODELS = ['gemini-3.1-flash-image', 'gemini-nano-banana-2.1', 'gemini-3.1-flash-lite-image', 'gemini-3-pro-image', 'gemini-2.5-flash-image'];

/** Výchozí styly (stejné jako přibalené v automatu) – vloží se jednou při prvním spuštění. */
function default_styles(): array
{
    return [
        ['comic', 'Komiks', '💥', 'Turn the photo into a classic comic book illustration with bold black ink outlines, halftone dot shading and vivid flat colours. Replace the background with a dynamic comic panel background with speed lines.'],
        ['animated3d', '3D animák (Pixar)', '🎬', 'Re-render the photo in the style of a modern 3D animated family movie: soft cinematic lighting, smooth stylized 3D characters that stay faithful likenesses of the real people, colourful playful clothing and a bright, cheerful animated-movie set as the background.'],
        ['cyberpunk', 'Cyberpunk', '🌃', 'Cyberpunk style: futuristic techwear clothing with glowing neon accents, a rainy neon-lit futuristic city street at night with holographic signs as the background, cinematic teal and magenta lighting.'],
        ['retro90', '90s Retro', '📼', 'A 1990s retro photo: colourful 90s fashion such as windbreakers, denim jackets and bold patterns, slightly faded colours with vintage film grain, and a classic 90s laser-pattern studio backdrop.'],
        ['superhero', 'Superhrdina', '⚡', 'Epic superhero movie poster: dress everyone in original superhero suits with capes and armour details (no existing characters, symbols or logos), a city skyline at sunset as the background, dramatic cinematic lighting. No title text.'],
        ['fantasy', 'Fantasy / Středověk', '🏰', 'Medieval fantasy: knight armour, royal gowns or wizard robes, a grand castle hall or an enchanted forest as the background, painterly epic fantasy lighting.'],
        ['gamecover', 'Herní plakát (GTA styl)', '🚓', 'Stylized illustrated action video game cover art: bold outlines, saturated colours, cel-shaded painted look, cool streetwear clothing, a sunny palm-lined city with sports cars as the background. Do not include any real game titles, logos or text.'],
        ['astronaut', 'Astronaut', '🚀', 'Space explorers: realistic white astronaut spacesuits with the helmets off so the faces stay fully visible, inside a space station with a large window showing planet Earth and stars as the background.'],
        ['oilpainting', 'Olejomalba', '🖼️', 'A classical oil painting on canvas in the manner of an old master portrait: visible brush strokes, rich warm colours, elegant historical clothing and a dark painted studio background.'],
        ['anime', 'Anime', '🌸', 'Japanese anime illustration: clean line art, cel shading and bright colours, with faces drawn as recognizable likenesses of the real people, and a scenic anime background with cherry blossoms.'],
    ];
}

function seed_styles(PDO $pdo): void
{
    $ins = $pdo->prepare('INSERT OR IGNORE INTO styles(id, name, icon, prompt, sort, updated_at) VALUES(?, ?, ?, ?, ?, ?)');
    foreach (default_styles() as $i => [$id, $name, $icon, $prompt]) {
        $ins->execute([$id, $name, $icon, $prompt, ($i + 1) * 10, time()]);
    }
}

/** Název → id stylu („Fantasy / Středověk“ → „fantasy-stredovek“), jedinečné. */
function style_slug(string $name): string
{
    $map = ['á' => 'a', 'č' => 'c', 'ď' => 'd', 'é' => 'e', 'ě' => 'e', 'í' => 'i', 'ň' => 'n', 'ó' => 'o', 'ř' => 'r', 'š' => 's', 'ť' => 't', 'ú' => 'u', 'ů' => 'u', 'ý' => 'y', 'ž' => 'z', 'ä' => 'a', 'ö' => 'o', 'ü' => 'u', 'ß' => 'ss'];
    $s = function_exists('mb_strtolower') ? mb_strtolower($name, 'UTF-8') : strtolower($name);
    $s = strtr($s, $map);
    $s = trim((string)preg_replace('/[^a-z0-9]+/', '-', $s), '-');
    $s = substr($s, 0, 32) ?: 'styl';
    $s = rtrim($s, '-');
    $id = $s;
    $st = db()->prepare('SELECT 1 FROM styles WHERE id = ?');
    for ($n = 2; ; $n++) {
        $st->execute([$id]);
        if ($st->fetchColumn() === false) {
            return $id;
        }
        $id = $s . '-' . $n;
    }
}

function all_styles(): array
{
    return db()->query('SELECT * FROM styles ORDER BY sort, name')->fetchAll();
}

function find_style(string $id): ?array
{
    $st = db()->prepare('SELECT * FROM styles WHERE id = ?');
    $st->execute([$id]);
    return $st->fetch() ?: null;
}

// ---------------------------------------------------------------- obrázky

/** Otočení fotky z mobilu podle EXIF (GD ho samo nečte). */
function apply_exif_orientation(GdImage $img, string $bytes): GdImage
{
    if (!function_exists('exif_read_data')) {
        return $img;
    }
    $exif = @exif_read_data('data://image/jpeg;base64,' . base64_encode($bytes));
    $angle = match ((int)($exif['Orientation'] ?? 1)) {
        3 => 180,
        6 => -90,
        8 => 90,
        default => 0,
    };
    if ($angle === 0) {
        return $img;
    }
    $rotated = imagerotate($img, $angle, 0);
    return $rotated ?: $img;
}

/** Ověří obrázek (JPEG/PNG/WebP); vrací [typ, šířka, výška] nebo hodí chybu. */
function image_info(string $bytes): array
{
    $info = @getimagesizefromstring($bytes);
    if (!$info || !in_array($info[2], [IMAGETYPE_JPEG, IMAGETYPE_PNG, IMAGETYPE_WEBP], true)) {
        throw new RuntimeException('Soubor není obrázek JPEG, PNG ani WebP.');
    }
    // Obří rozměry by při dekódování v GD přetekly memory_limit (fatální chyba, prázdná stránka).
    if ($info[0] * $info[1] > 40_000_000) {
        throw new RuntimeException('Obrázek má moc velké rozměry (max. 40 Mpx) – zmenši ho.');
    }
    return $info;
}

function gd_available(): bool
{
    return function_exists('imagecreatefromstring') && function_exists('imagejpeg');
}

/** Načte obrázek do GD (s otočením podle EXIF). */
function gd_load(string $bytes): GdImage
{
    $info = image_info($bytes);
    $img = @imagecreatefromstring($bytes);
    if (!$img) {
        throw new RuntimeException('Obrázek nejde přečíst.');
    }
    return $info[2] === IMAGETYPE_JPEG ? apply_exif_orientation($img, $bytes) : $img;
}

function gd_jpeg(GdImage $img, int $quality): string
{
    ob_start();
    imagejpeg($img, null, $quality);
    return (string)ob_get_clean();
}

/**
 * Uloží náhled stylu: s GD ořízne na 3:2 a zmenší na 768×512 JPEG (zahodí metadata),
 * bez GD uloží soubor jak je (jen ověřený obrázek do 1,5 MB). Vrací název souboru.
 */
function store_thumb(string $styleId, string $bytes): string
{
    if (gd_available()) {
        $src = gd_load($bytes);
        $w = imagesx($src);
        $h = imagesy($src);
        // Oříznout na střed na poměr 3:2.
        if ($w * THUMB_H > $h * THUMB_W) {
            $cw = (int)round($h * THUMB_W / THUMB_H);
            $ch = $h;
        } else {
            $cw = $w;
            $ch = (int)round($w * THUMB_H / THUMB_W);
        }
        $dst = imagecreatetruecolor(THUMB_W, THUMB_H);
        imagecopyresampled($dst, $src, 0, 0, (int)(($w - $cw) / 2), (int)(($h - $ch) / 2), THUMB_W, THUMB_H, $cw, $ch);
        $out = gd_jpeg($dst, 82);
        $ext = 'jpg';
    } else {
        $info = image_info($bytes);
        if (strlen($bytes) > 1_500_000) {
            throw new RuntimeException('Server nemá rozšíření PHP GD – nahraj menší obrázek (do 1,5 MB, ideálně 768×512).');
        }
        $out = $bytes;
        $ext = [IMAGETYPE_JPEG => 'jpg', IMAGETYPE_PNG => 'png', IMAGETYPE_WEBP => 'webp'][$info[2]];
    }
    $file = $styleId . '-' . substr(sha1($out), 0, 8) . '.' . $ext;
    if (file_put_contents(styles_dir() . '/' . $file, $out) === false) {
        throw new RuntimeException('Náhled nejde uložit – zkontroluj práva ke složce data/.');
    }
    return $file;
}

/** Nastaví stylu nový náhled a smaže starý soubor. */
function set_style_thumb(string $styleId, ?string $file): void
{
    $old = find_style($styleId)['thumb'] ?? null;
    db()->prepare('UPDATE styles SET thumb = ?, updated_at = ? WHERE id = ?')->execute([$file, time(), $styleId]);
    if ($old && $old !== $file && preg_match(THUMB_PATTERN, $old)) {
        @unlink(styles_dir() . '/' . $old);
    }
}

/** Referenční fotka pro generování náhledů (vymyšlená postava). Uloží se zmenšená na max. 1536 px. */
function store_reference(string $bytes): void
{
    if (gd_available()) {
        $src = gd_load($bytes);
        $w = imagesx($src);
        $h = imagesy($src);
        $scale = min(1.0, 1536 / max($w, $h));
        if ($scale < 1.0) {
            $dst = imagecreatetruecolor((int)round($w * $scale), (int)round($h * $scale));
            imagecopyresampled($dst, $src, 0, 0, 0, 0, imagesx($dst), imagesy($dst), $w, $h);
            $src = $dst;
        }
        $bytes = gd_jpeg($src, 88);
    } else {
        image_info($bytes);
        if (strlen($bytes) > 7_000_000) {
            throw new RuntimeException('Fotka je moc velká (max. 7 MB).');
        }
    }
    if (file_put_contents(reference_path(), $bytes) === false) {
        throw new RuntimeException('Fotku nejde uložit – zkontroluj práva ke složce data/.');
    }
}

function reference_path(): string
{
    return styles_dir() . '/reference.img';
}

function send_image_file(string $path): never
{
    $bytes = (string)file_get_contents($path);
    $info = @getimagesizefromstring($bytes);
    header('Content-Type: ' . ($info['mime'] ?? 'application/octet-stream'));
    header('Content-Length: ' . strlen($bytes));
    header('X-Content-Type-Options: nosniff');
    header('Cache-Control: private, max-age=86400');
    echo $bytes;
    exit;
}

// ---------------------------------------------------------------- Gemini

function http_post_json(string $url, array $headers, string $body, int $timeout): array
{
    if (function_exists('curl_init')) {
        $ch = curl_init($url);
        curl_setopt_array($ch, [
            CURLOPT_POST => true,
            CURLOPT_POSTFIELDS => $body,
            CURLOPT_HTTPHEADER => $headers,
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_CONNECTTIMEOUT => 15,
            CURLOPT_TIMEOUT => $timeout,
        ]);
        $resp = curl_exec($ch);
        if ($resp === false) {
            $e = curl_error($ch);
            throw new RuntimeException('Google AI nedostupné: ' . $e);
        }
        return [(int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE), (string)$resp];
    }
    $ctx = stream_context_create(['http' => [
        'method' => 'POST',
        'header' => implode("\r\n", $headers),
        'content' => $body,
        'timeout' => $timeout,
        'ignore_errors' => true,
    ]]);
    $resp = @file_get_contents($url, false, $ctx);
    if ($resp === false) {
        throw new RuntimeException('Google AI nedostupné (server nemá curl ani povolené allow_url_fopen?).');
    }
    $code = 0;
    foreach ($http_response_header ?? [] as $line) {
        if (preg_match('#^HTTP/\S+\s+(\d{3})#', $line, $m)) {
            $code = (int)$m[1];
        }
    }
    return [$code, $resp];
}

function gemini_is_size_error(int $code, string $body): bool
{
    return $code === 400 && preg_match('/imageSize|image_size|image size/i', $body) === 1;
}

/**
 * Obrázek z Gemini (generateContent). $image = vstupní fotka (null = jen z textu).
 * Náhledům stačí 1K; model, který velikost nezná, dostane druhý pokus bez ní. Vrací bajty obrázku.
 */
function gemini_image(string $key, string $model, string $prompt, ?string $image): string
{
    if ($key === '') {
        throw new RuntimeException('Chybí Gemini API klíč (níže v nastavení knihovny).');
    }
    @set_time_limit(180);
    $parts = [['text' => $prompt]];
    if ($image !== null) {
        $info = image_info($image);
        $parts[] = ['inline_data' => ['mime_type' => $info['mime'], 'data' => base64_encode($image)]];
    }
    $model = preg_replace('#^models/#', '', trim($model)) ?: GEMINI_DEFAULT_MODEL;
    $url = 'https://generativelanguage.googleapis.com/v1beta/models/' . rawurlencode($model) . ':generateContent';
    $headers = ['Content-Type: application/json', 'x-goog-api-key: ' . $key];
    $call = function (?string $size) use ($parts, $url, $headers): array {
        $imageConfig = ['aspectRatio' => '3:2'];
        if ($size !== null) {
            $imageConfig['imageSize'] = $size;
        }
        $body = json_encode([
            'contents' => [['parts' => $parts]],
            'generationConfig' => ['responseModalities' => ['IMAGE'], 'imageConfig' => $imageConfig],
        ], JSON_UNESCAPED_SLASHES);
        return http_post_json($url, $headers, (string)$body, 150);
    };
    [$code, $resp] = $call($model === 'gemini-2.5-flash-image' ? null : '1K');
    if (gemini_is_size_error($code, $resp)) {
        [$code, $resp] = $call(null);
    }
    $data = json_decode($resp, true);
    if ($code !== 200) {
        $msg = is_array($data) ? (string)($data['error']['message'] ?? '') : '';
        error_log('Fotokiosek Gemini HTTP ' . $code . ': ' . substr($resp, 0, 400));
        throw new RuntimeException(match (true) {
            $code === 400 && stripos($msg, 'API key') !== false => 'Google odmítl API klíč – zkontroluj ho.',
            $code === 403 => 'Klíč nemá přístup k modelu (zapnuté placené účtování?).',
            $code === 404 => 'Model ' . $model . ' neexistuje.',
            $code === 429 => 'Překročený limit nebo kredit u Google – zkus to za chvíli.',
            default => 'AI vrátila chybu ' . $code . ($msg !== '' ? ': ' . clip($msg, 200) : '.'),
        });
    }
    foreach ($data['candidates'][0]['content']['parts'] ?? [] as $part) {
        if (!empty($part['thought'])) {
            continue;
        }
        $inline = $part['inlineData'] ?? $part['inline_data'] ?? null;
        if (is_array($inline) && !empty($inline['data'])) {
            $bytes = base64_decode((string)$inline['data'], true);
            if ($bytes !== false && $bytes !== '') {
                return $bytes;
            }
        }
    }
    $reason = (string)($data['candidates'][0]['finishReason'] ?? $data['promptFeedback']['blockReason'] ?? '');
    throw new RuntimeException('AI nevrátila obrázek' . ($reason !== '' ? ' (' . $reason . ')' : '') . ' – zkus to znovu nebo uprav prompt.');
}
