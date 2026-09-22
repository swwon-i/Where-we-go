/**
 * 이동시간 행렬 표.
 *
 * ```
 *              사람 A    사람 B
 *   장소 A      20분      35분
 *               경로 자세히 보기
 *   장소 B      41분      12분
 * ```
 *
 * **행이 장소, 열이 사람이다.** 한 줄을 훑으면 그 장소가 모두에게 어떤지 보인다.
 * 사용자가 고르는 것은 장소이므로 비교 단위도 장소여야 한다.
 *
 * <p>순위를 매기지 않는다. 가장 빠른 칸을 표시해 주긴 하지만 그것은 **읽기를 돕는 것**이지
 * "여기로 가라"는 뜻이 아니다 — 누구의 시간을 얼마나 중하게 볼지는 그 모임이 정할 일이다.
 */

import { api, ApiError } from '../api.js';
import { $, $$, esc, notice, busy } from '../dom.js';
import { legColor } from '../lines.js';

/**
 * 출발 시각대는 0~23시 전부 고를 수 있다. 예전에는 8·12·15·19·21·23시만 골랐는데 근거가 없었고,
 * 결과적으로 운행 없는 시간대가 가려져 있었다. 이제 그래프가 "운행 없음"을 알므로
 * 새벽을 골라도 존재하지 않는 운행으로 경로가 나오지 않는다 — 새벽 2시는 N버스와 도보뿐이다.
 */
const DEFAULT_HOUR = 19;

/** 눌러서 펼쳐 둔 칸. 다시 그려도 열린 채로 둔다. */
let openCell = null;

export function resetMatrix() {
  openCell = null;
}

/**
 * @param {HTMLElement} container 표를 그릴 자리
 * @param {string} roomId
 * @param {{ onRoute?: (legs: object[]|null) => void, onLeg?: (leg: object) => void }} [map]
 *   방 지도와 잇는 자리. 칸을 펼치면 경로를 그리고(`onRoute(legs)`), 접으면 지운다(`onRoute(null)`).
 *   타임라인 줄을 누르면 그 구간으로 옮긴다(`onLeg`).
 */
export function matrixPanel(container, roomId, map = {}) {
  let hour = DEFAULT_HOUR;
  let data = null;

  function paint() {
    container.innerHTML = `
      <div class="matrix-bar">
        <label class="hour-pick">
          출발 시각대
          <input id="hour" type="range" min="0" max="23" step="1" value="${hour}"
                 aria-valuetext="${hour}시">
          <output id="hour-out">${hourLabel(hour)}</output>
        </label>
        <button id="calc" class="primary">계산</button>
        <span id="matrix-stats" class="meta"></span>
      </div>
      <div id="matrix-body">${data ? table(data) : notice('계산을 눌러 보세요.')}</div>
    `;

    const slider = $('#hour', container);
    // 끌면서는 표시만 바꾸고, 놓았을 때 이미 계산한 표가 있으면 새 시각대로 다시 계산한다.
    // 19시와 21시를 오가며 비교하는 것이 이 화면의 쓰임새다.
    slider.oninput = () => {
      hour = Number(slider.value);
      slider.setAttribute('aria-valuetext', `${hour}시`);
      $('#hour-out', container).textContent = hourLabel(hour);
    };
    slider.onchange = () => { if (data) calculate(); };
    $('#calc', container).onclick = (e) => busy(e.target, calculate);
    if (data) wireCells();
  }

  async function calculate() {
    $('#matrix-body', container).innerHTML = notice('계산 중…');
    try {
      data = await api.matrix(roomId, hour);
      openCell = null;
      paint();
      showStats(data.stats);
    } catch (e) {
      data = null;
      $('#matrix-body', container).innerHTML =
        notice(e instanceof ApiError ? esc(e.message) : '계산에 실패했습니다.', 'bad');
    }
  }

  function showStats(stats) {
    // 탐색 횟수가 사람 수와 같고 장소 수와 무관하다는 것이 one-to-many 의 증거다.
    $('#matrix-stats', container).textContent =
      `${stats.elapsedMs}ms · 탐색 ${stats.dijkstraRuns}회 `
      + `(사람 ${stats.originCount} × 장소 ${stats.destinationCount}) · `
      + `노드 ${stats.expandedNodes.toLocaleString()} · ${stats.graphSource}`;
  }

  function wireCells() {
    $$('#matrix-body .cell.ok', container).forEach((cell) => {
      cell.onclick = () => toggleDetail(cell);
    });
  }

  async function toggleDetail(cell) {
    const key = `${cell.dataset.row}:${cell.dataset.col}`;
    const row = cell.closest('tr');
    const existing = row.nextElementSibling?.classList.contains('detail-row')
      ? row.nextElementSibling : null;

    if (openCell === key) {
      openCell = null;
      existing?.remove();
      $$('.cell.is-open', container).forEach((c) => c.classList.remove('is-open'));
      map.onRoute?.(null);
      return;
    }

    // 한 번에 하나만 펼친다. 여러 칸이 열려 있으면 표가 읽히지 않는다.
    $$('.detail-row', container).forEach((r) => r.remove());
    $$('.cell.is-open', container).forEach((c) => c.classList.remove('is-open'));

    openCell = key;
    cell.classList.add('is-open');

    const detail = document.createElement('tr');
    detail.className = 'detail-row';
    detail.innerHTML =
      `<td colspan="${data.origins.length + 1}">${notice('경로를 불러오는 중…')}</td>`;
    row.after(detail);

    try {
      const route = await api.routeDetail(roomId, {
        originMemberId: cell.dataset.member,
        bookmarkId: cell.dataset.row,
        departureHour: hour,
      });
      if (openCell !== key) return;  // 그 사이 다른 칸을 눌렀다
      const colored = route.reachable
        ? route.legs.map((l) => ({ ...l, color: legColor(l) }))
        : [];
      detail.innerHTML =
        `<td colspan="${data.origins.length + 1}">${timeline(route, colored, hour)}</td>`;
      map.onRoute?.(colored.length ? colored : null);
      $$('.tl-leg', detail).forEach((li) => {
        li.onclick = () => map.onLeg?.(colored[Number(li.dataset.i)]);
      });
    } catch {
      detail.innerHTML =
        `<td colspan="${data.origins.length + 1}">${notice('경로를 불러오지 못했습니다.', 'bad')}</td>`;
    }
  }

  paint();
  return { recalculate: calculate };
}

// ── 그리기 ──────────────────────────────────────────────────────────────────

function table(m) {
  return `
    <table class="matrix">
      <thead>
        <tr>
          <th class="corner"></th>
          ${m.origins.map((o) => `
            <th>
              ${esc(o.nickname)}
              <div class="meta">
                ${esc(o.label ?? '')}
                ${o.resnapped ? '<span class="warn" title="그래프가 바뀌어 다시 붙였습니다">재스냅</span>' : ''}
              </div>
            </th>`).join('')}
        </tr>
      </thead>
      <tbody>
        ${m.rows.map((row) => bodyRow(row, m.origins, fastestIn(row))).join('')}
      </tbody>
    </table>
    <p class="hint">
      칸을 누르면 그 경로의 구간이 펼쳐집니다.
      <b>순위를 매기지 않습니다</b> — 가장 빠른 칸에 표시만 해 둡니다.
    </p>
  `;
}

/** 한 줄에서 가장 빠른 칸. 읽기를 돕는 표시일 뿐 추천이 아니다. */
function fastestIn(row) {
  let best = -1;
  let bestSeconds = Infinity;
  row.cells.forEach((cell, i) => {
    if (cell.reachable && cell.durationSeconds < bestSeconds) {
      bestSeconds = cell.durationSeconds;
      best = i;
    }
  });
  return best;
}

function bodyRow(row, origins, fastest) {
  return `
    <tr>
      <th class="place">
        ${esc(row.name)}
        <div class="meta">${esc(row.address ?? '')}</div>
      </th>
      ${row.cells.map((cell, i) => cellCell(row, cell, origins[i], i, i === fastest)).join('')}
    </tr>`;
}

function cellCell(row, cell, origin, index, isFastest) {
  if (!cell.reachable) {
    const why = cell.reason === 'NO_WALK_NETWORK' ? '보행망 밖' : '도달 불가';
    return `<td class="cell bad">${why}</td>`;
  }
  return `
    <td class="cell ok${isFastest ? ' fastest' : ''}"
        data-row="${row.bookmarkId}" data-col="${index}"
        data-member="${origin.memberId}"
        title="누르면 경로가 펼쳐집니다">
      <b>${minutes(cell.durationSeconds)}</b>
      <div class="meta">
        환승 ${cell.transfers} · 도보 ${Math.round(cell.walkDistanceM)}m
      </div>
    </td>`;
}

/** 새벽에는 대부분 운행하지 않는다는 것을 미리 알려 둔다. 빈칸이 많아도 놀라지 않도록. */
function hourLabel(h) {
  return h >= 1 && h <= 4 ? `${h}시 · 심야` : `${h}시`;
}

function minutes(seconds) {
  const m = Math.round(seconds / 60);
  return m >= 60 ? `${Math.floor(m / 60)}시간 ${m % 60}분` : `${m}분`;
}

/**
 * 경로 상세 — 세로 타임라인.
 *
 * 왼쪽 막대의 색이 **지도에 그린 선의 색과 같다**(lines.js). 도보는 점선, 승차·환승은 그 자리의
 * 점이고 흐리게 둔다 — "타고 가는 시간"과 "기다리는 시간"을 눈으로 가를 수 있어야 한다.
 * 줄을 누르면 지도가 그 구간으로 간다.
 */
function timeline(route, legs, hour) {
  if (!route.reachable) return notice('이 경로는 닿지 않습니다.', 'bad');
  const total = route.totalSeconds;
  return `
    <div class="timeline">
      <div class="tl-head">
        <b class="tl-total">${minutes(total)}</b>
        <span class="meta">환승 ${route.transfers} · 도보 ${Math.round(route.walkDistanceM)}m</span>
        <span class="tl-hour meta">${hour}시 출발</span>
      </div>
      <div class="tl-bar" aria-hidden="true">
        ${legs.map((l) => `<span style="flex:${Math.max(l.seconds, 1)};background:${l.color}"
            class="${l.kind === 'WALK' ? 'walk' : ''} ${WAITS[l.kind] ? 'wait' : ''}"></span>`).join('')}
      </div>
      <ol>
        ${legs.map((l, i) => `
          <li class="tl-leg ${l.kind.toLowerCase()}" data-i="${i}" style="--c:${l.color}"
              title="누르면 지도가 이 구간으로 갑니다">
            <span class="tl-rail"></span>
            <div class="tl-body">
              <div class="tl-line">
                <span class="tl-kind">${label(l.kind)}</span>
                ${l.label ? `<b class="tl-name">${esc(l.label)}</b>` : ''}
                ${l.stops ? `<span class="meta">${l.stops}정차</span>` : ''}
                ${l.kind === 'WALK' ? `<span class="meta">${Math.round(l.distanceM)}m</span>` : ''}
                <span class="tl-time">${minutes(l.seconds)}</span>
              </div>
              <div class="tl-sub meta">
                ${WAITS[l.kind]
                  ? `${esc(l.toName ?? '')} · ${WAITS[l.kind]}`
                  : (l.toName ? `→ ${esc(l.toName)}` : (i === legs.length - 1 ? '→ 도착' : ''))}
              </div>
            </div>
          </li>`).join('')}
      </ol>
    </div>`;
}

/** 기다리는 줄에 무엇이 들어 있는지. 시간대를 바꾸면 여기가 달라진다. */
const WAITS = {
  BOARD: '승강장까지 + 대기',
  TRANSFER: '갈아타기 + 대기',
};

const label = (kind) => ({
  WALK: '도보', SUBWAY: '지하철', BUS: '버스', BOARD: '승차', TRANSFER: '환승',
})[kind] ?? kind;
