/* Shared top bar: brand, signed-in user, role badge, logout. Also the login guard:
 * if the server says 401 (not logged in) we go to the login page. */
(function () {
  'use strict';
  var nav = document.getElementById('site-nav');
  if (nav) {
    nav.className = 'nav';
    nav.innerHTML = '<div class="wrap"><a class="brand" href="/"><img src="favicon.svg" alt="" width="30" height="30"><b>Aqua<span>Net</span></b></a>' +
      '<span class="meta" id="navTitle">Water Network Operations</span>' +
      '<div class="who"><span id="whoName"></span> <span class="role" id="whoRole"></span> ' +
      '<button type="button" class="btn small ghost" id="logoutBtn">Log out</button></div></div>';
    document.getElementById('logoutBtn').addEventListener('click', function () {
      fetch('/api/logout', { method: 'POST' }).then(function () { location.href = '/login.html'; });
    });
  }
  var f = document.getElementById('site-footer');
  if (f) f.innerHTML = '<div class="wrap">AquaNet &mdash; Water Network Operations</div>';

  window.AQ_ME = null;
  fetch('/api/me').then(function (r) {
    if (r.status === 401) { location.href = '/login.html'; return null; }
    return r.json();
  }).then(function (me) {
    if (!me) return;
    window.AQ_ME = me;
    var n = document.getElementById('whoName'), r = document.getElementById('whoRole');
    if (n) n.textContent = me.user;
    if (r) { r.textContent = me.role; r.className = 'role ' + me.role; }
    if (window.AQ_onMe) window.AQ_onMe(me);
  }).catch(function () {});
})();
