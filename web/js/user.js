/* User page extras: report form + "my reports" list. */
(function () {
  'use strict';
  var $ = function (id) { return document.getElementById(id); };
  function esc(t) { var d = document.createElement('div'); d.textContent = t; return d.innerHTML; }
  var filled = false;

  window.AQ_onBuild = function (s) {            // called by dashboard.js when the tank list is known
    var sel = $('rTank');
    sel.innerHTML = '<option value="">General / not sure</option>';
    s.tanks.forEach(function (t) {
      var o = document.createElement('option'); o.value = t.id; o.textContent = t.name + ' (' + t.id + ')'; sel.appendChild(o);
    });
  };

  function load() {
    fetch('/api/reports').then(function (r) { return r.ok ? r.json() : []; }).then(function (list) {
      var ul = $('myReports');
      if (!list.length) { ul.innerHTML = '<li>No reports yet.</li>'; return; }
      ul.innerHTML = list.map(function (r) {
        return '<li class="' + r.status + '"><strong>#' + r.id + ' ' + r.status + '</strong> &middot; ' + esc(r.tank || 'general') +
          '<br>' + esc(r.text) + (r.note ? '<br><em>Admin: ' + esc(r.note) + '</em>' : '') +
          '<div class="by">' + esc(r.time) + '</div></li>';
      }).join('');
    }).catch(function () {});
  }

  $('reportForm').addEventListener('submit', function (e) {
    e.preventDefault();
    fetch('/api/report', { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: $('rTank').value + '|' + $('rText').value })
      .then(function (r) { return r.json(); })
      .then(function (j) {
        var t = $('toast'); t.textContent = j.message; t.className = 'toast show' + (j.ok ? '' : ' bad');
        setTimeout(function () { t.className = 'toast'; }, 2600);
        if (j.ok) { $('rText').value = ''; load(); }
      });
  });
  load(); setInterval(load, 5000);
})();
