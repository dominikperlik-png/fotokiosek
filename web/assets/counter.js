// Počítadlo znaků pod polem s data-counter="<id prvku pro text>" (limit = maxlength).
document.querySelectorAll('[data-counter]').forEach(function (field) {
  var out = document.getElementById(field.getAttribute('data-counter'));
  if (!out) return;
  var update = function () { out.textContent = field.value.length + ' / ' + field.maxLength + ' znaků'; };
  field.addEventListener('input', update);
  update();
});
