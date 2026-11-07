const $ = id => document.getElementById(id);

async function api(url, method = 'GET', body) {
  const r = await fetch('/api' + url, {
    method, headers: body ? {'Content-Type':'application/json'} : {},
    body: body ? JSON.stringify(body) : undefined
  });
  if (r.status === 401) { showAuth(); throw new Error('Please log in again.'); }
  if (!r.ok) {
    let m = 'Request failed (' + r.status + ')';
    try { m = (await r.json()).message || m; } catch (e) {}
    throw new Error(m);
  }
  return r.status === 204 ? null : r.json();
}
function busy(btn, fn) {
  return async () => { btn.disabled = true; try { await fn(); } catch (e) { alert(e.message); } finally { btn.disabled = false; } };
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
function showAuth() { $('auth').style.display = 'flex'; }

$('logout').onclick = async () => { await fetch('/api/auth/logout', { method:'POST' }); location.reload(); };

/* ---------------- Theme (saved per account) ---------------- */
function applyTheme(theme) {
  document.documentElement.dataset.theme = theme;
  $('themeMoon').classList.toggle('on', theme === 'dark');
  $('themeSun').classList.toggle('on', theme === 'light');
}
async function setTheme(theme) { applyTheme(theme); try { await api('/me/theme', 'PUT', { theme }); } catch (e) {} }
$('themeMoon').onclick = () => setTheme('dark');
$('themeSun').onclick = () => setTheme('light');

/* ---------------- Network ---------------- */
const nodes = new vis.DataSet(), edges = new vis.DataSet();
const network = new vis.Network($('network'), { nodes, edges }, {
  nodes: {
    shape:'box', margin:12, widthConstraint:{ maximum:170 }, shapeProperties:{ borderRadius:14 }, borderWidth:2,
    color:{ background:'#171a2b', border:'#4a5280', highlight:{ background:'#232846', border:'#7c8cff' }, hover:{ background:'#1e2340', border:'#7c8cff' } },
    font:{ color:'#ffffff', size:15 }
  },
  edges: { width:2, smooth:true, selectionWidth:3 },
  physics: { stabilization:true, barnesHut:{ springLength:170, avoidOverlap:0.4 } },
  interaction: { hover:true }
});

let me = null, spaceId = null, types = [], typesById = {}, activeTypeId = null;
let thoughtsById = {}, openId = null, connectFrom = null;

const nodeOf = t => {
  thoughtsById[t.id] = t;
  const n = { id:t.id, label:t.title || t.text.slice(0, 24) };
  if (t.x != null && t.y != null) { n.x = t.x; n.y = t.y; }
  return n;
};
const edgeOf = l => {
  const ty = typesById[l.typeId] || { color:'#888', name:'', dotted:false };
  return { id:l.id, from:l.fromId, to:l.toId, typeId:l.typeId,
    color:{ color:ty.color, highlight:ty.color, hover:ty.color },
    dashes: ty.dotted ? [2, 7] : false,
    title: ty.name + (l.label ? ': ' + l.label : '') };
};

async function loadTypes() {
  types = await api('/types'); typesById = {}; types.forEach(t => typesById[t.id] = t);
  activeTypeId = activeTypeId && typesById[activeTypeId] ? activeTypeId : types[0].id;
  renderTypes();
}
function renderTypes() {
  $('types').innerHTML = '';
  types.forEach(t => {
    const row = document.createElement('div');
    row.className = 'type' + (t.id === activeTypeId ? ' active' : '');
    row.title = t.description;
    row.innerHTML = `<span class="swatch" style="border-top-style:${t.dotted ? 'dotted' : 'solid'};border-color:${t.color}"></span>
      <span class="name">${t.name}</span>
      <input type="color" value="${t.color}" title="Color">
      <label title="Dotted line"><input type="checkbox" ${t.dotted ? 'checked' : ''}>dot</label>`;
    row.onclick = e => { if (e.target.tagName !== 'INPUT') { activeTypeId = t.id; renderTypes(); } };
    row.querySelector('input[type=color]').onchange = e => saveType(t.id, { color:e.target.value });
    row.querySelector('input[type=checkbox]').onchange = e => saveType(t.id, { dotted:e.target.checked });
    $('types').appendChild(row);
  });
  $('edgeType').innerHTML = types.map(t => `<option value="${t.id}">${t.name}</option>`).join('');
}
async function saveType(id, patch) {
  const t = await api('/types/' + id, 'PUT', patch);
  typesById[id] = t; types = types.map(x => x.id === id ? t : x); renderTypes();
  edges.update(edges.get().filter(e => e.typeId === id).map(e => edgeOf({ id:e.id, fromId:e.from, toId:e.to, typeId:id })));
}

async function loadSpaces() {
  const spaces = await api('/spaces');
  $('spaceSelect').innerHTML = spaces.map(s => `<option value="${s.id}">${s.name}</option>`).join('');
  spaceId = spaceId && spaces.some(s => s.id == spaceId) ? spaceId : spaces[0].id;
  $('spaceSelect').value = spaceId;
  await loadGraph();
}
async function loadGraph() {
  nodes.clear(); edges.clear(); thoughtsById = {}; openId = null; connectFrom = null; setHint(null); hideEdgebar();
  const [ts, ls] = await Promise.all([api('/thoughts?spaceId=' + spaceId), api('/links?spaceId=' + spaceId)]);
  nodes.add(ts.map(nodeOf)); edges.add(ls.map(edgeOf));
  updateChatCtx();
}

/* ---------------- Interaction ---------------- */
function setHint(msg) { $('hint').style.display = msg ? 'flex' : 'none'; $('hint').textContent = msg || ''; }
function hideEdgebar() { $('edgebar').style.display = 'none'; }
let edgeSel = null;

network.on('click', async p => {
  if (connectFrom && p.nodes.length) {
    const to = p.nodes[0];
    if (to !== connectFrom) {
      try { edges.add(edgeOf(await api('/links', 'POST', { fromId:connectFrom, toId:to, typeId:activeTypeId }))); }
      catch (e) { alert(e.message); }
    }
    connectFrom = null; setHint(null); return;
  }
  connectFrom = null; setHint(null);
  if (p.nodes.length) { hideEdgebar(); openThought(p.nodes[0]); }
  else if (p.edges.length) { edgeSel = p.edges[0]; $('edgeType').value = edges.get(edgeSel).typeId; $('edgebar').style.display = 'flex'; }
  else hideEdgebar();
  updateChatCtx();
});
network.on('dragEnd', p => p.nodes.forEach(id => {
  const pos = network.getPositions([id])[id];
  thoughtsById[id].x = pos.x; thoughtsById[id].y = pos.y;
  api('/thoughts/' + id, 'PUT', { x:pos.x, y:pos.y }).catch(() => {});
}));

$('edgeType').onchange = async e => {
  const l = await api('/links/' + edgeSel, 'PUT', { typeId:Number(e.target.value) });
  edges.update(edgeOf(l));
};
$('edgeDel').onclick = busy($('edgeDel'), async () => { await api('/links/' + edgeSel, 'DELETE'); edges.remove(edgeSel); hideEdgebar(); });
$('edgeClose').onclick = hideEdgebar;

/* Expanded thought */
function openThought(id) {
  openId = id; const t = thoughtsById[id];
  $('mTitle').value = t.title || ''; $('mText').value = t.text;
  $('modalWrap').style.display = 'flex';
}
function closeModal() { $('modalWrap').style.display = 'none'; }
$('mClose').onclick = closeModal;
$('modalWrap').onclick = e => { if (e.target === $('modalWrap')) closeModal(); };
$('mSave').onclick = busy($('mSave'), async () => {
  const t = await api('/thoughts/' + openId, 'PUT', { title:$('mTitle').value, text:$('mText').value });
  nodes.update(nodeOf(t)); closeModal();
});
$('mDelete').onclick = busy($('mDelete'), async () => {
  if (!confirm('Delete this thought?')) return;
  await api('/thoughts/' + openId, 'DELETE');
  edges.get().filter(e => e.from === openId || e.to === openId).forEach(e => edges.remove(e.id));
  nodes.remove(openId); delete thoughtsById[openId]; openId = null; closeModal(); updateChatCtx();
});
$('mConnect').onclick = () => {
  connectFrom = openId; closeModal();
  setHint(`Click the thought to connect with — type: ${typesById[activeTypeId].name}`);
};

/* Sidebar actions */
$('spaceSelect').onchange = e => { spaceId = e.target.value; loadGraph(); };
$('addSpace').onclick = busy($('addSpace'), async () => {
  const name = $('newSpaceName').value.trim(); if (!name) return;
  const s = await api('/spaces', 'POST', { name, description:'' });
  $('newSpaceName').value = ''; spaceId = s.id; await loadSpaces();
});
$('delSpace').onclick = busy($('delSpace'), async () => {
  if (!confirm('Delete this space and all of its thoughts?')) return;
  await api('/spaces/' + spaceId, 'DELETE'); spaceId = null; await loadSpaces();
});
$('addThought').onclick = busy($('addThought'), async () => {
  const text = $('thoughtText').value.trim(); if (!text) return;
  const t = await api('/thoughts', 'POST', { text, spaceId:Number(spaceId) });
  $('thoughtText').value = ''; nodes.add(nodeOf(t)); updateChatCtx();
});
$('suggest').onclick = busy($('suggest'), async () => {
  const created = await api(`/spaces/${spaceId}/suggest-links`, 'POST');
  edges.add(created.map(edgeOf));
  if (!created.length) alert('No new connections suggested.');
});

/* Sidebar + chat collapse */
$('toggleSide').onclick = () => $('sidebar').classList.toggle('collapsed');
$('chatHead').onclick = () => {
  const c = $('chat').classList.toggle('collapsed'); $('chatCaret').textContent = c ? '▲' : '▼';
};

/* Gemini chat: uses the thought you last opened, otherwise the whole space (up to 30 thoughts). */
function chatContextIds() {
  if (openId && thoughtsById[openId]) return [openId];
  return Object.keys(thoughtsById).slice(0, 30).map(Number);
}
function updateChatCtx() {
  $('chatCtx').textContent = openId && thoughtsById[openId]
    ? `Context: “${thoughtsById[openId].title || 'selected thought'}”` : 'Context: this whole space';
}
function addMsg(cls, text) {
  const d = document.createElement('div'); d.className = 'msg ' + cls; d.textContent = text;
  $('chatLog').appendChild(d); $('chatLog').scrollTop = 1e9;
}
$('chatSend').onclick = busy($('chatSend'), async () => {
  const q = $('chatInput').value.trim(); if (!q) return;
  $('chatInput').value = ''; addMsg('me', q);
  const r = await api('/ask', 'POST', { question:q, thoughtIds:chatContextIds() });
  addMsg('ai', r.answer);
});
$('chatInput').onkeydown = e => { if (e.key === 'Enter') $('chatSend').click(); };

/* ---------------- Boot ---------------- */
async function boot() {
  const r = await fetch('/api/me');
  if (r.status !== 200) { showAuth(); return; }
  me = await r.json();
  applyTheme(me.theme); $('uname').textContent = me.username;
  $('auth').style.display = 'none';
  await loadTypes(); await loadSpaces();
}
boot();
