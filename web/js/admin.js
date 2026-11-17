/* Admin page extras: user reports, account management, activity log. All endpoints are ADMIN-only on the server. */
(function () {
  'use strict';
  var $ = function (id) { return document.getElementById(id); };
  function esc(t) { var d = document.createElement('div'); d.textContent = t; return d.innerHTML; }
  function post(url, body) {
    return fetch(url, { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: body }).then(function (r) { return r.json(); });
  }
  function toast(msg, bad) {
    var t = $('toast'); t.textContent = msg; t.className = 'toast show' + (bad ? ' bad' : '');
    setTimeout(function () { t.className = 'toast'; }, 2600);
  }

  function loadReports() {
    fetch('/api/reports').then(function (r) { return r.ok ? r.json() : []; }).then(function (list) {
      var ul = $('reports');
      if (!list.length) { ul.innerHTML = '<li>No reports.</li>'; return; }
      ul.innerHTML = list.map(function (r) {
        return '<li class="' + r.status + '"><strong>#' + r.id + ' ' + r.status + '</strong> &middot; ' + esc(r.tank || 'general') +
          ' &middot; from <b>' + esc(r.user) + '</b><br>' + esc(r.text) +
          (r.note ? '<br><em>Note: ' + esc(r.note) + '</em>' : '') +
          '<div class="by">' + esc(r.time) + '</div>' +
          (r.status === 'OPEN' ? '<button type="button" class="btn small" data-resolve="' + r.id + '" style="margin-top:6px">Mark resolved</button>' : '') +
          '</li>';
      }).join('');
    }).catch(function () {});
  }
  $('reports').addEventListener('click', function (e) {
    var id = e.target.getAttribute && e.target.getAttribute('data-resolve');
    if (!id) return;
    var note = prompt('Optional note for the user:', 'Fixed / looking into it') || '';
    post('/api/reports', id + '|' + note).then(function (j) { toast(j.message, !j.ok); loadReports(); loadAudit(); });
  });

  function loadUsers() {
    fetch('/api/users').then(function (r) { return r.ok ? r.json() : []; }).then(function (list) {
      $('stUsers').textContent = list.length;
      var me = window.AQ_ME ? window.AQ_ME.user.toLowerCase() : '';
      $('users').innerHTML = list.map(function (u) {
        var self = u.name.toLowerCase() === me;
        var other = u.role === 'ADMIN' ? 'USER' : 'ADMIN';
        return '<tr><td>' + esc(u.name) + (self ? ' (you)' : '') + '</td><td><span class="role ' + u.role + '">' + u.role + '</span></td><td>' +
          (self ? '' : '<button type="button" class="btn small ghost" data-role="' + esc(u.name) + '|' + other + '">Make ' + other + '</button> ' +
          '<button type="button" class="btn small danger" data-del="' + esc(u.name) + '">Delete</button>') + '</td></tr>';
      }).join('');
    }).catch(function () {});
  }
  $('users').addEventListener('click', function (e) {
    var r = e.target.getAttribute && e.target.getAttribute('data-role');
    var d = e.target.getAttribute && e.target.getAttribute('data-del');
    if (r) post('/api/users', 'ROLE|' + r).then(function (j) { toast(j.message, !j.ok); loadUsers(); loadAudit(); });
    else if (d && confirm('Delete account ' + d + '?')) post('/api/users', 'DELETE|' + d).then(function (j) { toast(j.message, !j.ok); loadUsers(); loadAudit(); });
  });

  function loadAudit() {
    fetch('/api/audit').then(function (r) { return r.ok ? r.json() : null; }).then(function (a) {
      if (!a) return;
      $('stReports').textContent = a.openReports;
      $('stSessions').textContent = a.sessions;
      $('audit').innerHTML = a.log.length ? a.log.map(function (l) {
        return '<li><time>' + esc(l.time.substring(11)) + '</time><strong>' + esc(l.user) + '</strong> ' + esc(l.action) + '</li>';
      }).join('') : '<li>Nothing yet.</li>';
    }).catch(function () {});
  }

  window.AQ_onMe = function () { loadUsers(); };
  function all() { loadReports(); loadAudit(); loadUsers(); }
  all(); setInterval(all, 4000);
})();
