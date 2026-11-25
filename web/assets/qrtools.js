// Nastavení: QR kód pro Gemini API klíč – vytvoří se jen v prohlížeči, klíč se nikam neposílá.
(function () {
  var btn = document.getElementById('gemini-qr-btn');
  if (!btn || !window.FotokioskQR) return;
  var input = document.getElementById('gemini-key');
  var box = document.getElementById('gemini-qr');
  var err = document.getElementById('gemini-qr-err');
  btn.addEventListener('click', function () {
    var key = input.value.trim();
    err.hidden = true;
    // Klíče Google mají různý tvar (AIza…, novější i s tečkou AQ.…) – jen délka a žádné mezery.
    if (!/^\S{20,300}$/.test(key)) {
      box.hidden = true;
      err.textContent = 'Klíč je moc krátký nebo obsahuje mezery – zkopíruj ho celý z Google AI Studio.';
      err.hidden = false;
      return;
    }
    try {
      FotokioskQR.render(box, 'fotokiosek:gemini?k=' + encodeURIComponent(key));
      box.hidden = false;
    } catch (e) {
      box.hidden = true;
      err.textContent = 'QR kód se nepodařilo vytvořit.';
      err.hidden = false;
    }
  });
})();

// Po přidání automatu posunout stránku rovnou k tokenu a QR kódu.
(function () {
  var box = document.querySelector('.token');
  if (box && box.scrollIntoView) box.scrollIntoView({ behavior: 'smooth', block: 'center' });
})();
