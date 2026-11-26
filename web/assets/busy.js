// Dlouhé akce (generování přes AI): po odeslání zablokovat tlačítko a ukázat, že se pracuje.
document.querySelectorAll('form.busy').forEach(function (form) {
  form.addEventListener('submit', function () {
    var button = form.querySelector('button');
    if (!button) return;
    setTimeout(function () {
      button.disabled = true;
      button.textContent = form.getAttribute('data-busy') || 'Pracuji…';
    }, 0);
  });
});
