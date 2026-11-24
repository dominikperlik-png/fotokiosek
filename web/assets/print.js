// Tlačítko Vytisknout a automatický tisk hned po vystavení účtenky.
(function () {
  var btn = document.getElementById('print-btn');
  if (btn) btn.addEventListener('click', function () { window.print(); });
  var receipt = document.querySelector('.receipt[data-autoprint]');
  if (receipt) {
    // Po obnovení stránky už znovu netisknout.
    if (window.history && history.replaceState) history.replaceState(null, '', location.pathname + location.search.replace(/[?&]print=1/, '').replace(/^&/, '?'));
    window.addEventListener('load', function () { window.print(); });
  }
})();
