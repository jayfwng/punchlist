/* 펀치리스트 — 건물 하자 점검 앱 (WebView UI) */
'use strict';

// ======================================================================= 상수
const CATS = [
  { key: 'arch',   name: '건축',       summary: '건축',   title: 'PUNCH LIST  (건축부문)',   color: '#2563eb' },
  { key: 'mech',   name: '기계',       summary: '기계',   title: 'PUNCH LIST  (기계부문)',   color: '#059669' },
  { key: 'elec',   name: '전기',       summary: '전기',   title: 'PUNCH LIST  (전기부문)',   color: '#d97706' },
  { key: 'fire',   name: '소방',       summary: '소방',   title: 'PUNCH LIST  (소방부문)',   color: '#dc2626' },
  { key: 'elev',   name: '승강기',     summary: '승강기', title: 'PUNCH LIST  (승강기부문)', color: '#7c3aed' },
  { key: 'safety', name: '안전(공통)', summary: '안전',   title: 'PUNCH LIST  (안전부문)',   color: '#0891b2' },
];
const CAT = Object.fromEntries(CATS.map(c => [c.key, c]));

// 자주 쓰는 문구 (탭하면 문제점에 추가)
const PHRASES = {
  arch:   ['크랙 발생', '마감 불량', '누수 흔적', '오염', '파손', '들뜸', '줄눈 손상', '코킹 불량', '도장 불량', '단차 발생', '손잡이 불량', '구멍'],
  mech:   ['누수', '보온 불량', '소음 발생', '배관 표식 미설치', '방향 표시 미설치', '고정 불량', '동파 위험', '배수 불량', '필터 오염', '작동 불량'],
  elec:   ['마감 불량', '커버 미설치', '결선 불량', '접지 미설치', '점등 불량', '전선 정리 불량', '관통부 마감 불량', '표기 미부착', '콘센트 불량'],
  fire:   ['헤드 함몰', '헤드 마개 없음', '감지기 탈락', '피난등 꺼짐', '유도등 불량', '압력게이지 파손', '방화구획 미흡', '소화기 미비치', '센터 조절 필요'],
  elev:   ['PIT 누수', '배수펌프 작동 불량', '바닥 깨짐', '도어 마감 불량', '배선 정리 불량', '불필요 배선 제거 요망', '이음 소음'],
  safety: ['추락 위험', '난간 미설치', '걸림 위험', '점검구 설치 필요', '발빠짐 위험', '안전표지 미부착', '미끄럼 위험', '고정 불량'],
};

const N = window.Native || null; // 안드로이드 브리지

// ======================================================================= 데이터
let DB = { version: 1, projects: [], settings: { photoMode: 'with' } };

function loadDB() {
  try {
    const s = N ? N.loadData() : localStorage.getItem('pl_db');
    if (s) {
      const d = JSON.parse(s);
      if (d && Array.isArray(d.projects)) DB = Object.assign({ settings: {} }, d);
    }
  } catch (e) { console.error(e); }
  if (!DB.settings) DB.settings = {};
}
let saveTimer = null;
function saveDB() {
  clearTimeout(saveTimer);
  const s = JSON.stringify(DB);
  if (N) N.saveData(s); else localStorage.setItem('pl_db', s);
}

const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
const today = () => { const d = new Date(); return `${d.getFullYear()}-${p2(d.getMonth() + 1)}-${p2(d.getDate())}`; };
const p2 = n => String(n).padStart(2, '0');
const fmtTime = ms => { const d = new Date(ms); return `${d.getFullYear()}-${p2(d.getMonth() + 1)}-${p2(d.getDate())} ${p2(d.getHours())}:${p2(d.getMinutes())}`; };
const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));
const thumbUrl = id => `/thumbs/${id}.jpg`;
const photoUrl = id => `/photos/${id}.jpg`;

function getProject(id) { return DB.projects.find(p => p.id === id); }

/** 층 구성 → 층 목록 (위에서 아래 순) */
function floorsOf(p) {
  const c = p.floorCfg || {};
  const out = [];
  if (c.roof) out.push('옥상');
  for (let i = (c.r | 0); i >= 1; i--) out.push(`옥탑${i}층`);
  for (let i = (c.n | 0); i >= 1; i--) out.push(`${i}층`);
  for (let i = 1; i <= (c.b | 0); i++) out.push(`지하${i}층`);
  if (c.all) out.push('전층');
  if (c.ext) out.push('외부');
  (c.custom || []).forEach(f => { if (f && !out.includes(f)) out.push(f); });
  return out;
}
function elevUnits(p) {
  const n = (p.floorCfg && p.floorCfg.elev) | 0;
  return Array.from({ length: n }, (_, i) => `${i + 1}호기`);
}

function itemsOf(p, cat) {
  return p.items.filter(i => i.cat === cat).sort((a, b) => a.createdAt - b.createdAt);
}
function numberOf(p, item) { return itemsOf(p, item.cat).indexOf(item) + 1; }

// ======================================================================= 라우터
let stack = [];
let ctx = {}; // 현재 화면 상태
function go(name, params = {}, replace = false) {
  if (replace) stack.pop();
  stack.push({ name, params });
  ctx = {};
  render();
  window.scrollTo(0, 0);
}
function back() {
  if (closeTopModal()) return true;
  const cur = stack[stack.length - 1];
  if (cur && cur.name === 'item' && ctx.dirty) {
    confirmDialog('작성 중인 내용이 있습니다', '저장하지 않고 나갈까요?\n(찍은 사진도 삭제됩니다)', '나가기', true).then(ok => {
      if (ok) { discardDraftPhotos(); clearDraft(); stack.pop(); ctx = {}; render(); }
    });
    return true;
  }
  if (stack.length > 1) {
    if (cur.name === 'item') clearDraft();
    stack.pop(); ctx = {}; render(); return true;
  }
  return false;
}
window.onBack = back;

function render() {
  const cur = stack[stack.length - 1];
  const app = $('#app');
  const views = { home: vHome, setup: vSetup, project: vProject, list: vList, floor: vFloor, item: vItem, export: vExport, settings: vSettings };
  app.innerHTML = '';
  (views[cur.name] || vHome)(app, cur.params);
}

// ======================================================================= 공통 UI
const ICON = {
  back: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M15 18l-6-6 6-6"/></svg>',
  edit: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 013 3L7 19l-4 1 1-4z"/></svg>',
  share: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 12v8a2 2 0 002 2h12a2 2 0 002-2v-8"/><path d="M16 6l-4-4-4 4"/><path d="M12 2v13"/></svg>',
  trash: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 6h18"/><path d="M8 6V4h8v2"/><path d="M19 6l-1 14H6L5 6"/></svg>',
  gear: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 00.3 1.8l.1.1a2 2 0 11-2.8 2.8l-.1-.1a1.7 1.7 0 00-1.8-.3 1.7 1.7 0 00-1 1.5V21a2 2 0 11-4 0v-.1a1.7 1.7 0 00-1.1-1.5 1.7 1.7 0 00-1.8.3l-.1.1a2 2 0 11-2.8-2.8l.1-.1a1.7 1.7 0 00.3-1.8 1.7 1.7 0 00-1.5-1H3a2 2 0 110-4h.1a1.7 1.7 0 001.5-1.1 1.7 1.7 0 00-.3-1.8l-.1-.1a2 2 0 112.8-2.8l.1.1a1.7 1.7 0 001.8.3H9a1.7 1.7 0 001-1.5V3a2 2 0 114 0v.1a1.7 1.7 0 001 1.5 1.7 1.7 0 001.8-.3l.1-.1a2 2 0 112.8 2.8l-.1.1a1.7 1.7 0 00-.3 1.8V9a1.7 1.7 0 001.5 1H21a2 2 0 110 4h-.1a1.7 1.7 0 00-1.5 1z"/></svg>',
  info: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="10"/><path d="M12 16v-4"/><path d="M12 8h.01"/></svg>',
};

function topbar(title, sub, { backBtn = true, right = '' } = {}) {
  return `<div class="top">
    ${backBtn ? `<button class="iconbtn" data-act="back" aria-label="뒤로">${ICON.back}</button>` : '<span style="width:8px"></span>'}
    <h1>${title}${sub ? `<span class="sub">${sub}</span>` : ''}</h1>${right}</div>`;
}

function toast(msg, ms = 2200) {
  const t = $('#toast');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(t._h);
  t._h = setTimeout(() => t.classList.remove('show'), ms);
}

const modals = [];
function openModal(html, onMount) {
  const bg = document.createElement('div');
  bg.className = 'modal-bg';
  bg.innerHTML = `<div class="modal">${html}</div>`;
  bg.addEventListener('click', e => { if (e.target === bg && !bg.dataset.locked) closeTopModal(); });
  $('#modal-root').appendChild(bg);
  const m = { el: bg, onClose: null };
  modals.push(m);
  if (onMount) onMount(bg.firstElementChild, m);
  return m;
}
function closeTopModal() {
  const m = modals[modals.length - 1];
  if (!m) return false;
  if (m.el.dataset.locked) return true;
  modals.pop();
  m.el.remove();
  if (m.onClose) m.onClose();
  return true;
}
function confirmDialog(title, msg, okText = '확인', danger = false) {
  return new Promise(res => {
    let done = false;
    openModal(`<h3>${esc(title)}</h3><p>${esc(msg)}</p>
      <div class="acts"><button class="btn ghost" data-r="0">취소</button><button class="btn ${danger ? 'danger' : 'primary'}" data-r="1">${esc(okText)}</button></div>`,
      (el, m) => {
        m.onClose = () => { if (!done) res(false); };
        $$('[data-r]', el).forEach(b => b.onclick = () => { done = true; closeTopModal(); res(b.dataset.r === '1'); });
      });
  });
}
function promptDialog(title, placeholder = '', value = '') {
  return new Promise(res => {
    let done = false;
    openModal(`<h3>${esc(title)}</h3><input class="input" id="pd" placeholder="${esc(placeholder)}" value="${esc(value)}">
      <div class="acts" style="margin-top:14px"><button class="btn ghost" data-r="0">취소</button><button class="btn primary" data-r="1">확인</button></div>`,
      (el, m) => {
        const inp = $('#pd', el);
        setTimeout(() => inp.focus(), 50);
        m.onClose = () => { if (!done) res(null); };
        const ok = () => { done = true; const v = inp.value.trim(); closeTopModal(); res(v || null); };
        inp.addEventListener('keydown', e => { if (e.key === 'Enter') ok(); });
        $$('[data-r]', el).forEach(b => b.onclick = () => { if (b.dataset.r === '1') ok(); else closeTopModal(); });
      });
  });
}
function lightbox(id) {
  const d = document.createElement('div');
  d.className = 'lightbox';
  d.innerHTML = `<img src="${photoUrl(id)}"><button class="iconbtn close">✕</button>`;
  const m = { el: d, onClose: null };
  d.onclick = () => closeTopModal();
  $('#modal-root').appendChild(d);
  modals.push(m);
}

// 전역 클릭 위임: data-act="back"
document.addEventListener('click', e => {
  const b = e.target.closest('[data-act="back"]');
  if (b) { e.preventDefault(); back(); }
});

// ======================================================================= 홈
function vHome(app) {
  const ps = [...DB.projects].sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
  app.innerHTML = topbar('펀치리스트', '건물 하자 점검', { backBtn: false, right: `<button class="iconbtn" id="settings" aria-label="설정">${ICON.gear}</button>` }) +
    `<div class="page">
      ${ps.length ? '<div class="section-title">점검 현장</div>' : ''}
      ${ps.map(p => {
        const n = p.items.length, d = p.items.filter(i => i.done).length;
        return `<div class="card proj" data-id="${p.id}">
          <div class="meta"><div class="name">${esc(p.name)}</div>
          <div class="info">${esc(p.date || '')}${p.inspector ? ' · ' + esc(p.inspector) : ''} · ${floorsOf(p).length}개 층</div></div>
          <div class="count"><b>${n}</b>건${d ? ` (완료 ${d})` : ''}</div></div>`;
      }).join('')}
      ${ps.length ? '' : `<div class="empty"><div class="big">📋</div><p><b>등록된 현장이 없습니다</b><br>아래 버튼으로 건물명과 층수를 입력해 시작하세요.</p></div>`}
    </div>
    <div class="bottombar"><button class="btn primary" id="new">＋ 새 현장 등록</button></div>`;
  $$('.proj', app).forEach(el => el.onclick = () => go('project', { id: el.dataset.id }));
  $('#new', app).onclick = () => go('setup', {});
  $('#settings', app).onclick = () => go('settings');
}

// ======================================================================= 현장 등록/수정
function vSetup(app, { id }) {
  const editing = id ? getProject(id) : null;
  if (!ctx.p) {
    ctx.p = editing ? JSON.parse(JSON.stringify(editing)) : {
      id: uid(), name: '', date: today(), inspector: '', items: [],
      floorCfg: { b: 1, n: 5, r: 1, roof: true, all: true, ext: false, elev: 0, custom: [] },
    };
    ctx.p.floorCfg.custom = ctx.p.floorCfg.custom || [];
  }
  const p = ctx.p, c = p.floorCfg;
  const stepper = (k, label, sub, max) => `<div class="numrow"><div class="lbl">${label}<small>${sub}</small></div>
    <div class="stepper"><button data-k="${k}" data-d="-1">−</button><input type="number" inputmode="numeric" data-k="${k}" value="${c[k] | 0}" min="0" max="${max}"><button data-k="${k}" data-d="1">＋</button></div></div>`;
  const tog = (k, label) => `<button class="chip ${c[k] ? 'on' : ''}" data-tog="${k}">${c[k] ? '✓ ' : ''}${label}</button>`;
  const fl = floorsOf(p);
  app.innerHTML = topbar(editing ? '현장 정보 수정' : '새 현장 등록', '', {}) +
    `<div class="page">
      <div class="field"><label>건물명 <span class="req">필수</span></label><input class="input" id="name" value="${esc(p.name)}" placeholder="예: OO빌딩 A동"></div>
      <div class="row">
        <div class="field"><label>점검일자</label><input class="input" type="date" id="date" value="${esc(p.date)}"></div>
        <div class="field"><label>점검자</label><input class="input" id="insp" value="${esc(p.inspector)}" placeholder="이름"></div>
      </div>
      <div class="section-title">층 구성</div>
      <div class="card">
        ${stepper('b', '지하', '지하 층수', 20)}
        ${stepper('n', '지상', '지상 층수', 150)}
        ${stepper('r', '옥탑', '옥탑 층수', 5)}
        ${stepper('elev', '승강기', '호기 수 (승강기 점검 시 선택)', 30)}
        <div class="togglerow">${tog('roof', '옥상')}${tog('all', '전층')}${tog('ext', '외부')}</div>
      </div>
      <div class="section-title">층 목록 미리보기 (${fl.length}개) · 추가 항목은 눌러서 삭제</div>
      <div class="card floorchips">
        ${fl.map(f => (c.custom || []).includes(f) ? `<button class="chip custom" data-rm="${esc(f)}">${esc(f)}<span class="x">×</span></button>` : `<span class="chip">${esc(f)}</span>`).join('')}
        <button class="chip" id="addfloor" style="border-style:dashed">＋ 직접 추가 (PIT, M층 등)</button>
      </div>
      ${editing ? `<div style="margin-top:28px"><button class="btn danger block" id="del">${ICON.trash}<span>이 현장 삭제</span></button></div>` : ''}
    </div>
    <div class="bottombar"><button class="btn primary" id="save">${editing ? '수정 완료' : '등록하고 시작'}</button></div>`;

  const sync = () => { p.name = $('#name').value; p.date = $('#date').value; p.inspector = $('#insp').value; };
  $$('.stepper button', app).forEach(b => b.onclick = () => {
    sync();
    const k = b.dataset.k, max = +$(`input[data-k="${k}"]`).max;
    c[k] = Math.max(0, Math.min(max, (c[k] | 0) + +b.dataset.d));
    render();
  });
  $$('.stepper input', app).forEach(i => i.onchange = () => { sync(); c[i.dataset.k] = Math.max(0, Math.min(+i.max, parseInt(i.value, 10) || 0)); render(); });
  $$('[data-tog]', app).forEach(b => b.onclick = () => { sync(); c[b.dataset.tog] = !c[b.dataset.tog]; render(); });
  $$('[data-rm]', app).forEach(b => b.onclick = () => { sync(); c.custom = c.custom.filter(f => f !== b.dataset.rm); render(); });
  $('#addfloor', app).onclick = async () => {
    sync();
    const v = await promptDialog('층/구역 추가', '예: PIT층, M층, 기계실동');
    if (v) { if (!c.custom.includes(v)) c.custom.push(v); render(); }
  };
  $('#save', app).onclick = () => {
    sync();
    if (!p.name.trim()) { toast('건물명을 입력하세요'); $('#name').focus(); return; }
    p.name = p.name.trim();
    p.updatedAt = Date.now();
    if (editing) Object.assign(editing, p, { items: editing.items });
    else { p.createdAt = Date.now(); DB.projects.push(p); }
    saveDB();
    toast(editing ? '수정되었습니다' : '현장이 등록되었습니다');
    if (editing) back(); else go('project', { id: p.id }, true);
  };
  if (editing) $('#del', app).onclick = async () => {
    const ok = await confirmDialog('현장 삭제', `"${editing.name}"의 하자 ${editing.items.length}건과 사진이 모두 삭제됩니다.\n되돌릴 수 없습니다.`, '삭제', true);
    if (!ok) return;
    editing.items.forEach(i => (i.photos || []).forEach(ph => N && N.deletePhoto(ph.id)));
    DB.projects = DB.projects.filter(x => x !== editing);
    saveDB();
    stack = [{ name: 'home', params: {} }];
    ctx = {}; render();
    toast('삭제되었습니다');
  };
}

// ======================================================================= 현장 대시보드
function vProject(app, { id }) {
  const p = getProject(id);
  if (!p) { stack = [{ name: 'home', params: {} }]; return render(); }
  const n = p.items.length, d = p.items.filter(i => i.done).length;
  app.innerHTML = topbar(esc(p.name), `${esc(p.date || '')}${p.inspector ? ' · ' + esc(p.inspector) : ''}`,
    { right: `<button class="iconbtn" id="edit" aria-label="수정">${ICON.edit}</button>` }) +
    `<div class="page">
      <div class="card">
        <div class="summary"><div class="s"><b>${n}</b><span>전체 하자</span></div><div class="s"><b>${n - d}</b><span>진행중</span></div><div class="s"><b style="color:var(--ok)">${d}</b><span>조치완료</span></div>
        <div class="s"><b>${p.items.reduce((s, i) => s + (i.photos || []).length, 0)}</b><span>사진</span></div></div>
        <div class="progress"><i style="width:${n ? d / n * 100 : 0}%"></i></div>
      </div>
      <div class="section-title">공종 선택</div>
      <div class="catgrid">
        ${CATS.map(c => {
          const its = p.items.filter(i => i.cat === c.key);
          return `<button class="card cat" style="--c:${c.color}" data-cat="${c.key}"><div class="cname">${c.name}</div>
            <div class="cnum"><b>${its.length}</b>건${its.some(i => i.done) ? ` · 완료 ${its.filter(i => i.done).length}` : ''}</div></button>`;
        }).join('')}
      </div>
    </div>
    <div class="bottombar"><button class="btn dark" id="exp">${ICON.share}<span>엑셀 · 사진 내보내기</span></button></div>`;
  $$('.cat', app).forEach(b => b.onclick = () => go('list', { id, cat: b.dataset.cat }));
  $('#edit', app).onclick = () => go('setup', { id });
  $('#exp', app).onclick = () => go('export', { id });
}

// ======================================================================= 공종별 목록
function vList(app, { id, cat }) {
  const p = getProject(id), c = CAT[cat];
  const all = itemsOf(p, cat);
  const used = [...new Set(all.map(i => i.floor))];
  const order = [...elevUnits(p), ...floorsOf(p)];
  used.sort((a, b) => (order.indexOf(a) + 1 || 999) - (order.indexOf(b) + 1 || 999));
  const f = ctx.filter || '';
  const list = f ? all.filter(i => i.floor === f) : all;
  app.innerHTML = topbar(`<span class="catdot" style="background:${c.color}"></span>${c.name}`, esc(p.name)) +
    (used.length > 1 ? `<div class="filters"><button class="chip ${f ? '' : 'on'}" data-f="">전체 ${all.length}</button>
      ${used.map(u => `<button class="chip ${f === u ? 'on' : ''}" data-f="${esc(u)}">${esc(u)} ${all.filter(i => i.floor === u).length}</button>`).join('')}</div>` : '') +
    `<div class="page" style="--c:${c.color}">
      ${list.map(i => {
        const ph = i.photos || [];
        return `<div class="card item" data-iid="${i.id}">
          <div class="thumb" style="${ph[0] ? `background-image:url('${thumbUrl(ph[0].id)}')` : ''}">${ph[0] ? (ph.length > 1 ? `<span class="n">+${ph.length - 1}</span>` : '') : '📷'}</div>
          <div class="body"><div class="where"><span class="no">${all.indexOf(i) + 1}</span>${esc(i.floor)} · ${esc(i.location)}${i.equipment ? ' · ' + esc(i.equipment) : ''}${i.done ? '<span class="badge">완료</span>' : ''}</div>
          <div class="issue">${esc(i.issue) || '<span style="color:var(--ink3)">(내용 없음)</span>'}</div></div></div>`;
      }).join('')}
      ${all.length ? '' : `<div class="empty"><div class="big">🔍</div><p><b>${c.name} 하자가 아직 없습니다</b><br>아래 <b>＋ 하자 등록</b>을 눌러 층을 고르고 기록하세요.</p></div>`}
    </div>
    <button class="fab" id="add">＋ 하자 등록</button>`;
  $$('[data-f]', app).forEach(b => b.onclick = () => { ctx.filter = b.dataset.f; const y = window.scrollY; render(); window.scrollTo(0, y); });
  $$('.item', app).forEach(el => el.onclick = () => go('item', { id, cat, iid: el.dataset.iid }));
  $('#add', app).onclick = () => go('floor', { id, cat });
}

// ======================================================================= 1단계: 층 선택
function floorButtons(p, cat, selected) {
  const counts = {};
  p.items.filter(i => i.cat === cat).forEach(i => counts[i.floor] = (counts[i.floor] || 0) + 1);
  const btn = f => `<button class="floorbtn ${f.startsWith('지하') ? 'basement' : ''} ${f === selected ? 'on' : ''}" data-floor="${esc(f)}">${esc(f)}${counts[f] ? `<span class="cnt">${counts[f]}건</span>` : ''}</button>`;
  const elev = cat === 'elev' ? elevUnits(p) : [];
  return (elev.length ? `<div class="section-title">승강기 호기</div><div class="floorgrid">${elev.map(btn).join('')}</div><div class="section-title">층</div>` : '') +
    `<div class="floorgrid">${floorsOf(p).map(btn).join('')}<button class="floorbtn" data-floor="" style="border-style:dashed;color:var(--ink2)">직접 입력</button></div>`;
}
async function pickFloorValue(el) {
  if (el.dataset.floor) return el.dataset.floor;
  return await promptDialog('층/위치 직접 입력', '예: PIT층, 3~5층, 6호기');
}
function vFloor(app, { id, cat }) {
  const p = getProject(id), c = CAT[cat];
  app.innerHTML = topbar(`<span class="catdot" style="background:${c.color}"></span>${c.name} · 하자 등록`, esc(p.name)) +
    `<div class="steps"><span class="on">① 층 선택</span><span>›</span><span>② 장소·문제점</span><span>›</span><span>③ 사진</span></div>
    <div class="page">${floorButtons(p, cat, ctx.sel)}</div>`;
  $$('[data-floor]', app).forEach(b => b.onclick = async () => {
    const f = await pickFloorValue(b);
    if (f) go('item', { id, cat, floor: f }, true);
  });
}

// ======================================================================= 2·3단계: 하자 입력
const DRAFT_KEY = 'pl_draft';
function saveDraft() {
  try { localStorage.setItem(DRAFT_KEY, JSON.stringify({ params: stack[stack.length - 1].params, f: ctx.f, newPhotos: ctx.newPhotos, removed: ctx.removed })); } catch (e) { }
}
function clearDraft() { try { localStorage.removeItem(DRAFT_KEY); } catch (e) { } }
function discardDraftPhotos() { (ctx.newPhotos || []).forEach(id => N && N.deletePhoto(id)); }

function recentValues(p, key, prefer) {
  const items = [...p.items].sort((a, b) => (b.updatedAt || b.createdAt) - (a.updatedAt || a.createdAt));
  const out = [];
  const push = v => { v = (v || '').trim(); if (v && !out.includes(v)) out.push(v); };
  if (prefer) items.filter(prefer).forEach(i => push(i[key]));
  items.forEach(i => push(i[key]));
  return out.slice(0, 10);
}

function vItem(app, params) {
  const { id, cat, iid } = params;
  const p = getProject(id), c = CAT[cat];
  const editing = iid ? p.items.find(i => i.id === iid) : null;
  if (!ctx.f) {
    if (params._restore) {
      ctx.f = params._restore.f; ctx.newPhotos = params._restore.newPhotos || []; ctx.removed = params._restore.removed || [];
      ctx.dirty = true;
      delete params._restore;
    } else {
      const src = editing || {};
      ctx.f = {
        floor: src.floor || params.floor || '', location: src.location || params.location || '', equipment: src.equipment || '',
        issue: src.issue || '', photos: JSON.parse(JSON.stringify(src.photos || [])),
        company: src.company || '', action: src.action || '', actionDate: src.actionDate || '', done: !!src.done,
      };
      ctx.newPhotos = []; ctx.removed = [];
    }
  }
  const f = ctx.f;
  const no = editing ? numberOf(p, editing) : itemsOf(p, cat).length + 1;
  const locs = recentValues(p, 'location', i => i.floor === f.floor);
  const eqs = recentValues(p, 'equipment', i => i.cat === cat);
  const showMore = editing && (f.company || f.action || f.actionDate || f.done);

  app.innerHTML = topbar(`${editing ? '하자 수정' : '하자 등록'} · No.${no}`, esc(p.name),
      { right: editing ? `<button class="iconbtn" id="del" aria-label="삭제">${ICON.trash}</button>` : '' }) +
    `<div class="ctxbar" style="--c:${c.color}"><span class="tag">${c.name}</span>
      <button class="floor" id="chfloor">${esc(f.floor) || '층 선택'} <span style="color:var(--ink3)">▾</span></button>
      <span style="margin-left:auto;font-size:12px;color:var(--ink3)">${editing ? fmtTime(editing.createdAt) : ''}</span></div>
    <div class="page">
      <div class="field"><label>장소 <span class="req">필수</span></label>
        <input class="input" id="location" value="${esc(f.location)}" placeholder="예: 계단실1, 남자화장실, 기계실" autocomplete="off">
        ${locs.length ? `<div class="sugs">${locs.map(v => `<button class="chip" data-set="location" data-v="${esc(v)}">${esc(v)}</button>`).join('')}</div>` : ''}</div>
      <div class="field"><label>설비(장비)명 <small>선택</small></label>
        <input class="input" id="equipment" value="${esc(f.equipment)}" placeholder="예: 벽, 바닥, 스프링클러, FCU" autocomplete="off">
        ${eqs.length ? `<div class="sugs">${eqs.map(v => `<button class="chip" data-set="equipment" data-v="${esc(v)}">${esc(v)}</button>`).join('')}</div>` : ''}</div>
      <div class="field"><label>현황 및 문제점 <span class="req">필수</span></label>
        <textarea class="input" id="issue" rows="3" placeholder="예: 출입문 손잡이 불량">${esc(f.issue)}</textarea>
        <div class="sugs">${(PHRASES[cat] || []).map(v => `<button class="chip" data-add="${esc(v)}">＋${esc(v)}</button>`).join('')}</div></div>
      <div class="field"><label>현장 사진 <small>엑셀에는 앞의 2장이 들어갑니다</small></label>
        <div class="photobtns"><button class="btn cam" id="cam">📷 사진 촬영</button><button class="btn" id="gal">🖼 앨범</button></div>
        <div class="photos" id="photos"></div></div>
      <details class="more" ${showMore ? 'open' : ''}><summary>조치 정보 (업체 · 조치내역 · 완료)</summary>
        <div class="field"><label>업체명</label><input class="input" id="company" value="${esc(f.company)}" placeholder="예: 영강, 직영"></div>
        <div class="field"><label>조치 내역</label><textarea class="input" id="action" rows="2" placeholder="예: 손보기 작업 완료">${esc(f.action)}</textarea></div>
        <div class="field"><label>조치일자</label><input class="input" id="actionDate" value="${esc(f.actionDate)}" placeholder="예: 2026-11-20 / 6월 8일 완료 예정"></div>
        <label class="check"><input type="checkbox" id="done" ${f.done ? 'checked' : ''}> 조치 완료</label>
      </details>
    </div>
    <div class="bottombar">
      ${editing ? `<button class="btn primary" id="save">저장</button>` :
        `<button class="btn ghost" id="save" style="flex:.8">저장</button><button class="btn primary" id="savenext">저장 후 다음 ›</button>`}
    </div>`;

  const renderPhotos = () => {
    $('#photos').innerHTML = f.photos.map((ph, i) => `<div class="ph" data-pid="${ph.id}" style="background-image:url('${thumbUrl(ph.id)}')">
      <span class="idx ${i < 2 ? 'xl' : ''}">${i + 1}${i < 2 ? ' · 엑셀' : ''}</span><button class="del" data-pdel="${ph.id}">×</button></div>`).join('');
    $$('.ph').forEach(el => el.onclick = e => { if (!e.target.closest('.del')) lightbox(el.dataset.pid); });
    $$('[data-pdel]').forEach(b => b.onclick = async e => {
      e.stopPropagation();
      if (!await confirmDialog('사진 삭제', '이 사진을 삭제할까요?', '삭제', true)) return;
      const pid = b.dataset.pdel;
      f.photos = f.photos.filter(x => x.id !== pid);
      if (ctx.newPhotos.includes(pid)) { ctx.newPhotos = ctx.newPhotos.filter(x => x !== pid); N && N.deletePhoto(pid); }
      else ctx.removed.push(pid);
      ctx.dirty = true; saveDraft(); renderPhotos();
    });
  };
  renderPhotos();
  ctx.renderPhotos = renderPhotos;

  ['location', 'equipment', 'issue', 'company', 'action', 'actionDate'].forEach(k => {
    $('#' + k).addEventListener('input', e => { f[k] = e.target.value; ctx.dirty = true; saveDraft(); });
  });
  $('#done').onchange = e => { f.done = e.target.checked; ctx.dirty = true; saveDraft(); };
  $$('[data-set]', app).forEach(b => b.onclick = () => {
    const k = b.dataset.set; f[k] = b.dataset.v; $('#' + k).value = f[k]; ctx.dirty = true; saveDraft();
  });
  $$('[data-add]', app).forEach(b => b.onclick = () => {
    const t = $('#issue'); const v = b.dataset.add;
    f.issue = f.issue.trim() ? f.issue.replace(/\s+$/, '') + (/[\n,]$/.test(f.issue.trim()) ? ' ' : ', ') + v : v;
    t.value = f.issue; ctx.dirty = true; saveDraft();
  });
  $('#chfloor').onclick = () => {
    openModal(`<h3>층 변경</h3>${floorButtons(p, cat, f.floor)}`, el => {
      $$('[data-floor]', el).forEach(b => b.onclick = async () => {
        const v = await pickFloorValue(b);
        if (!v) return;
        closeTopModal();
        f.floor = v; ctx.dirty = true; saveDraft();
        $('#chfloor').firstChild.textContent = v + ' ';
      });
    });
  };
  const reqId = 'r' + Date.now();
  ctx.reqId = reqId;
  $('#cam').onclick = () => { saveDraft(); if (N) N.takePhoto(reqId); else mockPhoto(); };
  $('#gal').onclick = () => { saveDraft(); if (N) N.pickPhotos(reqId); else mockPhoto(); };

  const doSave = next => {
    if (!f.floor) { toast('층을 선택하세요'); return; }
    if (!f.location.trim()) { toast('장소를 입력하세요'); $('#location').focus(); return; }
    if (!f.issue.trim()) { toast('현황 및 문제점을 입력하세요'); $('#issue').focus(); return; }
    const now = Date.now();
    const data = {
      floor: f.floor.trim(), location: f.location.trim(), equipment: f.equipment.trim(), issue: f.issue.trim(), photos: f.photos,
      company: f.company.trim(), action: f.action.trim(), actionDate: f.actionDate.trim(), done: f.done, updatedAt: now,
    };
    if (editing) Object.assign(editing, data);
    else p.items.push(Object.assign({ id: uid(), cat, createdAt: now }, data));
    ctx.removed.forEach(pid => N && N.deletePhoto(pid));
    p.updatedAt = now;
    saveDB();
    clearDraft();
    ctx.dirty = false;
    if (next) {
      toast(`No.${no} 저장됨 · 같은 층/장소로 계속`);
      go('item', { id, cat, floor: data.floor, location: data.location }, true);
    } else {
      toast(editing ? '수정되었습니다' : `No.${no} 저장되었습니다`);
      stack.pop(); ctx = {}; render();
    }
  };
  $('#save').onclick = () => doSave(false);
  if (!editing) $('#savenext').onclick = () => doSave(true);
  if (editing) $('#del').onclick = async () => {
    if (!await confirmDialog('하자 삭제', `No.${no} 항목과 사진을 삭제할까요?\n(이후 번호가 하나씩 당겨집니다)`, '삭제', true)) return;
    [...(editing.photos || []).map(x => x.id), ...ctx.newPhotos].forEach(pid => N && N.deletePhoto(pid));
    p.items = p.items.filter(i => i !== editing);
    p.updatedAt = Date.now();
    saveDB(); clearDraft(); ctx.dirty = false;
    stack.pop(); ctx = {}; render();
    toast('삭제되었습니다');
  };
}

// 네이티브에서 사진 처리 완료 알림
window.onNativePhotos = function () {
  if (!N) return;
  let arr = [];
  try { arr = JSON.parse(N.takePendingPhotos() || '[]'); } catch (e) { }
  const cur = stack[stack.length - 1];
  if (!arr.length) return;
  if (!cur || cur.name !== 'item' || !ctx.f) { arr.forEach(x => N.deletePhoto(x.id)); return; }
  arr.forEach(x => { ctx.f.photos.push({ id: x.id, w: x.w, h: x.h }); ctx.newPhotos.push(x.id); });
  ctx.dirty = true;
  saveDraft();
  if (ctx.renderPhotos) ctx.renderPhotos();
  toast(`사진 ${arr.length}장 추가됨`);
};
window.onPhotoBusy = function (on) { $('#busy').classList.toggle('hidden', !on); };

// 브라우저 미리보기용 가짜 사진
function mockPhoto() {
  const id = 'mock' + Date.now();
  ctx.f.photos.push({ id, w: 1600, h: 1200 }); ctx.newPhotos.push(id); ctx.dirty = true; ctx.renderPhotos();
}

// ======================================================================= 내보내기
function vExport(app, { id }) {
  const p = getProject(id);
  if (!ctx.o) {
    const has = CATS.filter(c => p.items.some(i => i.cat === c.key)).map(c => c.key);
    ctx.o = { excel: true, photoMode: DB.settings.photoMode || 'with', cats: has.length ? has : CATS.map(c => c.key) };
  }
  const o = ctx.o;
  const nPhotos = p.items.filter(i => o.cats.includes(i.cat)).reduce((s, i) => s + (i.photos || []).length, 0);
  const nItems = p.items.filter(i => o.cats.includes(i.cat)).length;
  const radio = (v, t, d) => `<label class="opt"><input type="radio" name="pm" value="${v}" ${o.photoMode === v ? 'checked' : ''}><div><div class="t">${t}</div><div class="d">${d}</div></div></label>`;
  app.innerHTML = topbar('내보내기', esc(p.name)) +
    `<div class="page">
      <div class="section-title">엑셀 파일</div>
      <div class="card"><label class="opt"><input type="checkbox" id="excel" ${o.excel ? 'checked' : ''}>
        <div><div class="t">PUNCH LIST 엑셀 (.xlsx)</div><div class="d">집계표 + 공종별 시트, 현장사진 1·2 자동 삽입</div></div></label></div>
      <div class="section-title">사진 파일 저장 방식</div>
      <div class="card">
        ${radio('with', '워터마크 있음', '사진 하단에 건물 / 층 / 위치 / 문제점 / 일시 표시')}
        ${radio('without', '워터마크 없음', '깨끗한 사진 그대로 저장')}
        ${radio('both', '둘 다', '워터마크 · 원본 폴더로 나눠 저장')}
        ${radio('none', '사진 저장 안 함', '엑셀만 만들기')}
      </div>
      <div id="pvwrap"></div>
      <div class="section-title">공종 선택</div>
      <div class="card togglerow">${CATS.map(c => {
        const n = p.items.filter(i => i.cat === c.key).length;
        return `<button class="chip ${o.cats.includes(c.key) ? 'on' : ''}" data-c="${c.key}">${o.cats.includes(c.key) ? '✓ ' : ''}${c.name} ${n}</button>`;
      }).join('')}</div>
      <p class="note" style="margin:12px 4px">선택: 하자 ${nItems}건 · 사진 ${nPhotos}장<br>
      휴대폰 저장 위치 — 엑셀: <b>다운로드/PunchList</b>, 사진: <b>사진(Pictures)/PunchList/건물명_날짜/공종</b></p>
    </div>
    <div class="bottombar"><button class="btn dark" id="share">${ICON.share}<span>공유</span></button><button class="btn primary" id="savebtn" style="flex:1.4">📥 휴대폰에 저장</button></div>`;

  $('#excel').onchange = e => { o.excel = e.target.checked; };
  $$('input[name=pm]', app).forEach(r => r.onchange = () => { o.photoMode = r.value; DB.settings.photoMode = r.value; saveDB(); showPreview(); });
  $$('[data-c]', app).forEach(b => b.onclick = () => {
    const k = b.dataset.c;
    o.cats = o.cats.includes(k) ? o.cats.filter(x => x !== k) : [...o.cats, k];
    render();
  });

  const firstWithPhoto = () => {
    for (const c of CATS) {
      if (!o.cats.includes(c.key)) continue;
      const it = itemsOf(p, c.key).find(i => (i.photos || []).length);
      if (it) return it;
    }
    return null;
  };
  function showPreview() {
    const w = $('#pvwrap');
    if (!(o.photoMode === 'with' || o.photoMode === 'both')) { w.innerHTML = ''; return; }
    const it = firstWithPhoto();
    if (!it) { w.innerHTML = ''; return; }
    w.innerHTML = `<div class="section-title">워터마크 미리보기</div><div class="card" style="padding:10px"><button class="btn block sm" id="pvbtn">미리보기 만들기</button><div id="pv"></div></div>`;
    $('#pvbtn').onclick = () => {
      if (!N) { $('#pv').innerHTML = '<p class="note">앱에서만 미리보기가 가능합니다</p>'; return; }
      $('#pvbtn').textContent = '만드는 중…';
      setTimeout(() => {
        const url = N.watermarkPreview(JSON.stringify({
          photoId: it.photos[0].id, building: p.name, floor: it.floor, location: it.location, category: CAT[it.cat].name,
          equipment: it.equipment, issue: it.issue, date: fmtTime(it.createdAt), no: numberOf(p, it),
        }));
        $('#pvbtn').remove();
        $('#pv').innerHTML = url ? `<img class="preview" src="${url}">` : '<p class="note">미리보기를 만들 수 없습니다</p>';
      }, 30);
    };
  }
  showPreview();

  const run = target => {
    if (!o.excel && o.photoMode === 'none') { toast('엑셀 또는 사진 중 하나는 선택하세요'); return; }
    if (!o.cats.length) { toast('공종을 하나 이상 선택하세요'); return; }
    const payload = {
      target, excel: o.excel, photoMode: o.photoMode,
      project: { name: p.name, date: p.date, inspector: p.inspector },
      cats: CATS.filter(c => o.cats.includes(c.key)).map(c => ({
        key: c.key, name: c.name, title: c.title, summary: c.summary,
        items: itemsOf(p, c.key).map((i, idx) => ({
          no: idx + 1, floor: i.floor, location: i.location, equipment: i.equipment, issue: i.issue,
          action: i.action, company: i.company, actionDate: i.actionDate, done: !!i.done,
          createdAt: fmtTime(i.createdAt), photos: (i.photos || []).map(x => x.id),
        })),
      })),
    };
    openModal(`<h3>${target === 'share' ? '공유 파일 만드는 중' : '저장 중'}</h3><div class="pbar"><i id="pb" style="width:0%"></i></div><div class="pmsg" id="pm">준비 중…</div>`,
      el => { el.parentElement.dataset.locked = '1'; });
    if (N) N.exportProject(JSON.stringify(payload));
    else setTimeout(() => window.onExportDone({ ok: true, saved: ['(브라우저 미리보기 — 실제 저장은 앱에서)'], shared: false }), 400);
  };
  $('#savebtn').onclick = () => run('save');
  $('#share').onclick = () => run('share');
}
window.onExportProgress = function (msg, pct) {
  const pb = $('#pb'), pm = $('#pm');
  if (pb) pb.style.width = pct + '%';
  if (pm) pm.textContent = msg;
};
window.onExportDone = function (r) {
  const m = modals[modals.length - 1];
  if (m) { delete m.el.dataset.locked; closeTopModal(); }
  if (!r.ok) {
    openModal(`<h3>내보내기 실패</h3><p>${esc(r.error || '알 수 없는 오류')}</p><div class="acts"><button class="btn dark" onclick="closeTopModal()">닫기</button></div>`);
    return;
  }
  if (r.shared && !(r.saved || []).length) return; // 공유 창이 바로 뜸
  openModal(`<h3>✅ 저장 완료</h3><ul class="result">${(r.saved || []).map(s => `<li>${esc(s)}</li>`).join('')}</ul>
    <p class="note">파일 관리자(내 파일) 앱의 다운로드 / 사진 폴더에서 확인할 수 있습니다.</p>
    <div class="acts"><button class="btn dark" onclick="closeTopModal()">확인</button></div>`);
};

// ======================================================================= 설정
function vSettings(app) {
  let info = { photos: 0, mb: 0 };
  try { if (N) info = JSON.parse(N.storageInfo()); } catch (e) { }
  const nItems = DB.projects.reduce((s, p) => s + p.items.length, 0);
  app.innerHTML = topbar('설정', '') +
    `<div class="page">
      <div class="section-title">데이터 백업 (현장·하자·사진 전체)</div>
      <div class="card" style="padding:14px">
        <p class="note" style="margin:0 0 12px">현장 ${DB.projects.length}곳 · 하자 ${nItems}건 · 사진 ${info.photos}장 (${info.mb}MB)<br>
        백업 ZIP 파일로 다른 휴대폰에 옮기거나, 앱 삭제·초기화 전에 보관할 수 있습니다.</p>
        <div class="row" style="margin-bottom:10px"><button class="btn primary" id="bsave">📥 백업 저장</button><button class="btn dark" id="bshare">${ICON.share}<span>백업 공유</span></button></div>
        <button class="btn ghost block" id="bload">📂 백업 불러오기</button>
      </div>
      <div class="section-title">데이터 초기화</div>
      <div class="card" style="padding:14px">
        <p class="note" style="margin:0 0 12px">모든 현장, 하자 기록, 사진을 삭제합니다. 되돌릴 수 없으니 먼저 백업하세요.<br>(이미 휴대폰 다운로드·사진 폴더에 내보낸 파일은 지워지지 않습니다)</p>
        <button class="btn danger block" id="reset">${ICON.trash}<span>데이터 초기화</span></button>
      </div>
      <div class="section-title">앱 정보</div>
      <div class="card" style="padding:14px">
        <p class="note" style="margin:0;color:var(--ink2)"><b>펀치리스트 ${N && N.version ? 'v' + esc(N.version()) : ''}</b><br>
        건축 · 기계 · 전기 · 소방 · 승강기 · 안전(공통) 하자를 기록하고 PUNCH LIST 엑셀(사진 포함)과 사진 파일로 내보냅니다.<br>
        데이터는 이 휴대폰 안에만 저장됩니다.</p>
      </div>
    </div>`;
  const backup = target => {
    if (!N) { toast('앱에서만 가능합니다'); return; }
    openModal(`<h3>백업 ${target === 'share' ? '파일 만드는 중' : '저장 중'}</h3><div class="pbar"><i id="pb" style="width:0%"></i></div><div class="pmsg" id="pm">준비 중…</div>`,
      el => { el.parentElement.dataset.locked = '1'; });
    N.exportBackup(JSON.stringify(DB), target);
  };
  $('#bsave', app).onclick = () => backup('save');
  $('#bshare', app).onclick = () => backup('share');
  $('#bload', app).onclick = async () => {
    if (!N) { toast('앱에서만 가능합니다'); return; }
    if (DB.projects.length && !await confirmDialog('백업 불러오기', '현재 앱의 모든 데이터가 백업 파일 내용으로 바뀝니다.\n계속할까요?', '파일 선택')) return;
    N.importBackup();
  };
  $('#reset', app).onclick = async () => {
    if (!await confirmDialog('데이터 초기화', `현장 ${DB.projects.length}곳, 하자 ${nItems}건, 사진 ${info.photos}장이 모두 삭제됩니다.\n되돌릴 수 없습니다.`, '계속', true)) return;
    const v = await promptDialog('확인을 위해 "초기화"를 입력하세요', '초기화');
    if (v !== '초기화') { if (v !== null) toast('입력이 일치하지 않아 취소했습니다'); return; }
    if (N) N.resetAll();
    DB = { version: 1, projects: [], settings: { photoMode: DB.settings.photoMode || 'with', seededW4: true } };
    saveDB();
    clearDraft();
    stack = [{ name: 'home', params: {} }]; ctx = {}; render();
    toast('초기화되었습니다');
  };
}
window.onBackupImported = function (r) {
  if (!r) return;
  if (!r.ok) {
    openModal(`<h3>불러오기 실패</h3><p>${esc(r.error || '알 수 없는 오류')}</p><div class="acts"><button class="btn dark" onclick="closeTopModal()">닫기</button></div>`);
    return;
  }
  try {
    const d = JSON.parse(r.data);
    if (!d || !Array.isArray(d.projects)) throw new Error('형식 오류');
    DB = Object.assign({ settings: {} }, d);
    DB.settings.seededW4 = true;
    saveDB();
    const keep = [];
    DB.projects.forEach(p => p.items.forEach(i => (i.photos || []).forEach(ph => keep.push(ph.id))));
    if (N) N.cleanupPhotos(JSON.stringify(keep));
    clearDraft();
    stack = [{ name: 'home', params: {} }]; ctx = {}; render();
    const n = DB.projects.reduce((s, p) => s + p.items.length, 0);
    openModal(`<h3>✅ 불러오기 완료</h3><p>현장 ${DB.projects.length}곳 · 하자 ${n}건 · 사진 ${r.photos}장</p><div class="acts"><button class="btn dark" onclick="closeTopModal()">확인</button></div>`);
  } catch (e) {
    openModal(`<h3>불러오기 실패</h3><p>백업 파일을 읽을 수 없습니다.</p><div class="acts"><button class="btn dark" onclick="closeTopModal()">닫기</button></div>`);
  }
};

// 기본 현장: 무신사 W4 (지하1층 ~ 지상7층, 옥상) — 최초 1회만 생성
function seedDefaults() {
  if (DB.settings.seededW4) return;
  DB.settings.seededW4 = true;
  if (!DB.projects.some(p => p.name === '무신사 W4')) {
    DB.projects.push({
      id: uid(), name: '무신사 W4', date: today(), inspector: '', items: [],
      floorCfg: { b: 1, n: 7, r: 0, roof: true, all: true, ext: false, elev: 0, custom: [] },
      createdAt: Date.now(), updatedAt: Date.now(),
    });
  }
  saveDB();
}

// ======================================================================= 시작
function start() {
  loadDB();
  seedDefaults();
  stack = [{ name: 'home', params: {} }];
  // 작성 중이던 하자(카메라 촬영 중 앱이 종료된 경우 등) 복원
  try {
    const d = JSON.parse(localStorage.getItem(DRAFT_KEY) || 'null');
    if (d && d.params && getProject(d.params.id) && CAT[d.params.cat]) {
      stack.push({ name: 'project', params: { id: d.params.id } });
      stack.push({ name: 'list', params: { id: d.params.id, cat: d.params.cat } });
      stack.push({ name: 'item', params: Object.assign({}, d.params, { _restore: d }) });
    }
  } catch (e) { }
  render();
  if (N) setTimeout(window.onNativePhotos, 300);
}
start();
