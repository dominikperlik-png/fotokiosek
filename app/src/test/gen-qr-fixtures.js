// Vygeneruje testovací QR kódy webovým generátorem (web/assets/qr.js) do resources/qr-web-fixtures.txt.
// Spuštění z kořene repozitáře:  node app/src/test/gen-qr-fixtures.js
// Kontrolu, že je přečte ZXing (stejná knihovna jako v automatu), dělá QrScanTest (gradlew testDebugUnitTest).
const fs = require('fs');
const path = require('path');

global.window = {};
global.document = { querySelectorAll: () => [] };
global.unescape = global.unescape || ((s) => decodeURIComponent(s.replace(/%(?![0-9a-fA-F]{2})/g, '%25')));
eval(fs.readFileSync(path.join(__dirname, '../../../web/assets/qr.js'), 'utf8'));
const QR = window.FotokioskQR;

const cases = [];
[1, 10, 14, 20, 26, 40, 42, 60, 62, 80, 84, 100, 106, 120, 122, 150, 152, 178, 180, 210, 213].forEach((n, i) => {
  let t = '';
  for (let k = 0; k < n; k++) t += String.fromCharCode(33 + ((k * 7 + i) % 90));
  cases.push(t);
});
cases.push('fotokiosek:report?u=https%3A%2F%2Fmuj-server.cz%2Ffotokiosek%2Fapi.php&t=0123456789abcdef0123456789abcdef0123456789abcdef&n=Automat%20%C4%8Dern%C3%BD');
cases.push('fotokiosek:gemini?k=AIzaSyD-test_KEY-0123456789abcdefghijklmnop');
cases.push('Příliš žluťoučký kůň úpěl ďábelské ódy');

const out = cases.map((t) => {
  const m = QR.encode(t);
  return Buffer.from(t, 'utf8').toString('base64') + '\n' + m.map((r) => r.map((v) => (v ? '1' : '0')).join('')).join('\n');
}).join('\n\n') + '\n';
const target = path.join(__dirname, 'resources', 'qr-web-fixtures.txt');
fs.mkdirSync(path.dirname(target), { recursive: true });
fs.writeFileSync(target, out);
console.log(`${cases.length} QR kódů → ${target}`);
