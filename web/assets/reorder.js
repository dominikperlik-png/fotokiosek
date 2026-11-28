// Přetahování karet v mřížce (myš i prst – Pointer Events) za úchyt .drag-handle.
// Po puštění, když se pořadí změnilo, odešle formulář z data-reorder s id karet (data-id) oddělenými čárkou.
document.querySelectorAll('[data-reorder]').forEach(function (grid) {
  var form = document.getElementById(grid.getAttribute('data-reorder'));
  if (!form) return;
  var cards = function () { return Array.prototype.slice.call(grid.querySelectorAll('[data-id]')); };
  var order = function () { return cards().map(function (c) { return c.getAttribute('data-id'); }).join(','); };
  var busy = false; // jen jedno přetahování najednou (druhý prst, zaseknutá myš)

  grid.querySelectorAll('.drag-handle').forEach(function (handle) {
    handle.addEventListener('pointerdown', function (e) {
      if (e.button !== 0 || busy) return;
      busy = true;
      var card = handle.closest('[data-id]');
      var before = order();
      var home = card.nextSibling;
      e.preventDefault();
      var id = e.pointerId;
      card.classList.add('dragging');
      document.body.classList.add('reordering');

      var move = function (ev) {
        if (ev.pointerId !== id) return;
        var target = document.elementFromPoint(ev.clientX, ev.clientY);
        var over = target && target.closest('[data-id]');
        if (!over || over === card || over.parentNode !== grid) return;
        var r = over.getBoundingClientRect();
        // Za polovinou karty (vodorovně) → za ni, jinak před ni.
        var after = ev.clientX > r.left + r.width / 2;
        grid.insertBefore(card, after ? over.nextSibling : over);
      };
      // Posluchače na document: přesunutím karty v DOM by prvek ztratil zachycení ukazatele.
      var end = function (ev) {
        if (ev.pointerId !== id) return;
        document.removeEventListener('pointermove', move);
        document.removeEventListener('pointerup', end);
        document.removeEventListener('pointercancel', end);
        card.classList.remove('dragging');
        document.body.classList.remove('reordering');
        busy = false;
        if (ev.type === 'pointercancel') { // přerušeno systémem → vrátit kartu, nic neukládat
          grid.insertBefore(card, home);
          return;
        }
        var now = order();
        if (now !== before) {
          form.querySelector('[name="order"]').value = now;
          form.submit();
        }
      };
      document.addEventListener('pointermove', move);
      document.addEventListener('pointerup', end);
      document.addEventListener('pointercancel', end);
    });
  });
});
