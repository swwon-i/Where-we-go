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

const HOURS = [8, 12, 15, 19, 21, 23];

/** 눌러서 펼쳐 둔 칸. 다시 그려도 열린 채로 둔다. */
let openCell = null;

export function resetMatrix() {
  openCell = null;
}

/**
 * @param {HTMLElement} container 표를 그릴 자리
 * @param {string} roomId
 */
export function matrixPanel(container, roomId) {
  let hour = 19;
  let data = null;

  function paint() {
    container.innerHTML = `
      <div class="matrix-bar">
        <label>
          출발 시각대
          <select id="hour">
            ${HOURS.map((h) => `<option value="${h}"${h === hour ? ' selected' : ''}>${h}시</option>`).join('')}
          </select>
        </label>
        <button id="calc" class="primary">계산</button>
        <span id="matrix-stats" class="meta"></span>
      </div>
      <div id="matrix-body">${data ? table(data) : notice('계산을 눌러 보세요.')}</div>
    `;

    $('#hour', container).onchange = (e) => { hour = Number(e.target.value); };
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
      detail.innerHTML =
        `<td colspan="${data.origins.length + 1}">${legs(route)}</td>`;
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

function minutes(seconds) {
  const m = Math.round(seconds / 60);
  return m >= 60 ? `${Math.floor(m / 60)}시간 ${m % 60}분` : `${m}분`;
}

function legs(route) {
  if (!route.reachable) return notice('이 경로는 닿지 않습니다.', 'bad');
  return `
    <div class="legs">
      <div class="legs-head">${esc(route.summary)}</div>
      <ol>
        ${route.legs.map((l) => `
          <li>
            <span class="kind ${l.kind.toLowerCase()}">${label(l.kind)}</span>
            <b>${esc(l.label ?? '')}</b>
            ${l.stops ? `<span class="meta">${l.stops}정차</span>` : ''}
            ${l.kind === 'WALK' ? `<span class="meta">${Math.round(l.distanceM)}m</span>` : ''}
            <span class="meta">${minutes(l.seconds)}</span>
            ${l.toName ? `<span class="meta">→ ${esc(l.toName)}</span>` : ''}
          </li>`).join('')}
      </ol>
    </div>`;
}

const label = (kind) => ({ WALK: '도보', SUBWAY: '지하철', BUS: '버스' })[kind] ?? kind;
