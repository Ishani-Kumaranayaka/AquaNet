/* Login / registration page. Talks to POST /api/login and POST /api/register (body "user|password"). */
(function () {
  'use strict';
  var $ = function (id) { return document.getElementById(id); };
  var mode = 'login';

  fetch('/api/me').then(function (r) { if (r.ok) location.href = '/'; }).catch(function () {});

  function setMode(m) {
    mode = m;
    $('tabLogin').setAttribute('aria-pressed', m === 'login');
    $('tabReg').setAttribute('aria-pressed', m === 'register');
    $('p2wrap').hidden = m === 'login';
    $('go').textContent = m === 'login' ? 'Sign in' : 'Create account';
    $('p').autocomplete = m === 'login' ? 'current-password' : 'new-password';
    $('msg').textContent = ''; $('msg').className = 'msg';
  }
  function say(text, good) { $('msg').textContent = text; $('msg').className = 'msg ' + (good ? 'good' : 'bad'); }
  function post(url, body) {
    return fetch(url, { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: body })
      .then(function (r) { return r.json(); });
  }

  $('tabLogin').addEventListener('click', function () { setMode('login'); });
  $('tabReg').addEventListener('click', function () { setMode('register'); });
  $('form').addEventListener('submit', function (e) {
    e.preventDefault();
    var u = $('u').value.trim(), p = $('p').value;
    if (mode === 'register') {
      if (p !== $('p2').value) { say('Passwords do not match', false); return; }
      post('/api/register', u + '|' + p).then(function (j) {
        if (!j.ok) { say(j.message, false); return; }
        return post('/api/login', u + '|' + p).then(function (k) {
          if (k.ok) location.href = '/'; else say(k.message, false);
        });
      }).catch(function () { say('Server unreachable', false); });
    } else {
      post('/api/login', u + '|' + p).then(function (j) {
        if (j.ok) location.href = '/'; else say(j.message, false);
      }).catch(function () { say('Server unreachable', false); });
    }
  });
})();
