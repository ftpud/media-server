'use strict';
// Media Server dashboard: vanilla JS, no build step.
const $ = (s) => document.querySelector(s);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const fmtTime = (ms) => { if (ms == null || ms < 0) return '0:00'; const s = Math.floor(ms / 1000), h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, x = s % 60;
  return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(x).padStart(2, '0'); };
const fmtDur = (sec) => { if (sec == null) return '—'; const d = Math.floor(sec / 86400), h = Math.floor(sec / 3600) % 24, m = Math.floor(sec / 60) % 60;
  return d ? `${d}d ${h}h` : h ? `${h}h ${m}m` : `${m}m ${sec % 60}s`; };
const timers = [];
let hwdec = null;

// ---------- api ----------
async function api(path, opts = {}) {
  const init = { credentials: 'same-origin', headers: {}, ...opts };
  if (opts.json !== undefined) { init.method = init.method || 'POST'; init.headers['Content-Type'] = 'application/json'; init.body = JSON.stringify(opts.json); }
  const r = await fetch(path, init);
  if (r.status === 401) { showLogin(); throw new Error('unauthorized'); }
  const text = await r.text();
  let data; try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  if (!r.ok) throw new Error((data && data.error) || r.statusText);
  return data;
}
const post = (path, json = {}) => api(path, { method: 'POST', json });

function toast(msg, bad = false) {
  const t = document.createElement('div');
  t.className = 'toast' + (bad ? ' bad' : '');
  t.textContent = msg;
  $('#toasts').appendChild(t);
  setTimeout(() => t.remove(), bad ? 6000 : 3000);
}
async function act(label, fn) {
  try { await fn(); toast(label); } catch (e) { if (e.message !== 'unauthorized') toast(label + ' failed: ' + e.message, true); }
}

function confirmBox(title, html, okLabel = 'OK', danger = true) {
  return new Promise((resolve) => {
    $('#modalTitle').textContent = title;
    $('#modalBody').innerHTML = html;
    const ok = $('#modalOk');
    ok.textContent = okLabel;
    ok.className = 'btn ' + (danger ? 'danger' : 'primary');
    $('#modal').classList.remove('hidden');
    const done = (v) => { $('#modal').classList.add('hidden'); ok.onclick = null; $('#modalCancel').onclick = null; resolve(v); };
    ok.onclick = () => done(true);
    $('#modalCancel').onclick = () => done(false);
  });
}

// ---------- login / theme ----------
function showLogin() { timers.forEach(clearInterval); timers.length = 0; $('#app').classList.add('hidden'); $('#login').classList.remove('hidden'); }
$('#loginForm').onsubmit = async (e) => {
  e.preventDefault();
  try { await post('/api/login', { token: $('#tokenInput').value }); $('#login').classList.add('hidden'); start(); }
  catch (err) { $('#loginError').textContent = err.message; }
};
$('#logoutBtn').onclick = async () => { await post('/api/logout').catch(() => {}); showLogin(); };
const applyTheme = () => document.documentElement.classList.toggle('light', localStorage.theme === 'light');
$('#themeBtn').onclick = () => { localStorage.theme = localStorage.theme === 'light' ? 'dark' : 'light'; applyTheme(); };
applyTheme();

// ---------- host ----------
async function refreshHost() {
  const s = await api('/api/system');
  const load = s.load ? s.load[0] : 0, cpus = s.cpus || 1;
  const memUsed = s.memTotalMb ? 1 - s.memAvailableMb / s.memTotalMb : 0;
  const cls = (v, w, b) => v >= b ? 'bad' : v >= w ? 'warn' : '';
  $('#hostChips').innerHTML =
    `<span class="chip ${cls(load / cpus, 0.8, 1.2)}">load <b>${load.toFixed(2)}</b> / ${cpus} cpu</span>` +
    `<span class="chip ${cls(memUsed, 0.85, 0.95)}">memory <b>${Math.round(memUsed * 100)}%</b></span>` +
    `<span class="chip">up <b>${fmtDur(s.uptimeSec)}</b></span>`;
  $('#hostInfo').textContent = `load ${s.load ? s.load.join(' · ') : ''}`;
  $('#disks').innerHTML = (s.disks || []).map((d) => {
    const used = d.totalGb ? 1 - d.freeGb / d.totalGb : 0;
    const st = used >= 0.97 || (d.busy ?? 0) >= 90 ? 'bad' : used >= 0.9 || (d.busy ?? 0) >= 60 ? 'warn' : '';
    return `<div class="disk ${st}">
      <div class="row"><b>${esc(d.mount)}</b><span class="muted small">${esc(d.device || '')}${d.readOnly ? ' · read-only' : ''}</span></div>
      <div class="meter ${used >= 0.97 ? 'bad' : used >= 0.9 ? 'warn' : ''}"><div style="width:${Math.round(used * 100)}%"></div></div>
      <div class="row small"><span>${d.freeGb} GB free of ${d.totalGb} GB</span><span class="${(d.busy ?? 0) >= 90 ? 'error' : 'muted'}">busy ${d.busy ?? '—'}%</span></div>
    </div>`;
  }).join('');
}

// ---------- services ----------
const GROUPS = { stream: 'Stream', core: 'Core', legacy: 'Legacy (replaced by mediad)' };
async function refreshServices() {
  const list = await api('/api/services');
  const up = list.filter((s) => s.running).length;
  $('#svcInfo').textContent = `${up}/${list.length} running`;
  let html = '';
  for (const g of Object.keys(GROUPS)) {
    const items = list.filter((s) => s.group === g);
    if (!items.length) continue;
    html += `<div class="group-title">${GROUPS[g]}</div>`;
    for (const s of items) {
      const cpu = s.processes.reduce((a, p) => a + (p.cpu || 0), 0), rss = s.processes.reduce((a, p) => a + (p.rssMb || 0), 0);
      const main = s.processes[0];
      const dot = !s.running ? 'down' : s.health === 'down' ? 'unhealthy' : 'up';
      const state = !s.running ? 'stopped' : s.health === 'down' ? 'unhealthy' : 'running';
      const pend = s.pendingDeploy ? `<div class="pending">New build waiting: ${esc(s.pendingDeploy.file)} (${new Date(s.pendingDeploy.modified).toLocaleString()})</div>` : '';
      html += `<div class="svc ${g === 'legacy' ? 'legacy' : ''}">
        <div class="svc-head"><span class="dot ${dot}"></span><span class="svc-name">${esc(s.title)}</span><span class="badge ${dot === 'up' ? 'ok' : dot === 'unhealthy' ? 'warn' : 'bad'}">${state}</span></div>
        <div class="svc-desc">${esc(s.description)}</div>
        <div class="stats">
          <div class="stat"><div class="k">uptime</div><div class="v">${main ? fmtDur(main.uptimeSec) : '—'}</div></div>
          <div class="stat"><div class="k">cpu</div><div class="v">${s.running ? cpu.toFixed(1) + '%' : '—'}</div></div>
          <div class="stat"><div class="k">memory</div><div class="v">${s.running ? rss + ' MB' : '—'}</div></div>
        </div>
        ${pend}
        <div class="svc-actions">
          ${s.running ? (s.self ? '' : `<button class="btn small" data-a="stop" data-s="${s.name}">Stop</button>`) + `<button class="btn small" data-a="restart" data-s="${s.name}">Restart</button>`
                      : `<button class="btn small primary" data-a="start" data-s="${s.name}">Start</button>`}
          <button class="btn small" data-a="logs" data-s="${s.name}">Logs</button>
          ${s.pendingDeploy ? `<button class="btn small primary" data-a="deploy" data-s="${s.name}">Deploy</button>` : ''}
          ${s.versions ? `<button class="btn small" data-a="versions" data-s="${s.name}">Versions (${s.versions})</button>` : ''}
        </div>
      </div>`;
    }
  }
  $('#services').innerHTML = html;
  window.__services = list;
}
$('#services').onclick = async (e) => {
  const b = e.target.closest('button[data-a]');
  if (!b) return;
  const name = b.dataset.s, a = b.dataset.a, svc = (window.__services || []).find((s) => s.name === name);
  const title = svc ? svc.title : name;
  if (a === 'logs') return openLogs(svc);
  if (a === 'versions') return showVersions(svc);
  if (a !== 'start') {
    const warn = name === 'mediad' || name === 'stream-service' ? '<p><b>This interrupts the live stream.</b></p>' : '';
    if (!(await confirmBox(`${a[0].toUpperCase() + a.slice(1)} ${title}?`, warn + `<p class="muted">${esc(svc.description)}</p>`, a))) return;
  }
  b.disabled = true;
  await act(`${title}: ${a}`, () => post(`/api/services/${name}/${a}`));
  setTimeout(refreshServices, 1500);
};
async function showVersions(svc) {
  const vs = await api(`/api/services/${svc.name}/versions`);
  const html = '<ul class="list">' + vs.map((v) => `<li><span class="t mono">${esc(v.version)}</span><span class="muted small">${v.sizeMb} MB</span>
      <button class="btn small" data-file="${esc(v.file)}">Restore</button></li>`).join('') + '</ul>';
  $('#modalTitle').textContent = `${svc.title}: previous versions`;
  $('#modalBody').innerHTML = html;
  $('#modalOk').className = 'hidden';
  $('#modal').classList.remove('hidden');
  $('#modalCancel').textContent = 'Close';
  $('#modalCancel').onclick = () => { $('#modal').classList.add('hidden'); $('#modalOk').className = 'btn danger'; $('#modalCancel').textContent = 'Cancel'; };
  $('#modalBody').onclick = async (e) => {
    const b = e.target.closest('button[data-file]');
    if (!b) return;
    b.disabled = true;
    await act(`${svc.title}: restored ${b.dataset.file}`, () => post(`/api/services/${svc.name}/rollback`, { file: b.dataset.file }));
    $('#modalCancel').click();
    setTimeout(refreshServices, 1500);
  };
}

// ---------- stream ----------
async function refreshStream() {
  let s;
  try { s = await api('/api/stream/status'); } catch (e) {
    if (e.message === 'unauthorized') return;
    $('#liveBadge').className = 'badge bad'; $('#liveBadge').textContent = 'OFFLINE';
    $('#nowTitle').textContent = 'mediad is not reachable'; return;
  }
  const c = s.current;
  const badge = $('#liveBadge');
  if (!s.connected) { badge.className = 'badge bad'; badge.textContent = 'DISCONNECTED'; }
  else if (c) { badge.className = 'badge live'; badge.textContent = '● LIVE'; }
  else { badge.className = 'badge warn'; badge.textContent = 'IDLE'; }
  $('#nowTitle').textContent = c ? c.title || c.path.split('/').pop() : 'Nothing playing (filler)';
  const pos = c ? c.position_ms : 0, dur = c ? c.duration_ms : 0;
  $('#nowBar').style.width = dur ? Math.min(100, (100 * pos) / dur) + '%' : '0';
  $('#nowTime').textContent = `${fmtTime(pos)} / ${fmtTime(dur)}`;
  const b = [];
  if (hwdec !== null) b.push(`<span class="badge ${hwdec ? 'ok' : ''}">${hwdec ? 'HW decode' : 'SW decode'}</span>`);
  const av = s.audio_minus_video_ms;
  b.push(`<span class="badge ${Math.abs(av) > 40 ? 'warn' : 'ok'}">A/V ${av > 0 ? '+' : ''}${av} ms</span>`);
  b.push(`<span class="badge ${s.late_frames ? 'warn' : 'ok'}" title="frames repeated because a track was not ready in time">late ${s.late_frames}</span>`);
  if (s.track_audio_lag_ms !== undefined) b.push(`<span class="badge ${s.track_audio_lag_ms > 40 ? 'warn' : 'ok'}" title="audio of the current track still catching up after a slow read">track lag ${s.track_audio_lag_ms} ms</span>`);
  b.push(`<span class="badge">on air ${fmtDur(Math.floor(s.output_ms / 1000))}</span>`);
  if (s.queue && s.queue.length) b.push(`<span class="badge">queued ${s.queue.length}</span>`);
  $('#nowBadges').innerHTML = b.join('');
}
async function refreshUpcoming() {
  try {
    const items = await api('/api/stream/upcoming');
    $('#upcoming').innerHTML = items.slice(0, 12).map((i) => `<li><span class="t" title="${esc(i.fullpath)}">${esc(i.title)}</span><span class="muted small">${fmtTime(i.length)}</span></li>`).join('');
    $('#upcomingInfo').textContent = 'from media-service';
  } catch (e) { $('#upcomingInfo').textContent = 'unavailable'; }
}
$('#nextBtn').onclick = async () => { await act('Skipped to the next track', () => post('/api/stream/next')); setTimeout(refreshUpcoming, 1500); };
$('#seekForm').onsubmit = async (e) => { e.preventDefault(); const t = $('#seekInput').value.trim(); if (t) await act(`Seek to ${t}`, () => post('/api/stream/seek', { time: t })); };
$('#msgForm').onsubmit = async (e) => { e.preventDefault(); const t = $('#msgInput').value.trim(); if (!t) return; await act('Message shown on stream', () => post('/api/stream/message', { text: t })); $('#msgInput').value = ''; };
$('#searchForm').onsubmit = async (e) => {
  e.preventDefault();
  const q = $('#searchInput').value.trim();
  if (!q) { $('#searchResults').innerHTML = ''; return; }
  try {
    const items = await post('/api/stream/search', { q });
    $('#searchResults').innerHTML = items.length ? items.map((i) => `<li><span class="t" title="${esc(i.fullpath)}">${esc(i.title)}</span>
      <button class="btn small" data-q="${esc(i.fullpath)}" data-next="0">Queue</button><button class="btn small primary" data-q="${esc(i.fullpath)}" data-next="1">Play next</button></li>`).join('')
      : '<li class="muted">Nothing found</li>';
  } catch (err) { toast('Search failed: ' + err.message, true); }
};
$('#searchResults').onclick = async (e) => {
  const b = e.target.closest('button[data-q]');
  if (!b) return;
  await act(b.dataset.next === '1' ? 'Will play next' : 'Added to the queue', () => post('/api/stream/enqueue', { q: b.dataset.q, next: b.dataset.next === '1' }));
  setTimeout(refreshUpcoming, 1000);
};

// mediad events (long poll)
const EV = { track_started: ['▶', 'Started'], track_ended: ['■', 'Ended'], track_ending: ['…', 'Ending soon'], track_error: ['⚠', 'Error'],
  idle: ['◌', 'Idle (filler)'], connected: ['⇡', 'Connected'], disconnected: ['⇣', 'Disconnected'], hwdec_fallback: ['↓', 'Software decode fallback'] };
async function eventLoop() {
  let since = 0, first = true;
  while (true) {
    try {
      const r = await api(`/api/stream/events?since=${since}&wait=${first ? 0 : 20000}`);
      const list = $('#events');
      for (const e of r.events) {
        since = Math.max(since, e.id);
        if (e.type === 'track_started') hwdec = !!e.hwdec;
        const [ico, label] = EV[e.type] || ['•', e.type];
        const name = e.title || (e.path ? e.path.split('/').pop() : '');
        const extra = e.type === 'track_ended' ? ` (${e.reason})` : e.type === 'track_error' ? `: ${e.message}` : '';
        const li = document.createElement('li');
        li.innerHTML = `<span class="time">${new Date().toLocaleTimeString().slice(0, 5)}</span><span class="ico">${ico}</span><span class="t" title="${esc(name + extra)}">${esc(label)} ${esc(name)}${esc(extra)}</span>`;
        if (first) list.appendChild(li); else list.insertBefore(li, list.firstChild);
      }
      if (first) list.innerHTML = Array.from(list.children).reverse().map((li) => li.outerHTML).join('');
      while (list.children.length > 100) list.lastChild.remove();
      if (r.next < since) since = 0; // mediad restarted
      first = false;
    } catch (e) {
      if (e.message === 'unauthorized') return;
      await new Promise((res) => setTimeout(res, 3000));
    }
  }
}

// ---------- logs drawer ----------
let logSource = null, logLines = [], logSvc = null, logFile = null;
function closeLogs() { if (logSource) logSource.close(); logSource = null; $('#drawer').classList.add('hidden'); }
$('#drawerClose').onclick = closeLogs;
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') { closeLogs(); $('#modalCancel').click(); } });
async function openLogs(svc, file) {
  logSvc = svc; logFile = file || svc.logs[0];
  $('#drawer').classList.remove('hidden');
  $('#drawerTitle').textContent = `${svc.title} logs`;
  $('#drawerFiles').innerHTML = svc.logs.map((f) => `<button class="tab ${f === logFile ? 'active' : ''}" data-f="${esc(f)}">${esc(f)}</button>`).join('');
  $('#logBody').textContent = 'Loading…';
  const r = await api(`/api/logs/${svc.name}?file=${encodeURIComponent(logFile)}&lines=400`);
  logLines = r.lines;
  $('#logInfo').textContent = `${r.sizeMb} MB`;
  renderLogs(true);
  follow();
}
$('#drawerFiles').onclick = (e) => { const b = e.target.closest('[data-f]'); if (b) openLogs(logSvc, b.dataset.f); };
function follow() {
  if (logSource) logSource.close();
  logSource = null;
  if (!$('#logFollow').checked || !logSvc) return;
  logSource = new EventSource(`/api/logs/${logSvc.name}/follow?file=${encodeURIComponent(logFile)}`);
  logSource.addEventListener('line', (e) => { logLines.push(e.data); if (logLines.length > 5000) logLines.splice(0, logLines.length - 5000); renderLogs(false); });
}
$('#logFollow').onchange = follow;
$('#logFilter').oninput = () => renderLogs(true);
$('#logErrors').onchange = () => renderLogs(true);
function renderLogs(jump) {
  const body = $('#logBody');
  const atBottom = body.scrollHeight - body.scrollTop - body.clientHeight < 40;
  const f = $('#logFilter').value.toLowerCase(), errs = $('#logErrors').checked;
  const out = [];
  for (const l of logLines) {
    const low = l.toLowerCase();
    const isErr = / error |exception|panic|^error|failed/i.test(l), isWarn = / warn /i.test(l);
    if (errs && !isErr && !isWarn) continue;
    if (f && !low.includes(f)) continue;
    let h = esc(l);
    if (f) h = h.replace(new RegExp(f.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'gi'), (m) => `<span class="h">${m}</span>`);
    out.push(`<span class="${isErr ? 'e' : isWarn ? 'w' : l.startsWith('event ') ? 'ev' : ''}">${h}</span>`);
  }
  body.innerHTML = out.join('\n');
  if (jump || atBottom) body.scrollTop = body.scrollHeight;
}

// ---------- boot ----------
function every(fn, ms) { fn().catch(() => {}); timers.push(setInterval(() => fn().catch(() => {}), ms)); }
async function start() {
  $('#app').classList.remove('hidden');
  every(refreshServices, 5000);
  every(refreshHost, 5000);
  every(refreshStream, 1000);
  every(refreshUpcoming, 10000);
  eventLoop();
}
api('/api/system').then(() => start()).catch(() => {});
