const $ = id => document.getElementById(id);
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' }[c]));

async function api(url, method = 'GET', body) {
  const r = await fetch('/api' + url, {
    method, headers: body ? {'Content-Type':'application/json'} : {},
    body: body ? JSON.stringify(body) : undefined
  });
  if (r.status === 401) { showAuth(); const e = new Error('Please log in again.'); e.silent = true; throw e; }
  if (!r.ok) {
    let m = 'Request failed (' + r.status + ')';
    try { m = (await r.json()).message || m; } catch (e) {}
    throw new Error(m);
  }
  return r.status === 204 ? null : r.json();
}

/* ---------------- Warning box (replaces alert / confirm) ---------------- */
let warnDone = null;
function showWarn(msg, { confirm = false, okText = 'OK' } = {}) {
  return new Promise(resolve => {
    $('warnMsg').textContent = msg;
    $('warnCancel').style.display = confirm ? '' : 'none';
    $('warnOk').textContent = okText;
    $('warnWrap').style.display = 'flex';
    warnDone = v => { $('warnWrap').style.display = 'none'; warnDone = null; resolve(v); };
    $('warnOk').focus();
  });
}
const warn = m => showWarn(m);
const confirmBox = (m, okText = 'Delete') => showWarn(m, { confirm:true, okText });
$('warnOk').onclick = () => warnDone && warnDone(true);
$('warnCancel').onclick = $('warnX').onclick = () => warnDone && warnDone(false);
$('warnWrap').onclick = e => { if (e.target === $('warnWrap') && warnDone) warnDone(false); };

/* Run an async function; show failures in the warning box. */
const guard = fn => async (...a) => {
  try { return await fn(...a); } catch (e) { if (!e.silent) await warn(e.message); }
};
function busy(btn, fn) {
  return async () => { btn.disabled = true; try { await fn(); } catch (e) { if (!e.silent) await warn(e.message); } finally { btn.disabled = false; } };
}

/* ---------------- Accounts ---------------- */
let registerMode = false;
function setMode(reg) {
  registerMode = reg;
  $('aEmail').style.display = reg ? 'block' : 'none';
  $('authGo').textContent = reg ? 'Create account' : 'Log in';
  $('aPass').autocomplete = reg ? 'new-password' : 'current-password';
  $('tabLogin').className = reg ? 'ghost' : ''; $('tabRegister').className = reg ? '' : 'ghost';
  $('authErr').textContent = '';
}
$('tabLogin').onclick = () => setMode(false);
$('tabRegister').onclick = () => setMode(true);

async function login(username, password) {
  const r = await fetch('/api/auth/login', { method:'POST',
    headers:{'Content-Type':'application/x-www-form-urlencoded'},
    body:new URLSearchParams({ username, password }) });
  return r.ok;
}
$('authCard').onsubmit = async e => {
  e.preventDefault();
  const username = $('aUser').value.trim(), password = $('aPass').value;
  $('authErr').textContent = '';
  try {
    if (registerMode) {
      const r = await fetch('/api/auth/register', { method:'POST', headers:{'Content-Type':'application/json'},
        body:JSON.stringify({ email:$('aEmail').value, username, password }) });
      if (!r.ok) { $('authErr').textContent = (await r.json()).message || 'Could not create account.'; return; }
    }
    if (!(await login(username, password))) { $('authErr').textContent = 'Wrong username or password.'; return; }
    $('aPass').value = '';
    await boot();
  } catch (err) { $('authErr').textContent = err.message; }
};
function showAuth() {
  $('auth').style.display = 'flex'; $('app').style.display = 'none';
  if (sessionStorage.getItem('switchUser')) {
    sessionStorage.removeItem('switchUser'); setMode(false); $('aUser').value = ''; $('aUser').focus();
  }
}

/* Username opens a small menu: switch user / log out. */
$('uname').onclick = e => { e.stopPropagation(); $('userMenu').classList.toggle('show'); };
document.addEventListener('click', () => $('userMenu').classList.remove('show'));
async function signOut(switching) {
  await fetch('/api/auth/logout', { method:'POST' });
  if (switching) sessionStorage.setItem('switchUser', '1');
  location.reload();
}
$('logout').onclick = () => signOut(false);
$('switchUser').onclick = () => signOut(true);

/* ---------------- Network ---------------- */
const MIN_SCALE = 0.3, DEFAULT_SCALE = 1;
const nodes = new vis.DataSet(), edges = new vis.DataSet();
const network = new vis.Network($('network'), { nodes, edges }, {
  nodes: {
    shape:'box', margin:12, widthConstraint:{ maximum:170 }, shapeProperties:{ borderRadius:14 }, borderWidth:2,
    font:{ size:15 }
  },
  edges: { width:2, smooth:true, selectionWidth:3 },
  physics: false,                 // rigid: nothing drifts, nodes only move when the user drags them
  interaction: { hover:true, dragNodes:true }
});

function themeOptions(theme) {
  const d = theme === 'dark';
  return { nodes: {
    color: { background: d ? '#171a2b' : '#ffffff', border: d ? '#5a63a8' : '#8d97ff',
      highlight: { background: d ? '#232846' : '#eceeff', border:'#a07cff' },
      hover: { background: d ? '#1e2340' : '#f3f4ff', border:'#7c8cff' } },
    font: { color: d ? '#ffffff' : '#1b1e33' } } };
}

let me = null, view = 'home', spaceId = null, spacesList = [];
let types = [], typesById = {}, activeTypeId = null;
let thoughtsById = {}, openId = null, connectFrom = null, edgeSel = null;

const PIN_SVG = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 3h6l-1 6 3 3v2H7v-2l3-3-1-6zM12 14v7" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/></svg>';

/* ---------------- Theme (saved per account) ---------------- */
function applyTheme(theme) {
  document.documentElement.dataset.theme = theme;
  $('themeSwitch').checked = theme === 'dark';
  network.setOptions(themeOptions(theme));
}
$('themeSwitch').onchange = async e => {
  const theme = e.target.checked ? 'dark' : 'light';
  applyTheme(theme);
  try { await api('/me/theme', 'PUT', { theme }); } catch (err) {}
};

/* ---------------- Thoughts on the canvas ---------------- */
const fixedOpt = t => (t.pinned && !network.getSelectedNodes().includes(t.id)) ? { x:true, y:true } : { x:false, y:false };

const nodeOf = t => {
  thoughtsById[t.id] = t;
  const n = { id:t.id, label:t.title || t.text.slice(0, 24), fixed:fixedOpt(t) };
  if (t.x != null && t.y != null) { n.x = t.x; n.y = t.y; }
  return n;
};
/* A pinned thought stays put unless it is selected. */
function syncFixed() {
  nodes.update(Object.values(thoughtsById).map(t => ({ id:t.id, fixed:fixedOpt(t) })));
}
const edgeOf = l => {
  const ty = typesById[l.typeId] || { color:'#888', name:'', dotted:false };
  return { id:l.id, from:l.fromId, to:l.toId, typeId:l.typeId,
    color:{ color:ty.color, highlight:ty.color, hover:ty.color },
    dashes: ty.dotted ? [2, 7] : false,
    title: ty.name + (l.label ? ': ' + l.label : '') };
};

/* Find an empty spot (spiral outward from c) so thoughts never land on top of each other. */
function freeSpot(c, pts) {
  for (let i = 0; i < 3000; i++) {
    const a = i * 2.39996, r = 70 * Math.sqrt(i);
    const x = c.x + r * Math.cos(a), y = c.y + r * Math.sin(a);
    if (!pts.some(p => Math.abs(p.x - x) < 210 && Math.abs(p.y - y) < 90)) return { x, y };
  }
  return c;
}
function placeMissing(ts) {
  const placed = ts.filter(t => t.x != null && t.y != null);
  ts.filter(t => t.x == null || t.y == null).forEach(t => {
    const s = freeSpot({ x:0, y:0 }, placed);
    t.x = s.x; t.y = s.y; placed.push(t);
    api('/thoughts/' + t.id, 'PUT', { x:s.x, y:s.y }).catch(() => {});
  });
}

/* ---------------- Connection types ---------------- */
async function loadTypes() {
  types = await api('/types'); typesById = {}; types.forEach(t => typesById[t.id] = t);
  activeTypeId = activeTypeId && typesById[activeTypeId] ? activeTypeId : (types[0] && types[0].id);
  renderTypes();
}
function renderTypes() {
  $('types').innerHTML = '';
  types.forEach(t => {
    const row = document.createElement('div');
    row.className = 'type' + (t.id === activeTypeId ? ' active' : '');
    row.title = t.description || '';
    row.innerHTML = `<span class="swatch" style="border-top-style:${t.dotted ? 'dotted' : 'solid'};border-color:${esc(t.color)}"></span>
      <span class="name">${esc(t.name)}</span>
      <input type="color" value="${esc(t.color)}" title="Color">
      <label title="Dotted line"><input type="checkbox" ${t.dotted ? 'checked' : ''}>dot</label>`;
    row.onclick = e => { if (e.target.tagName !== 'INPUT') { activeTypeId = t.id; renderTypes(); } };
    row.querySelector('input[type=color]').onchange = e => guard(saveType)(t.id, { color:e.target.value });
    row.querySelector('input[type=checkbox]').onchange = e => guard(saveType)(t.id, { dotted:e.target.checked });
    $('types').appendChild(row);
  });
  $('edgeType').innerHTML = types.map(t => `<option value="${t.id}">${esc(t.name)}</option>`).join('');
}
async function saveType(id, patch) {
  const t = await api('/types/' + id, 'PUT', patch);
  typesById[id] = t; types = types.map(x => x.id === id ? t : x); renderTypes();
  edges.update(edges.get().filter(e => e.typeId === id).map(e => edgeOf({ id:e.id, fromId:e.from, toId:e.to, typeId:id })));
}
$('ntAdd').onclick = busy($('ntAdd'), async () => {
  const name = $('ntName').value.trim();
  if (!name) { await warn('Give the new connection a name first.'); return; }
  const t = await api('/types', 'POST', { name, color:$('ntColor').value, dotted:$('ntDotted').checked });
  types.push(t); typesById[t.id] = t; activeTypeId = t.id;
  $('ntName').value = ''; $('ntDotted').checked = false; renderTypes();
});
$('ntName').onkeydown = e => { if (e.key === 'Enter') $('ntAdd').click(); };

/* ---------------- Home: idea spaces ---------------- */
const TILE_GRADS = [
  'linear-gradient(135deg,#4c5cff,#8a5cf5)', 'linear-gradient(135deg,#3d7bff,#6a5cff)',
  'linear-gradient(135deg,#6a5cff,#b05cf5)', 'linear-gradient(135deg,#3a63e8,#9a5cf5)'
];
async function loadHome() {
  spacesList = await api('/spaces');
  const box = $('tiles'); box.innerHTML = '';
  spacesList.forEach(s => box.appendChild(tileEl(s)));
  const add = document.createElement('div'); add.className = 'tile';
  add.innerHTML = '<div class="sq add"><span class="cnt">+</span><span class="cap">New space</span></div><div class="muted" style="text-align:center">&nbsp;</div>';
  add.firstChild.onclick = guard(async () => {
    const s = await api('/spaces', 'POST', { name:'Untitled space', description:'' });
    await loadHome();
    const inp = document.querySelector(`[data-sid="${s.id}"] .tname`);
    if (inp) { inp.focus(); inp.select(); }
  });
  box.appendChild(add);
}
function tileEl(s) {
  const t = document.createElement('div'); t.className = 'tile'; t.dataset.sid = s.id;
  const sq = document.createElement('div'); sq.className = 'sq';
  sq.style.background = TILE_GRADS[s.id % TILE_GRADS.length];
  sq.innerHTML = `<span class="cnt">${s.thoughtCount}</span><span class="cap">${s.thoughtCount === 1 ? 'thought' : 'thoughts'}</span>
    <button class="tdel" title="Delete space">✕</button>`;
  sq.onclick = guard(() => openSpace(s.id));
  sq.querySelector('.tdel').onclick = guard(async e => {
    e.stopPropagation();
    if (!(await confirmBox(`Delete “${s.name}” and all of its thoughts?`))) return;
    await api('/spaces/' + s.id, 'DELETE'); await loadHome();
  });
  const nm = document.createElement('input'); nm.type = 'text'; nm.className = 'tname'; nm.value = s.name; nm.maxLength = 60;
  nm.title = 'Click to rename';
  nm.onkeydown = e => { if (e.key === 'Enter') nm.blur(); };
  nm.onchange = guard(async () => {
    const name = nm.value.trim() || s.name;
    const u = await api('/spaces/' + s.id, 'PUT', { name }); s.name = u.name; nm.value = u.name;
  });
  t.append(sq, nm);
  return t;
}
function goHome() {
  view = 'home'; spaceId = null; closeModal(); hidePin();
  $('workspace').style.display = 'none'; $('home').style.display = 'block';
  document.body.classList.remove('in-space');
  return loadHome();
}
async function openSpace(id) {
  spacesList = await api('/spaces');
  spaceId = id; view = 'space';
  const s = spacesList.find(x => x.id == id);
  $('spaceName').textContent = s ? s.name : '';
  $('home').style.display = 'none'; $('workspace').style.display = 'flex';
  network.setSize('100%', '100%'); network.redraw();
  await loadGraph();
}
$('homeBtn').onclick = guard(goHome);

/* ---------------- Workspace ---------------- */
async function loadGraph() {
  nodes.clear(); edges.clear(); thoughtsById = {}; openId = null; connectFrom = null; setHint(null); hideEdgebar(); hidePin();
  const [ts, ls] = await Promise.all([api('/thoughts?spaceId=' + spaceId), api('/links?spaceId=' + spaceId)]);
  placeMissing(ts);
  nodes.add(ts.map(nodeOf)); edges.add(ls.map(edgeOf));
  renderPins(); focusSpace();
}
/* Same visible size every time a space opens, centered on the thoughts. The user zooms from there. */
function focusSpace() {
  const ps = Object.values(thoughtsById).filter(t => t.x != null);
  let c = { x:0, y:0 };
  if (ps.length) {
    const xs = ps.map(t => t.x), ys = ps.map(t => t.y);
    c = { x:(Math.min(...xs) + Math.max(...xs)) / 2, y:(Math.min(...ys) + Math.max(...ys)) / 2 };
  }
  network.moveTo({ position:c, scale:DEFAULT_SCALE, animation:false });
  syncZoom();
}

function setHint(msg) { $('hint').style.display = msg ? 'flex' : 'none'; $('hint').textContent = msg || ''; }
function hideEdgebar() { $('edgebar').style.display = 'none'; }

/* ---- Zoom ---- */
function syncZoom() {
  const s = network.getScale();
  $('zRange').value = Math.min(Math.max(s, MIN_SCALE), 3);
  $('zPct').textContent = Math.round(s * 100) + '%';
}
function setScale(s) {
  network.moveTo({ scale:Math.max(MIN_SCALE, s), animation:{ duration:150, easingFunction:'easeInOutQuad' } });
  setTimeout(syncZoom, 190);
}
$('zIn').onclick = () => setScale(network.getScale() * 1.25);
$('zOut').onclick = () => setScale(network.getScale() / 1.25);
$('zRange').oninput = e => { network.moveTo({ scale:Number(e.target.value), animation:false }); syncZoom(); };
network.on('zoom', p => {
  if (p.scale < MIN_SCALE) network.moveTo({ scale:MIN_SCALE, animation:false });   // minimum only; no maximum
  syncZoom(); hidePin();
});

/* ---- Pins ---- */
function pinnedThoughts() { return Object.values(thoughtsById).filter(t => t.pinned); }
function renderPins() {
  const list = pinnedThoughts();
  $('pinCount').textContent = list.length ? '(' + list.length + ')' : '';
  $('pinList').innerHTML = '';
  if (!list.length) { const m = document.createElement('div'); m.className = 'muted'; m.textContent = 'No thought is pinned.'; $('pinList').appendChild(m); return; }
  list.forEach(t => {
    const d = document.createElement('div'); d.className = 'pinitem'; d.textContent = t.title || t.text.slice(0, 24);
    d.onclick = () => network.focus(t.id, { scale:network.getScale(), animation:{ duration:400 } });
    $('pinList').appendChild(d);
  });
}
const togglePin = guard(async id => {
  const t = thoughtsById[id]; if (!t) return;
  const upd = await api('/thoughts/' + id, 'PUT', { pinned:!t.pinned });
  nodes.update(nodeOf(upd)); renderPins(); network.redraw();
  if (hoverId === id) showPin(id);
  if (openId === id) updateModalPin();
});

/* Red pin at the top-right corner while hovering a thought. */
let hoverId = null, hideTimer = null;
function showPin(id) {
  if (!thoughtsById[id]) return;
  clearTimeout(hideTimer); hoverId = id;
  const b = network.getBoundingBox(id), d = network.canvasToDOM({ x:b.right, y:b.top });
  const el = $('pinHover'), pinned = thoughtsById[id].pinned;
  el.innerHTML = PIN_SVG; el.classList.toggle('on', !!pinned); el.title = pinned ? 'Unpin this thought' : 'Pin this thought';
  el.style.left = (d.x - 14) + 'px'; el.style.top = (d.y - 14) + 'px'; el.style.display = 'flex';
}
function hidePin() { clearTimeout(hideTimer); hoverId = null; $('pinHover').style.display = 'none'; }
function schedHidePin() { clearTimeout(hideTimer); hideTimer = setTimeout(hidePin, 250); }
network.on('hoverNode', p => showPin(p.node));
network.on('blurNode', schedHidePin);
network.on('dragStart', hidePin);
$('pinHover').onmouseenter = () => clearTimeout(hideTimer);
$('pinHover').onmouseleave = schedHidePin;
$('pinHover').onclick = () => hoverId != null && togglePin(hoverId);

/* Pin drawn at the top center of each pinned thought; clicking it unpins. */
const pinPos = id => { const b = network.getBoundingBox(id); return { x:(b.left + b.right) / 2, y:b.top - 10 }; };
network.on('afterDrawing', ctx => {
  pinnedThoughts().forEach(t => {
    if (!nodes.get(t.id)) return;
    const p = pinPos(t.id);
    ctx.save();
    ctx.strokeStyle = '#c9ccd8'; ctx.lineWidth = 1.8; ctx.beginPath(); ctx.moveTo(p.x, p.y + 4); ctx.lineTo(p.x, p.y + 14); ctx.stroke();
    ctx.fillStyle = '#ff3b52'; ctx.strokeStyle = '#8a0f20'; ctx.lineWidth = 1;
    ctx.beginPath(); ctx.arc(p.x, p.y, 7, 0, Math.PI * 2); ctx.fill(); ctx.stroke();
    ctx.fillStyle = 'rgba(255,255,255,.55)'; ctx.beginPath(); ctx.arc(p.x - 2.2, p.y - 2.4, 2, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
  });
});
function pinHit(pt) {
  const t = pinnedThoughts().find(t => { if (!nodes.get(t.id)) return false; const p = pinPos(t.id); return Math.hypot(pt.x - p.x, pt.y - p.y) < 10; });
  return t ? t.id : null;
}

/* ---- Clicks and dragging ---- */
network.on('click', async p => {
  hidePin();
  if (!connectFrom) {
    const hit = pinHit(p.pointer.canvas);
    if (hit != null) { await togglePin(hit); return; }
  }
  if (connectFrom && p.nodes.length) {
    const to = p.nodes[0];
    if (to !== connectFrom) {
      try { edges.add(edgeOf(await api('/links', 'POST', { fromId:connectFrom, toId:to, typeId:activeTypeId }))); }
      catch (e) { if (!e.silent) await warn(e.message); }
    }
    connectFrom = null; setHint(null); syncFixed(); return;
  }
  connectFrom = null; setHint(null);
  if (p.nodes.length) { hideEdgebar(); openThought(p.nodes[0]); }
  else if (p.edges.length) { edgeSel = p.edges[0]; $('edgeType').value = edges.get(edgeSel).typeId; $('edgebar').style.display = 'flex'; }
  else hideEdgebar();
  syncFixed();
});
network.on('deselectNode', syncFixed);
network.on('dragEnd', p => {
  p.nodes.forEach(id => {
    const pos = network.getPositions([id])[id];
    if (!thoughtsById[id]) return;
    thoughtsById[id].x = pos.x; thoughtsById[id].y = pos.y;
    api('/thoughts/' + id, 'PUT', { x:pos.x, y:pos.y }).catch(() => {});
  });
  syncFixed();
});

$('edgeType').onchange = guard(async e => {
  const l = await api('/links/' + edgeSel, 'PUT', { typeId:Number(e.target.value) });
  edges.update(edgeOf(l));
});
$('edgeDel').onclick = busy($('edgeDel'), async () => { await api('/links/' + edgeSel, 'DELETE'); edges.remove(edgeSel); hideEdgebar(); });
$('edgeClose').onclick = hideEdgebar;

/* ---- Expanded thought ---- */
function updateModalPin() {
  const pinned = !!(thoughtsById[openId] && thoughtsById[openId].pinned);
  $('mPin').innerHTML = PIN_SVG + (pinned ? 'Unpin' : 'Pin');
  $('mPin').classList.toggle('pinned', pinned);
}
function openThought(id) {
  openId = id; const t = thoughtsById[id];
  $('mTitle').value = t.title || ''; $('mCategory').value = t.category || ''; $('mText').value = t.text;
  $('mHistory').style.display = 'none';
  updateModalPin();
  $('modalWrap').style.display = 'flex';
}
function closeModal() { $('modalWrap').style.display = 'none'; $('mHistory').style.display = 'none'; }
$('mClose').onclick = closeModal;
$('modalWrap').onclick = e => { if (e.target === $('modalWrap')) closeModal(); };
$('mSave').onclick = busy($('mSave'), async () => {
  const t = await api('/thoughts/' + openId, 'PUT', { title:$('mTitle').value, text:$('mText').value, category:$('mCategory').value });
  nodes.update(nodeOf(t)); closeModal();
});
$('mPin').onclick = () => togglePin(openId);
$('mDelete').onclick = busy($('mDelete'), async () => {
  if (!(await confirmBox('Delete this thought?'))) return;
  await api('/thoughts/' + openId, 'DELETE');
  edges.get().filter(e => e.from === openId || e.to === openId).forEach(e => edges.remove(e.id));
  nodes.remove(openId); delete thoughtsById[openId]; openId = null; closeModal(); renderPins();
});
$('mConnect').onclick = () => {
  connectFrom = openId; closeModal();
  const t = typesById[activeTypeId];
  setHint(`Click the thought to connect with${t ? ' — type: ' + t.name : ''}`);
};
$('mHist').onclick = guard(async () => {
  const box = $('mHistory');
  if (box.style.display === 'flex') { box.style.display = 'none'; return; }
  const vs = await api('/thoughts/' + openId + '/history');
  box.innerHTML = '';
  if (!vs.length) { const m = document.createElement('div'); m.className = 'muted'; m.textContent = 'No history yet. It starts building from now on, every time you edit this thought.'; box.appendChild(m); }
  vs.forEach(v => {
    const d = document.createElement('div'); d.className = 'ver';
    const meta = document.createElement('div'); meta.className = 'vmeta'; meta.textContent = v.note + ' · ' + new Date(v.createdAt).toLocaleString();
    const ti = document.createElement('div'); ti.className = 'vtitle'; ti.textContent = v.title || '';
    const tx = document.createElement('div'); tx.className = 'vtext'; tx.textContent = v.text || '';
    const use = document.createElement('button'); use.className = 'ghost'; use.textContent = 'Use this version';
    use.onclick = () => { $('mTitle').value = v.title || ''; $('mText').value = v.text || ''; };
    d.append(meta, ti, tx, use); box.appendChild(d);
  });
  box.style.display = 'flex';
});

/* ---- Sidebar: new thought ---- */
$('addThought').onclick = busy($('addThought'), async () => {
  const text = $('thoughtText').value.trim(); if (!text) return;
  const t = await api('/thoughts', 'POST', { text, spaceId:Number(spaceId) });
  $('thoughtText').value = '';
  const pts = Object.values(thoughtsById).filter(x => x.x != null);
  const s = freeSpot(network.getViewPosition(), pts);
  t.x = s.x; t.y = s.y;
  api('/thoughts/' + t.id, 'PUT', { x:s.x, y:s.y }).catch(() => {});
  nodes.add(nodeOf(t));
});
$('thoughtText').onkeydown = e => {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); $('addThought').click(); }
};
$('toggleSide').onclick = () => $('sidebar').classList.toggle('collapsed');

document.addEventListener('keydown', e => {
  if (e.key !== 'Escape') return;
  if (warnDone) warnDone(false); else if ($('modalWrap').style.display === 'flex') closeModal();
});

/* ---------------- Psy ---------------- */
const psyHistory = [];
let psyGreeted = false;
function psyState(s) {
  $('psy').className = s;
  document.body.classList.toggle('psy-open', s === 'open');
  if (s !== 'min') $('psyInput').focus();
}
$('psyHead').onclick = e => {
  if (e.target.closest('button')) return;
  const c = $('psy').className;
  if (c === 'min') psyState('open'); else if (c === 'open') psyState('min');
};
$('psyFull').onclick = () => psyState($('psy').className === 'full' ? 'open' : 'full');
$('psyMin').onclick = () => psyState('min');

function addMsg(cls, text) {
  const d = document.createElement('div'); d.className = 'msg ' + cls; d.textContent = text;
  $('psyLog').appendChild(d); $('psyLog').scrollTop = 1e9; return d;
}
function actionRow(a, container) {
  const row = document.createElement('div'); row.className = 'act';
  const label = document.createElement('span');
  const btn = document.createElement('button');
  if (a.kind === 'link') {
    label.textContent = `${a.fromTitle} → ${a.toTitle} (${a.typeName}): ${a.reason}`; btn.textContent = 'Add';
    btn.apply = async () => {
      const l = await api('/links', 'POST', { fromId:a.fromId, toId:a.toId, typeId:a.typeId, label:a.reason, aiSuggested:true });
      if (view === 'space' && nodes.get(a.fromId) && nodes.get(a.toId)) edges.add(edgeOf(l));
    };
  } else {
    label.textContent = `${a.fromTitle} → ${a.category}`; btn.textContent = 'Apply';
    btn.apply = async () => {
      const t = await api('/thoughts/' + a.thoughtId, 'PUT', { category:a.category });
      if (thoughtsById[a.thoughtId]) thoughtsById[a.thoughtId].category = t.category;
    };
  }
  btn.onclick = async () => {
    btn.disabled = true;
    try { await btn.apply(); btn.textContent = 'Added'; row.style.opacity = .6; }
    catch (e) { btn.disabled = false; if (!e.silent) await warn(e.message); }
  };
  row.append(label, btn); container.appendChild(row);
  return btn;
}
function renderPsyReply(r) {
  addMsg('ai', r.reply);
  if (r.actions && r.actions.length) {
    const box = document.createElement('div'); box.className = 'acts';
    const btns = r.actions.map(a => actionRow(a, box));
    if (btns.length > 1) {
      const all = document.createElement('button'); all.className = 'ghost'; all.textContent = 'Add all';
      all.onclick = async () => { for (const b of btns) if (!b.disabled) await b.onclick(); };
      box.appendChild(all);
    }
    $('psyLog').appendChild(box);
  }
  if (r.openSpaceId) {
    const box = document.createElement('div'); box.className = 'acts';
    const b = document.createElement('button'); b.textContent = `Open “${r.openSpaceName}”`;
    b.onclick = guard(async () => { psyState('min'); await openSpace(r.openSpaceId); });
    box.appendChild(b); $('psyLog').appendChild(box);
  }
  if (r.spacesChanged && view === 'home') loadHome();
  $('psyLog').scrollTop = 1e9;
}
async function psySend() {
  const q = $('psyInput').value.trim(); if (!q) return;
  $('psyInput').value = ''; addMsg('me', q);
  const wait = addMsg('ai pending', 'Psy is thinking…');
  $('psySend').disabled = true;
  try {
    const r = await api('/psy', 'POST', { message:q, spaceId: view === 'space' ? Number(spaceId) : null, history:psyHistory.slice(-8) });
    wait.remove(); renderPsyReply(r);
    psyHistory.push({ role:'user', text:q }, { role:'psy', text:r.reply });
  } catch (e) { wait.textContent = e.silent ? 'Please log in again.' : e.message; wait.classList.remove('pending'); }
  finally { $('psySend').disabled = false; $('psyInput').focus(); }
}
$('psySend').onclick = psySend;
$('psyInput').onkeydown = e => { if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); psySend(); } };

function greetPsy() {
  if (psyGreeted) return; psyGreeted = true;
  addMsg('ai', "Hi, I'm Psy! What are you thinking about today? Walk me through your amazing ideas and let's start explore them together! (^-^)/");
}

/* ---------------- Boot ---------------- */
async function boot() {
  const r = await fetch('/api/me');
  if (r.status !== 200) { showAuth(); return; }
  me = await r.json();
  applyTheme(me.theme || 'dark'); $('uname').textContent = me.username;
  $('auth').style.display = 'none'; $('app').style.display = 'block';
  await loadTypes(); await goHome(); greetPsy();
}
boot();