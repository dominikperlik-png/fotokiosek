/*
 * Minimální generátor QR kódu (ISO/IEC 18004): bajtový režim, oprava chyb úrovně M, verze 1–10
 * (až 213 bajtů – na adresu + token i API klíč stačí). Bez cizích knihoven, běží jen v prohlížeči:
 * nic se neposílá na server.
 *
 * Použití v HTML:  <div class="qr" data-qr="text k zakódování"></div>  → vykreslí SVG.
 * Pro tlačítko „Vytvořit QR“: FotokioskQR.render(element, text).
 */
(function () {
  'use strict';

  // Verze → [EC kódových slov na blok, bloky skupiny 1, data slov v bloku sk. 1, bloky sk. 2, data slov sk. 2] pro úroveň M.
  var EC_M = [null,
    [10, 1, 16, 0, 0], [16, 1, 28, 0, 0], [26, 1, 44, 0, 0], [18, 2, 32, 0, 0], [24, 2, 43, 0, 0],
    [16, 4, 27, 0, 0], [18, 4, 31, 0, 0], [22, 2, 38, 2, 39], [22, 3, 36, 2, 37], [26, 4, 43, 1, 44]];
  var ALIGN = [null, [], [6, 18], [6, 22], [6, 26], [6, 30], [6, 34], [6, 22, 38], [6, 24, 42], [6, 26, 46], [6, 28, 50]];

  // ---- GF(256), primitivní polynom 0x11D ----
  function gfMul(x, y) {
    var z = 0;
    for (var i = 7; i >= 0; i--) {
      z = (z << 1) ^ ((z >>> 7) * 0x11D);
      z ^= ((y >>> i) & 1) * x;
    }
    return z & 0xFF;
  }
  function rsDivisor(degree) {
    var result = [];
    for (var i = 0; i < degree - 1; i++) result.push(0);
    result.push(1);
    var root = 1;
    for (i = 0; i < degree; i++) {
      for (var j = 0; j < result.length; j++) {
        result[j] = gfMul(result[j], root);
        if (j + 1 < result.length) result[j] ^= result[j + 1];
      }
      root = gfMul(root, 0x02);
    }
    return result;
  }
  function rsRemainder(data, divisor) {
    var result = divisor.map(function () { return 0; });
    data.forEach(function (b) {
      var factor = b ^ result.shift();
      result.push(0);
      for (var i = 0; i < divisor.length; i++) result[i] ^= gfMul(divisor[i], factor);
    });
    return result;
  }

  function utf8(text) {
    var bytes = [];
    var s = unescape(encodeURIComponent(text));
    for (var i = 0; i < s.length; i++) bytes.push(s.charCodeAt(i));
    return bytes;
  }

  function dataCapacity(v) {
    var t = EC_M[v];
    return t[1] * t[2] + t[3] * t[4];
  }

  /** Text → pole modulů (true = tmavý). */
  function encode(text) {
    var bytes = utf8(text);
    var version = 0;
    for (var v = 1; v <= 10; v++) {
      var ccBits = v < 10 ? 8 : 16;
      if (4 + ccBits + bytes.length * 8 <= dataCapacity(v) * 8) { version = v; break; }
    }
    if (!version) throw new Error('Text je na QR kód moc dlouhý');

    // Datový bitový proud: režim bajty (0100), délka, data, ukončení, doplnění.
    var bits = [];
    function put(val, len) { for (var i = len - 1; i >= 0; i--) bits.push((val >>> i) & 1); }
    put(4, 4);
    put(bytes.length, version < 10 ? 8 : 16);
    bytes.forEach(function (b) { put(b, 8); });
    var capBits = dataCapacity(version) * 8;
    put(0, Math.min(4, capBits - bits.length));
    put(0, (8 - bits.length % 8) % 8);
    for (var pad = 0xEC; bits.length < capBits; pad ^= 0xEC ^ 0x11) put(pad, 8);
    var data = [];
    for (var i = 0; i < bits.length; i += 8) {
      var byte = 0;
      for (var j = 0; j < 8; j++) byte = (byte << 1) | bits[i + j];
      data.push(byte);
    }

    // Bloky, Reed-Solomon, prokládání.
    var t = EC_M[version];
    var divisor = rsDivisor(t[0]);
    var blocks = [];
    var k = 0;
    [[t[1], t[2]], [t[3], t[4]]].forEach(function (g) {
      for (var n = 0; n < g[0]; n++) {
        var d = data.slice(k, k + g[1]);
        k += g[1];
        blocks.push({ d: d, e: rsRemainder(d, divisor) });
      }
    });
    var codewords = [];
    var maxData = Math.max.apply(null, blocks.map(function (b) { return b.d.length; }));
    for (i = 0; i < maxData; i++) blocks.forEach(function (b) { if (i < b.d.length) codewords.push(b.d[i]); });
    for (i = 0; i < t[0]; i++) blocks.forEach(function (b) { codewords.push(b.e[i]); });

    // Matice: funkční vzory.
    var size = version * 4 + 17;
    var modules = [], isFn = [];
    for (i = 0; i < size; i++) {
      modules.push(new Array(size).fill(false));
      isFn.push(new Array(size).fill(false));
    }
    function setFn(x, y, dark) { modules[y][x] = dark; isFn[y][x] = true; }

    for (i = 0; i < size; i++) { setFn(6, i, i % 2 === 0); setFn(i, 6, i % 2 === 0); } // časování
    function finder(cx, cy) {
      for (var dy = -4; dy <= 4; dy++) {
        for (var dx = -4; dx <= 4; dx++) {
          var dist = Math.max(Math.abs(dx), Math.abs(dy)), x = cx + dx, y = cy + dy;
          if (x >= 0 && x < size && y >= 0 && y < size) setFn(x, y, dist !== 2 && dist !== 4);
        }
      }
    }
    finder(3, 3); finder(size - 4, 3); finder(3, size - 4);
    var al = ALIGN[version];
    for (i = 0; i < al.length; i++) {
      for (j = 0; j < al.length; j++) {
        if ((i === 0 && j === 0) || (i === 0 && j === al.length - 1) || (i === al.length - 1 && j === 0)) continue;
        for (var dy = -2; dy <= 2; dy++) {
          for (var dx = -2; dx <= 2; dx++) setFn(al[i] + dx, al[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
        }
      }
    }
    function drawFormat(mask) {
      var d = (0 << 3) | mask; // úroveň M = 00
      var rem = d;
      for (var n = 0; n < 10; n++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
      var f = ((d << 10) | rem) ^ 0x5412;
      function bit(n) { return ((f >>> n) & 1) !== 0; }
      for (n = 0; n <= 5; n++) setFn(8, n, bit(n));
      setFn(8, 7, bit(6)); setFn(8, 8, bit(7)); setFn(7, 8, bit(8));
      for (n = 9; n < 15; n++) setFn(14 - n, 8, bit(n));
      for (n = 0; n < 8; n++) setFn(size - 1 - n, 8, bit(n));
      for (n = 8; n < 15; n++) setFn(8, size - 15 + n, bit(n));
      setFn(8, size - 8, true); // tmavý modul
    }
    drawFormat(0); // rezervace míst
    if (version >= 7) {
      var rem = version;
      for (i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
      var vbits = (version << 12) | rem;
      for (i = 0; i < 18; i++) {
        var b = ((vbits >>> i) & 1) !== 0, a = size - 11 + i % 3, c = Math.floor(i / 3);
        setFn(a, c, b); setFn(c, a, b);
      }
    }

    // Data cik-cak zdola zprava.
    var bitIndex = 0;
    for (var right = size - 1; right >= 1; right -= 2) {
      if (right === 6) right = 5;
      for (var vert = 0; vert < size; vert++) {
        for (j = 0; j < 2; j++) {
          var x = right - j, upward = ((right + 1) & 2) === 0, y = upward ? size - 1 - vert : vert;
          if (!isFn[y][x] && bitIndex < codewords.length * 8) {
            modules[y][x] = ((codewords[bitIndex >>> 3] >>> (7 - (bitIndex & 7))) & 1) !== 0;
            bitIndex++;
          }
        }
      }
    }

    // Maska s nejnižší penalizací.
    function maskBit(m, x, y) {
      switch (m) {
        case 0: return (x + y) % 2 === 0;
        case 1: return y % 2 === 0;
        case 2: return x % 3 === 0;
        case 3: return (x + y) % 3 === 0;
        case 4: return (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0;
        case 5: return (x * y) % 2 + (x * y) % 3 === 0;
        case 6: return ((x * y) % 2 + (x * y) % 3) % 2 === 0;
        default: return ((x + y) % 2 + (x * y) % 3) % 2 === 0;
      }
    }
    function applyMask(m) {
      for (var y = 0; y < size; y++) for (var x = 0; x < size; x++) if (!isFn[y][x] && maskBit(m, x, y)) modules[y][x] = !modules[y][x];
    }
    function penalty() {
      var p = 0, x, y, run, dark = 0;
      for (y = 0; y < size; y++) { // řady stejné barvy
        run = 1;
        for (x = 1; x <= size; x++) {
          if (x < size && modules[y][x] === modules[y][x - 1]) run++;
          else { if (run >= 5) p += run - 2; run = 1; }
        }
      }
      for (x = 0; x < size; x++) {
        run = 1;
        for (y = 1; y <= size; y++) {
          if (y < size && modules[y][x] === modules[y - 1][x]) run++;
          else { if (run >= 5) p += run - 2; run = 1; }
        }
      }
      for (y = 0; y < size - 1; y++) for (x = 0; x < size - 1; x++) { // bloky 2×2
        var c = modules[y][x];
        if (c === modules[y][x + 1] && c === modules[y + 1][x] && c === modules[y + 1][x + 1]) p += 3;
      }
      for (y = 0; y < size; y++) for (x = 0; x < size; x++) if (modules[y][x]) dark++;
      p += Math.floor(Math.abs(dark * 20 - size * size * 10) / (size * size)) * 10; // vyváženost
      return p;
    }
    var best = 0, bestScore = Infinity;
    for (var m = 0; m < 8; m++) {
      applyMask(m); drawFormat(m);
      var score = penalty();
      if (score < bestScore) { bestScore = score; best = m; }
      applyMask(m); // zpět (XOR)
    }
    applyMask(best); drawFormat(best);
    return modules;
  }

  /** Vykreslí QR jako SVG do prvku (s tichou zónou 4 moduly). */
  function render(el, text) {
    var modules = encode(text), n = modules.length, q = 4, path = '';
    for (var y = 0; y < n; y++) for (var x = 0; x < n; x++) if (modules[y][x]) path += 'M' + (x + q) + ' ' + (y + q) + 'h1v1h-1z';
    var s = n + 2 * q;
    el.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + s + ' ' + s + '" shape-rendering="crispEdges" role="img" aria-label="QR kód">' +
      '<rect width="100%" height="100%" fill="#fff"/><path d="' + path + '" fill="#000"/></svg>';
  }

  window.FotokioskQR = { encode: encode, render: render };
  document.querySelectorAll('[data-qr]').forEach(function (el) {
    try {
      render(el, el.getAttribute('data-qr'));
    } catch (e) {
      el.className += ' qr-error';
      el.textContent = 'QR kód se nepodařilo vytvořit – zadej údaje do automatu ručně.';
    }
  });
})();
