// Potvrzení před zrušením tokenu automatu.
document.querySelectorAll('form.confirm').forEach(function (form) {
  form.addEventListener('submit', function (e) {
    if (!window.confirm(form.getAttribute('data-confirm') || 'Opravdu?')) e.preventDefault();
  });
});
