/* AquaNet live dashboard (shared by user.html = view only and admin.html = controls). Data flow:
 *   GET /api/history + /api/alerts once, then EventSource('/events') pushes 'state' and 'alert' events.
 *   Controls POST plain-text commands (e.g. "PUMP|P1|ON") to /api/command. */
(function () {
  'use strict';
  var $ = function (id) { return document.getElementById(id); };
  var COLORS = ['#22d3ee', '#fbbf24', '#34d399', '#f472b6', '#a78bfa', '#fb923c', '#60a5fa', '#f87171'];
  var STATUS_FILL = { OK: '#38bdf8', LOW: '#fbbf24', HIGH: '#2dd4bf', EMPTY: '#f87171', OVERFLOW: '#f87171', OFFLINE: '#64748b' };
  var MAX_HIST = 120;

  var ADMIN = document.body.getAttribute('data-role') === 'admin';
  var state = null, history = [], tankIds = [], structKey = '', es = null, lastMsg = 0, reconnectTimer = null;

  /* ---------- helpers ---------- */
  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }
  function safe(id) { return String(id).replace(/[^A-Za-z0-9_-]/g, '_'); }
  var toastTimer;
  function toast(msg, bad) {
    var t = $('toast'); t.textContent = msg; t.className = 'toast show' + (bad ? ' bad' : '');
    clearTimeout(toastTimer); toastTimer = setTimeout(function () { t.className = 'toast'; }, 2600);
  }
  function send(cmd) {
    return fetch('/api/command', { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: cmd })
      .then(function (r) { if (r.status === 401) { location.href = '/login.html'; } return r.json(); })
      .then(function (j) { toast((j.ok ? 'OK: ' : 'Error: ') + j.message, !j.ok); return j; })
      .catch(function () { toast('Server unreachable', true); });
  }
  function getJson(url) { return fetch(url).then(function (r) { if (!r.ok) throw new Error(r.status); return r.json(); }); }

  /* ---------- connection ---------- */
  function setConn(status) {
    var c = $('conn');
    c.className = 'conn ' + (status === 'on' ? 'on' : status === 'off' ? 'off' : '');
    $('connText').textContent = status === 'on' ? 'Live' : status === 'off' ? 'Server offline' : 'Connecting...';
    $('offline').className = 'offline-banner' + (status === 'off' ? ' show' : '');
  }
  function connect() {
    if (es) { es.close(); }
    clearTimeout(reconnectTimer);
    setConn('wait');
    es = new EventSource('/events');
    es.onopen = function () { lastMsg = Date.now(); loadInitial(); };
    es.addEventListener('state', function (e) {
      lastMsg = Date.now(); setConn('on');
      try { onState(JSON.parse(e.data)); } catch (err) { console.error('bad state', err); }
    });
    es.addEventListener('alert', function (e) {
      try { addAlert(JSON.parse(e.data)); } catch (err) { console.error('bad alert', err); }
    });
    es.onerror = function () {
      setConn('off');
      fetch('/api/me').then(function (r) { if (r.status === 401) location.href = '/login.html'; }).catch(function () {});
      // EventSource retries by itself; if it gave up (CLOSED) we retry manually.
      if (es.readyState === 2) reconnectTimer = setTimeout(connect, 3000);
    };
  }
  setInterval(function () {            // watchdog: no data for 6 s = treat as offline
    if (lastMsg && Date.now() - lastMsg > 6000) setConn('off');
  }, 2000);

  function loadInitial() {
    getJson('/api/history').then(function (h) { history = h.slice(-MAX_HIST); drawChart(); }).catch(function () {});
    getJson('/api/alerts').then(function (a) {
      $('feed').innerHTML = '';
      a.forEach(addAlert);
    }).catch(function () {});
  }

  /* ---------- state ---------- */
  function onState(s) {
    state = s;
    $('meta').textContent = 'tick ' + s.tick + ' · clients ' + s.clients;
    var key = s.tanks.map(function (t) { return t.id; }).join() + '|' + s.pumps.map(function (p) { return p.id; }).join() +
      '|' + s.valves.map(function (v) { return v.id; }).join();
    if (key !== structKey) { structKey = key; build(s); }
    updateTanks(s); updatePumps(s); updateValves(s); updateMode(s);
    var last = history.length ? history[history.length - 1] : null;
    if (!last || last.tick !== s.tick) {
      var pt = { tick: s.tick, tanks: {} };
      s.tanks.forEach(function (t) { pt.tanks[t.id] = t.percent; });
      history.push(pt);
      if (history.length > MAX_HIST) history.shift();
    }
    drawChart();
  }

  /* ---------- build DOM (only when the set of devices changes) ---------- */
  function build(s) {
    tankIds = s.tanks.map(function (t) { return t.id; });
    var tanks = $('tanks'); tanks.innerHTML = '';
    s.tanks.forEach(function (t, i) {
      var id = safe(t.id), card = el('div', 'tank'); card.id = 'tank-' + id;
      card.innerHTML =
        '<h3></h3><svg viewBox="0 0 130 170" role="img"><title></title>' +
        '<defs><clipPath id="clip-' + id + '"><rect x="15" y="10" width="100" height="150" rx="10"/></clipPath></defs>' +
        '<rect x="15" y="10" width="100" height="150" rx="10" fill="#04101e"/>' +
        '<g clip-path="url(#clip-' + id + ')"><g class="wlevel" id="w-' + id + '" style="transform:translateY(160px)">' +
        '<g class="wavea"><path id="wp-' + id + '" d="M-40 6 Q-20 -4 0 6 T40 6 T80 6 T120 6 T160 6 T200 6 V200 H-40Z" fill="#38bdf8" opacity=".9"/></g></g></g>' +
        '<rect x="15" y="10" width="100" height="150" rx="10" fill="none" stroke="#7dd3fc" stroke-width="3"/>' +
        '<path d="M22 20 V150" stroke="#fff" stroke-opacity=".15" stroke-width="4" stroke-linecap="round"/></svg>' +
        '<div class="pct" id="p-' + id + '">0%</div><div class="lit" id="l-' + id + '"></div>' +
        '<div><span class="badge" id="b-' + id + '">OK</span></div><div class="flow" id="f-' + id + '"></div>' +
        (ADMIN ? '<button type="button" class="btn small ghost" id="lk-' + id + '"></button>' : '');
      card.querySelector('h3').textContent = t.name + ' (' + t.id + ')';
      tanks.appendChild(card);
      if (ADMIN) $('lk-' + id).addEventListener('click', function () {
        var cur = state && state.tanks.filter(function (x) { return x.id === t.id; })[0];
        send('LEAK|' + t.id + '|' + (cur && cur.leaking ? 'OFF' : 'ON'));
      });
    });
    var lg = $('legend'); lg.innerHTML = '';
    tankIds.forEach(function (id, i) {
      var sp = el('span', null, id); sp.style.setProperty('--c', COLORS[i % COLORS.length]); lg.appendChild(sp);
    });

    if (window.AQ_onBuild) window.AQ_onBuild(s);
    var pumps = $('pumps'); pumps.innerHTML = '';
    if (!s.pumps.length) pumps.appendChild(el('p', 'meta', 'No pumps.'));
    s.pumps.forEach(function (p) {
      var id = safe(p.id), row = el('div', 'row');
      var led = el('span', 'led'); led.id = 'pl-' + id; led.setAttribute('aria-hidden', 'true');
      var nm = el('span', 'name', p.name + ' (' + p.id + ')');
      var fl = el('span', 'meta'); fl.id = 'pf-' + id;
      row.appendChild(led); row.appendChild(nm); row.appendChild(fl);
      if (ADMIN) {
        var b = el('button', 'btn small', 'Turn ON'); b.type = 'button'; b.id = 'pb-' + id;
        b.addEventListener('click', function () {
          var cur = state.pumps.filter(function (x) { return x.id === p.id; })[0];
          send('PUMP|' + p.id + '|' + (cur && cur.on ? 'OFF' : 'ON'));
        });
        row.appendChild(b);
      }
      pumps.appendChild(row);
    });

    var valves = $('valves'); valves.innerHTML = '';
    if (!s.valves.length) valves.appendChild(el('p', 'meta', 'No valves.'));
    s.valves.forEach(function (v) {
      var id = safe(v.id), row = el('div', 'row');
      var nm = el('span', 'name', v.name + ' (' + v.id + ')  ' + v.from + ' → ' + v.to);
      var r = document.createElement('input'); r.type = 'range'; r.min = 0; r.max = 100; r.step = 5; r.id = 'vr-' + id;
      r.setAttribute('aria-label', 'Opening of valve ' + v.name);
      if (!ADMIN) r.disabled = true;
      var val = el('span', 'meta', '0%'); val.id = 'vv-' + id; val.style.minWidth = '3em';
      r.addEventListener('input', function () { val.textContent = r.value + '%'; r.dataset.edit = '1'; });
      r.addEventListener('change', function () { send('VALVE|' + v.id + '|' + r.value).then(function () { delete r.dataset.edit; }); });
      row.appendChild(nm); row.appendChild(r); row.appendChild(val); valves.appendChild(row);
    });
  }

  /* ---------- updates ---------- */
  function updateTanks(s) {
    s.tanks.forEach(function (t) {
      var id = safe(t.id), pct = Math.max(0, Math.min(100, t.percent));
      var w = $('w-' + id); if (!w) return;
      w.style.transform = 'translateY(' + (10 + 150 * (1 - pct / 100)) + 'px)';
      $('wp-' + id).setAttribute('fill', t.leaking ? '#f87171' : (STATUS_FILL[t.status] || '#38bdf8'));
      $('p-' + id).textContent = pct.toFixed(1) + '%';
      $('l-' + id).textContent = t.level.toFixed(0) + ' / ' + t.capacity.toFixed(0) + ' L' +
        (t.measured >= 0 ? ' · sensor ' + t.measured.toFixed(0) + ' L' : ' · no sensor');
      var b = $('b-' + id); b.textContent = t.status; b.className = 'badge ' + t.status;
      $('f-' + id).textContent = 'in ' + t.inflow.toFixed(1) + ' L/s · out ' + t.outflow.toFixed(1) + ' L/s';
      $('tank-' + id).className = 'tank' + (t.leaking ? ' leak' : '');
      if ($('lk-' + id)) $('lk-' + id).textContent = t.leaking ? 'Repair leak' : 'Inject leak';
      var svg = $('tank-' + id).querySelector('svg');
      svg.setAttribute('aria-label', t.name + ' is ' + pct.toFixed(0) + ' percent full, status ' + t.status);
      svg.querySelector('title').textContent = t.name + ' ' + pct.toFixed(0) + '%';
    });
  }
  function updatePumps(s) {
    s.pumps.forEach(function (p) {
      var id = safe(p.id); if (!$('pl-' + id)) return;
      $('pl-' + id).className = 'led' + (p.on ? ' on' : '');
      $('pf-' + id).textContent = p.on ? p.flowRate.toFixed(1) + ' L/s' : 'stopped';
      if ($('pb-' + id)) {
        $('pb-' + id).textContent = p.on ? 'Turn OFF' : 'Turn ON';
        $('pb-' + id).setAttribute('aria-label', (p.on ? 'Turn off ' : 'Turn on ') + p.name);
      }
    });
  }
  function updateValves(s) {
    s.valves.forEach(function (v) {
      var id = safe(v.id), r = $('vr-' + id); if (!r) return;
      $('vv-' + id).textContent = (r.dataset.edit ? r.value : v.open) + '%';
      if (!r.dataset.edit && document.activeElement !== r) r.value = v.open;
    });
  }
  function updateMode(s) {
    if ($('modeAuto')) {
      $('modeAuto').setAttribute('aria-pressed', s.mode === 'AUTO');
      $('modeManual').setAttribute('aria-pressed', s.mode === 'MANUAL');
    }
    if ($('modeText')) $('modeText').textContent = s.mode === 'AUTO' ? 'Automatic control' : 'Manual control by an administrator';
  }

  /* ---------- alerts ---------- */
  function addAlert(a) {
    var feed = $('feed');
    if (feed.firstChild && feed.firstChild.dataset && feed.firstChild.dataset.empty) feed.innerHTML = '';
    if (feed.children.length === 1 && feed.firstChild.textContent === 'No alerts yet.') feed.innerHTML = '';
    var li = el('li', a.level);
    li.appendChild(el('time', null, a.time));
    li.appendChild(el('strong', null, a.level + ' '));
    li.appendChild(document.createTextNode(a.message));
    feed.insertBefore(li, feed.firstChild);
    while (feed.children.length > 100) feed.removeChild(feed.lastChild);
  }

  /* ---------- chart (plain canvas) ---------- */
  function drawChart() {
    var cv = $('chart'), dpr = window.devicePixelRatio || 1;
    var w = cv.clientWidth, h = cv.clientHeight;
    if (!w) return;
    if (cv.width !== Math.round(w * dpr)) { cv.width = Math.round(w * dpr); cv.height = Math.round(h * dpr); }
    var g = cv.getContext('2d');
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, w, h);
    var L = 36, R = 10, T = 10, B = 22, pw = w - L - R, ph = h - T - B;
    g.font = '11px system-ui,sans-serif'; g.textBaseline = 'middle';
    for (var y = 0; y <= 100; y += 25) {
      var yy = T + ph * (1 - y / 100);
      g.strokeStyle = '#1e4f7a'; g.lineWidth = 1; g.beginPath(); g.moveTo(L, yy); g.lineTo(w - R, yy); g.stroke();
      g.fillStyle = '#a9c4dc'; g.textAlign = 'right'; g.fillText(y + '%', L - 6, yy);
    }
    g.textAlign = 'center'; g.fillText('older', L + 16, h - 8); g.fillText('now', w - R - 12, h - 8);
    if (history.length < 2) return;
    var n = history.length;
    var ids = tankIds.length ? tankIds : Object.keys(history[n - 1].tanks);
    ids.forEach(function (id, i) {
      g.strokeStyle = COLORS[i % COLORS.length]; g.lineWidth = 2; g.beginPath();
      var started = false;
      for (var k = 0; k < n; k++) {
        var v = history[k].tanks[id]; if (v == null) { started = false; continue; }
        var x = L + pw * (MAX_HIST - n + k) / (MAX_HIST - 1), yv = T + ph * (1 - v / 100);
        if (!started) { g.moveTo(x, yv); started = true; } else g.lineTo(x, yv);
      }
      g.stroke();
    });
  }
  window.addEventListener('resize', drawChart);

  /* ---------- wiring ---------- */
  if (ADMIN) {
    $('modeAuto').addEventListener('click', function () { send('MODE|AUTO'); });
    $('modeManual').addEventListener('click', function () { send('MODE|MANUAL'); });
    $('resetBtn').addEventListener('click', function () { if (confirm('Reset the whole simulation?')) send('RESET'); });
  }
  connect();
})();
