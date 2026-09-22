/**
 * 파이프라인 운영 기록. 관리자만 본다(WWG_ADMIN_LOGIN_IDS).
 *
 * 이 서비스의 겉은 "모두에게 얼마나 걸리나"지만 속은 공간 데이터를 매일 받아 검수하고
 * 바뀐 것만 반영하는 파이프라인이다. 그것이 돌고 있다는 증거가 이 화면이다 — 회차가 날마다
 * 한 줄씩 늘고, 검수가 무엇을 걸러냈는지, 경로 탐색이 어떤 그래프를 쓰고 있는지.
 *
 * 읽기만 한다. 서버에 쓰는 버튼은 하나도 두지 않는다.
 */

import { api, ApiError } from '../api.js';
import { render, $, $$, esc, notice } from '../dom.js';

/** 원천 식별자 → 사람이 읽는 이름. */
const SOURCES = {
  LOCALDATA_FOOD: '일반음식점',
  LOCALDATA_REST: '휴게음식점',
};

/**
 * 검수 규칙 설명. `etl/validate/rules/*.sql` 머리 주석을 옮긴 것이다.
 * ERROR 는 서비스 반영에서 빠지고, WARN 은 반영하되 기록만 남긴다.
 */
const RULES = {
  COORD_MISSING: ['좌표 결측', '지도에 찍을 수 없고 경로의 출발·도착점이 되지 못한다'],
  NAME_MISSING: ['상호명 결측', '검색도 지도 표시도 할 수 없다'],
  DUPLICATE_NAME_ADDR: ['상호·주소 중복', '같은 상호 + 같은 지번이 두 건 이상. 층별 영업장일 수도 있어 판단하지 않고 기록만 한다'],
  STATUS_DATE_CONFLICT: ['영업상태·폐업일 불일치', '폐업인데 폐업일이 없거나, 영업 중인데 폐업일이 있다'],
  DATE_LOGIC_ERROR: ['날짜 순서 오류', '인허가일이 폐업일보다 늦다'],
  COORD_MOVED: ['좌표 이동', '직전 회차와 10m 넘게 다르다. 원본 정정이거나 좌표 변환이 틀어진 신호'],
};

const KINDS = {
  WALK: '보행', ACCESS: '진출입', BOARD: '승차', ALIGHT: '하차', RIDE: '주행', TRANSFER: '환승',
  STOP: '정류장·역', PLATFORM: '승강장(노선별)',
};
const MODES = ['SUBWAY', 'BUS', 'WALK'];
const MODE_LABEL = { SUBWAY: '지하철', BUS: '버스', WALK: '보행망' };

const PAGE_SIZE = 50;

/** 펼쳐 둔 검수 결과. 다른 규칙을 누르면 바뀐다. */
let drill = null;

export async function adminPage() {
  drill = null;
  render(`
    <div class="admin">
      <div class="admin-head">
        <h1>파이프라인</h1>
        <p class="meta">
          인허가 데이터를 매일 받아 검수하고 바뀐 것만 반영한다. 그래프는 시각표·버스 구간 데이터로 따로 빌드한다.
          읽기 전용이다.
        </p>
        <div id="totals" class="totals"></div>
      </div>

      <section>
        <h2>적재 회차</h2>
        <div id="runs">${notice('불러오는 중…')}</div>
      </section>

      <section>
        <h2>검수 <span class="meta">원천마다 검수 결과가 있는 가장 최근 회차</span></h2>
        <div id="rules">${notice('불러오는 중…')}</div>
      </section>

      <section>
        <h2>경로 그래프</h2>
        <div id="graph">${notice('불러오는 중…')}</div>
      </section>
    </div>
  `);

  await Promise.all([loadRuns(), loadRules(), loadGraph()]);
}

// ── 적재 회차 ──────────────────────────────────────────────────────────────

async function loadRuns() {
  try {
    const { runs, totals } = await api.adminIngestRuns();
    $('#totals').innerHTML = `
      <span><b>${num(totals.ACTIVE)}</b> 영업 중</span>
      <span><b>${num(totals.CLOSED)}</b> 폐업</span>
      <span><b>${runs.length}</b> 회차</span>`;
    $('#runs').innerHTML = runs.length ? runsTable(runs) : notice('아직 적재한 회차가 없습니다.');
  } catch (e) {
    $('#runs').innerHTML = failed(e);
  }
}

function runsTable(runs) {
  return `
    <div class="scroll">
      <table class="grid">
        <thead>
          <tr>
            <th>회차</th><th>원천</th><th>기준일</th><th>상태</th>
            <th class="n">원본</th><th class="n">유효</th><th class="n">거절</th>
            <th class="n" title="이번 회차에 처음 보인 레코드">신규</th>
            <th class="n" title="직전 회차에도 있던 레코드">유지</th>
            <th class="n" title="직전 회차에 있었는데 파일에서 사라진 레코드">소멸</th>
            <th class="n" title="영업 → 폐업으로 바뀐 레코드. 소멸과는 다른 사건이다">폐업 전환</th>
            <th class="n">걸린 시간</th><th>시작</th>
          </tr>
        </thead>
        <tbody>
          ${runs.map((r) => `
            <tr>
              <td class="n">#${r.id}</td>
              <td>${esc(SOURCES[r.source] ?? r.source)}</td>
              <td>${esc(r.snapshotDate ?? '')}</td>
              <td>${badge(r.status)}${r.errorMessage ? `<div class="meta warn">${esc(r.errorMessage)}</div>` : ''}</td>
              <td class="n">${num(r.rowsTotal)}</td>
              <td class="n">${num(r.rowsValid)}</td>
              <td class="n">${num(r.rowsRejected)}</td>
              <td class="n">${num(r.rowsNew)}</td>
              <td class="n">${num(r.rowsKept)}</td>
              <td class="n">${num(r.rowsVanished)}</td>
              <td class="n">${num(r.rowsClosed)}</td>
              <td class="n">${duration(r.durationMs)}</td>
              <td class="meta">${when(r.startedAt)}</td>
            </tr>`).join('')}
        </tbody>
      </table>
    </div>
    <p class="hint">
      <b>SKIPPED</b> 는 원본 파일이 직전 회차와 같아(해시 비교) 적재를 건너뛴 회차다. 실패가 아니다.
    </p>`;
}

// ── 검수 ──────────────────────────────────────────────────────────────────

async function loadRules() {
  try {
    const rows = await api.adminValidationSummary();
    if (!rows.length) {
      $('#rules').innerHTML = notice('검수 결과가 아직 없습니다.');
      return;
    }
    const bySource = groupBy(rows, (r) => r.source);
    $('#rules').innerHTML = Object.entries(bySource).map(([source, list]) => `
      <div class="rule-group">
        <h3>${esc(SOURCES[source] ?? source)} <span class="meta">회차 #${list[0].runId}</span></h3>
        <table class="grid rules">
          <tbody>
            ${list.map((r) => `
              <tr class="rule" data-run="${r.runId}" data-rule="${esc(r.ruleCode)}"
                  data-severity="${r.severity}" tabindex="0" title="눌러서 해당 레코드 보기">
                <td>${badge(r.severity)}</td>
                <td>
                  <b>${esc(RULES[r.ruleCode]?.[0] ?? r.ruleCode)}</b>
                  <code class="meta">${esc(r.ruleCode)}</code>
                  <div class="meta">${esc(RULES[r.ruleCode]?.[1] ?? '')}</div>
                </td>
                <td class="n"><b>${num(r.count)}</b></td>
              </tr>`).join('')}
          </tbody>
        </table>
        <div class="drill-slot"></div>
      </div>`).join('');

    $$('#rules tr.rule').forEach((tr) => {
      const open = () => openDrill({
        runId: tr.dataset.run, rule: tr.dataset.rule, severity: tr.dataset.severity, page: 0,
      });
      tr.onclick = open;
      tr.onkeydown = (e) => { if (e.key === 'Enter') open(); };
    });
  } catch (e) {
    $('#rules').innerHTML = failed(e);
  }
}

/**
 * 누른 규칙의 레코드를 그 원천 묶음 바로 아래에 펼친다. 한 번에 하나만 연다 —
 * 두 원천을 같은 규칙으로 비교하고 싶으면 번갈아 누르면 된다.
 */
async function openDrill(query) {
  drill = query;
  let box = null;
  $$('#rules tr.rule').forEach((tr) => {
    const on = tr.dataset.run === query.runId && tr.dataset.rule === query.rule;
    tr.classList.toggle('is-open', on);
    if (on) box = tr.closest('.rule-group').querySelector('.drill-slot');
  });
  $$('#rules .drill-slot').forEach((slot) => { if (slot !== box) slot.innerHTML = ''; });
  if (!box) return;
  box.innerHTML = notice('불러오는 중…');
  try {
    const page = await api.adminValidationResults({ ...query, size: PAGE_SIZE });
    if (drill !== query) return;  // 그 사이 다른 규칙을 눌렀다
    box.innerHTML = drillTable(query, page);
    $('#prev', box)?.addEventListener('click', () => openDrill({ ...query, page: query.page - 1 }));
    $('#next', box)?.addEventListener('click', () => openDrill({ ...query, page: query.page + 1 }));
    $('#close', box)?.addEventListener('click', () => {
      drill = null;
      box.innerHTML = '';
      $$('#rules tr.rule').forEach((tr) => tr.classList.remove('is-open'));
    });
  } catch (e) {
    box.innerHTML = failed(e);
  }
}

function drillTable(query, page) {
  const from = page.page * page.size + 1;
  const to = page.page * page.size + page.items.length;
  const last = to >= page.total;
  return `
    <div class="drill">
      <div class="drill-bar">
        <b>${esc(RULES[query.rule]?.[0] ?? query.rule)}</b>
        <span class="meta">회차 #${query.runId} · ${num(from)}–${num(to)} / ${num(page.total)}</span>
        <span class="spacer"></span>
        <button id="prev" ${page.page === 0 ? 'disabled' : ''}>이전</button>
        <button id="next" ${last ? 'disabled' : ''}>다음</button>
        <button id="close" class="link">닫기</button>
      </div>
      <div class="scroll">
        <table class="grid">
          <thead><tr><th class="id">인허가번호</th><th>내용</th></tr></thead>
          <tbody>
            ${page.items.map((v) => `
              <tr>
                <td><code>${esc(v.targetId ?? '')}</code></td>
                <td>${detail(v.detail)}</td>
              </tr>`).join('')}
          </tbody>
        </table>
      </div>
    </div>`;
}

/** 규칙마다 detail 의 모양이 다르다. 키·값을 그대로 늘어놓는다. */
function detail(d) {
  if (!d || typeof d !== 'object') return '<span class="meta">—</span>';
  return Object.entries(d)
    .map(([k, v]) => `<span class="kv"><span class="meta">${esc(k)}</span> ${value(v)}</span>`)
    .join('');
}

function value(v) {
  if (v === null || v === undefined || v === '') return '<span class="meta">(비어 있음)</span>';
  return esc(typeof v === 'object' ? JSON.stringify(v) : v);
}

// ── 그래프 ────────────────────────────────────────────────────────────────

async function loadGraph() {
  try {
    const { active, counts } = await api.adminGraphStats();
    if (!active) {
      $('#graph').innerHTML = notice('활성 그래프가 없습니다. <code>python -m etl.build_graph</code> 로 빌드하세요.');
      return;
    }
    $('#graph').innerHTML = `
      <div class="facts">
        <div><span class="meta">활성 빌드</span><b>#${active.id}</b></div>
        <div><span class="meta">빌드 시간</span><b>${duration(active.durationMs)}</b></div>
        <div><span class="meta">빌드 완료</span><b>${when(active.finishedAt)}</b></div>
        <div title="서울교통공사 환승 데이터에 없어 기본값(180초)을 쓴 환승 쌍의 비율">
          <span class="meta">환승 폴백 비율</span><b>${pct(active.transferFallbackRatio)}</b></div>
        <div><span class="meta">고립 노드</span><b>${num(active.isolatedNodes)}</b></div>
      </div>
      <div class="two">
        ${modeTable('노드', counts.filter((c) => c.what === 'NODE'))}
        ${modeTable('엣지', counts.filter((c) => c.what === 'EDGE'))}
      </div>`;
  } catch (e) {
    $('#graph').innerHTML = failed(e);
  }
}

function modeTable(title, rows) {
  const kinds = [...new Set(rows.map((r) => r.kind))];
  const cell = (kind, mode) => rows.find((r) => r.kind === kind && r.mode === mode)?.count;
  const total = (mode) => rows.filter((r) => r.mode === mode).reduce((s, r) => s + r.count, 0);
  return `
    <table class="grid">
      <thead>
        <tr><th>${title}</th>${MODES.map((m) => `<th class="n">${MODE_LABEL[m]}</th>`).join('')}</tr>
      </thead>
      <tbody>
        ${kinds.map((k) => `
          <tr>
            <td>${esc(KINDS[k] ?? k)} <code class="meta">${esc(k)}</code></td>
            ${MODES.map((m) => `<td class="n">${num(cell(k, m))}</td>`).join('')}
          </tr>`).join('')}
      </tbody>
      <tfoot>
        <tr><td>합계</td>${MODES.map((m) => `<td class="n"><b>${num(total(m) || null)}</b></td>`).join('')}</tr>
      </tfoot>
    </table>`;
}

// ── 표시 ──────────────────────────────────────────────────────────────────

const STATUS_CLASS = { SUCCESS: 'ok', SKIPPED: 'skip', FAILED: 'bad', RUNNING: 'run', ERROR: 'bad', WARN: 'warn' };

const badge = (s) => `<span class="badge ${STATUS_CLASS[s] ?? ''}">${esc(s)}</span>`;

const num = (n) => (n === null || n === undefined ? '<span class="meta">—</span>' : Number(n).toLocaleString('ko-KR'));

const pct = (r) => (r === null || r === undefined ? '—' : `${(r * 100).toFixed(1)}%`);

function duration(ms) {
  if (ms === null || ms === undefined) return '<span class="meta">—</span>';
  const s = Math.round(ms / 1000);
  return s < 60 ? `${s}초` : `${Math.floor(s / 60)}분 ${s % 60}초`;
}

function when(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  const pad = (v) => String(v).padStart(2, '0');
  return `${d.getMonth() + 1}/${d.getDate()} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function groupBy(list, key) {
  return list.reduce((acc, x) => ((acc[key(x)] ??= []).push(x), acc), {});
}

function failed(e) {
  return notice(e instanceof ApiError ? esc(e.message) : '불러오지 못했습니다.', 'bad');
}
