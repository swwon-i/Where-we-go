/**
 * 방 화면 — 지도 + 참가자 + 후보 장소.
 *
 * 서버의 `GET /rooms/{id}` 하나가 화면 전체의 상태다. 무엇을 하든 끝나면 그것을 다시 받아
 * 통째로 다시 그린다. 부분 갱신을 하지 않으므로 화면과 서버가 어긋날 자리가 없다.
 */

import { api, ApiError } from '../api.js';
import { render, $, $$, esc, notice, busy } from '../dom.js';
import { loadKakao, MapUnavailable, mapNotice, createMap } from '../kakao.js';

/** 출발지와 후보를 색으로 가른다. */
const ORIGIN_COLOR = '#2563eb';
const BOOKMARK_COLOR = '#e11d48';

/** 다른 사람이 뭔가 바꿨는지 확인하는 주기(ms). 폴링으로 충분하다 — 실시간이 아니어도 된다. */
const POLL_MS = 5000;

let poller = null;
let mapView = null;
let state = null;
let roomId = null;

/** 화면을 떠날 때 폴링을 멈춘다. 안 멈추면 방을 나가도 요청이 계속 나간다. */
export function leaveRoom() {
  clearInterval(poller);
  poller = null;
  mapView?.destroy();
  mapView = null;
  state = null;
  pickMode = null;
}

export async function roomPage({ roomId: id }) {
  roomId = id;

  let room;
  try {
    room = await api.room(roomId);
  } catch (e) {
    if (e instanceof ApiError && e.status === 403) return joinPrompt();
    if (e instanceof ApiError && e.status === 404) {
      return render(notice('없는 방입니다. 주소를 다시 확인해 주세요.', 'bad'));
    }
    return render(notice('방을 불러오지 못했습니다.', 'bad'));
  }

  state = room;
  paintShell();
  await setUpMap();
  paintPanels();

  clearInterval(poller);
  poller = setInterval(refresh, POLL_MS);
}

/** 참가하지 않은 사람에게 보이는 화면. 방 링크는 초대장이지 열람권이 아니다. */
function joinPrompt() {
  render(`
    <div class="center-card">
      <h2>초대받은 방</h2>
      <p class="sub">들어가면 참가자와 후보 장소를 볼 수 있습니다.</p>
      <button id="join" class="primary">참가하기</button>
    </div>
  `);
  $('#join').onclick = (e) =>
    busy(e.target, async () => {
      await api.joinRoom(roomId);
      await roomPage({ roomId });
    });
}

function paintShell() {
  render(`
    <div class="room">
      <div class="room-head">
        <h2>${esc(state.title)}</h2>
        <span class="meta">${esc(state.ownerNickname)}의 방</span>
        <button id="share" class="link">링크 복사</button>
        <span id="share-done" class="ok" hidden>복사했습니다</span>
        <span id="matrix-state" class="badge"></span>
      </div>

      <div class="room-body">
        <div id="map" class="map"></div>

        <aside class="side">
          <div class="tabs" role="tablist">
            <button class="tab is-on" data-panel="members">참가자</button>
            <button class="tab" data-panel="bookmarks">후보</button>
            <button class="tab" data-panel="find">장소 찾기</button>
          </div>

          <section id="panel-members" class="panel-body"></section>
          <section id="panel-bookmarks" class="panel-body" hidden></section>
          <section id="panel-find" class="panel-body" hidden>
            <form id="find-form" class="row-form">
              <input id="find-q" type="search" placeholder="상호명으로 찾기" autocomplete="off">
              <button class="primary" type="submit">검색</button>
            </form>
            <p class="hint">
              결과를 누르면 후보로 담깁니다.
              지도를 직접 눌러 담을 수도 있어요 — 아래 <b>지도에서 고르기</b>를 켜세요.
            </p>
            <div id="find-results"></div>
          </section>
        </aside>
      </div>
    </div>
  `);

  $('#share').onclick = async () => {
    await navigator.clipboard.writeText(window.location.href);
    const done = $('#share-done');
    done.hidden = false;
    setTimeout(() => { done.hidden = true; }, 1500);
  };

  $$('.tab').forEach((tab) => {
    tab.onclick = () => {
      $$('.tab').forEach((t) => t.classList.toggle('is-on', t === tab));
      $$('.panel-body').forEach((p) => {
        p.hidden = p.id !== `panel-${tab.dataset.panel}`;
      });
    };
  });

  $('#find-form').onsubmit = (e) => {
    e.preventDefault();
    findPlaces();
  };
}

// ── 지도 ────────────────────────────────────────────────────────────────────

async function setUpMap() {
  const container = $('#map');
  try {
    await loadKakao();
  } catch (e) {
    container.innerHTML = notice(mapNotice(e instanceof MapUnavailable ? e.message : ''), 'bad');
    return;
  }

  const center = state.bookmarks[0]
    ?? state.members.find((m) => m.originSet)
    ?? { lat: 37.4979, lng: 127.0276 };

  mapView = createMap(container, { lat: center.lat ?? center.originLat, lng: center.lng ?? center.originLng });

  // 지도를 눌러 무엇을 할지는 모드가 정한다. 같은 클릭이 상황에 따라 다른 뜻이 되면
  // 사용자가 예측할 수 없으므로, 켜 둔 동안만 동작하게 한다.
  mapView.onClick(async (point) => {
    if (pickMode === 'origin') {
      await setOrigin({ ...point, label: `지도에서 고른 곳` });
      pickMode = null;
      paintPanels();
    } else if (pickMode === 'bookmark') {
      const name = prompt('이 자리의 이름을 붙여 주세요', '새 후보');
      if (name?.trim()) {
        await api.addBookmark(roomId, { name: name.trim(), ...point });
        pickMode = null;
        await refresh();
      }
    }
  });
}

let pickMode = null;

function paintMarkers() {
  if (!mapView) return;
  mapView.clear();

  state.members.filter((m) => m.originSet).forEach((m) => {
    mapView.addMarker({
      lat: m.originLat,
      lng: m.originLng,
      label: `${m.nickname} 출발`,
      color: ORIGIN_COLOR,
    });
  });

  state.bookmarks.forEach((b) => {
    mapView.addMarker({ lat: b.lat, lng: b.lng, label: b.name, color: BOOKMARK_COLOR });
  });

  mapView.fit();
}

// ── 패널 ────────────────────────────────────────────────────────────────────

function paintPanels() {
  paintMembers();
  paintBookmarks();
  paintMarkers();

  const badge = $('#matrix-state');
  badge.textContent = state.matrixReady ? '행렬 계산 가능' : '출발지와 후보가 각각 하나 이상 필요';
  badge.classList.toggle('ready', state.matrixReady);
}

function paintMembers() {
  const me = state.members.find((m) => m.isMe);

  $('#panel-members').innerHTML = `
    <ul class="list">
      ${state.members.map((m) => `
        <li class="${m.isMe ? 'mine' : ''}">
          <b>${esc(m.nickname)}</b>${m.isMe ? ' <span class="tag">나</span>' : ''}
          <div class="meta">
            ${m.originSet
              ? `${esc(m.originLabel)}
                 <span class="snap" title="출발지에서 보행망까지의 거리">
                   스냅 ${Math.round(m.originSnapM)}m
                 </span>
                 ${m.originStale ? '<span class="warn" title="그래프가 새로 빌드됐습니다. 계산 전에 다시 붙입니다">재스냅 필요</span>' : ''}`
              : '<span class="muted">출발지 미정</span>'}
          </div>
        </li>`).join('')}
    </ul>

    <div class="origin-box">
      <h3>${me?.originSet ? '내 출발지 바꾸기' : '내 출발지 정하기'}</h3>
      <form id="origin-form" class="row-form">
        <input id="origin-station" type="text" placeholder="지하철역 이름 (예: 강남)" autocomplete="off">
        <button class="primary" type="submit">설정</button>
      </form>
      <button id="pick-origin" class="link">지도에서 고르기</button>
      <em id="origin-error" class="form-error" hidden></em>
    </div>
  `;

  $('#origin-form').onsubmit = async (e) => {
    e.preventDefault();
    const station = $('#origin-station').value.trim();
    if (!station) return;
    await busy($('#origin-form button'), () => setOrigin({ station }));
  };

  $('#pick-origin').onclick = () => {
    pickMode = pickMode === 'origin' ? null : 'origin';
    $('#pick-origin').textContent =
      pickMode === 'origin' ? '지도를 눌러 주세요 (취소하려면 다시 클릭)' : '지도에서 고르기';
  };
}

async function setOrigin(origin) {
  const box = $('#origin-error');
  try {
    await api.setOrigin(roomId, origin);
    await refresh();
  } catch (e) {
    if (!box) return;
    box.textContent = e instanceof ApiError ? e.message : '설정하지 못했습니다.';
    box.hidden = false;
  }
}

function paintBookmarks() {
  $('#panel-bookmarks').innerHTML = `
    ${state.bookmarks.length
      ? `<ul class="list">
          ${state.bookmarks.map((b) => `
            <li>
              <b>${esc(b.name)}</b>
              ${b.addedByMe ? `<button class="link del" data-id="${b.bookmarkId}">빼기</button>` : ''}
              <div class="meta">
                ${esc(b.address ?? '주소 없음')}
                <span class="muted">· ${esc(b.addedByNickname)}</span>
              </div>
            </li>`).join('')}
         </ul>`
      : notice('아직 후보가 없습니다. <b>장소 찾기</b>에서 담아 보세요.')}
    <button id="pick-bookmark" class="link">지도에서 고르기</button>
  `;

  $$('#panel-bookmarks .del').forEach((button) => {
    button.onclick = () =>
      busy(button, async () => {
        try {
          await api.removeBookmark(roomId, Number(button.dataset.id));
          await refresh();
        } catch (e) {
          alert(e instanceof ApiError ? e.message : '빼지 못했습니다.');
        }
      });
  });

  $('#pick-bookmark').onclick = () => {
    pickMode = pickMode === 'bookmark' ? null : 'bookmark';
    $('#pick-bookmark').textContent =
      pickMode === 'bookmark' ? '지도를 눌러 주세요 (취소하려면 다시 클릭)' : '지도에서 고르기';
  };
}

// ── 장소 찾기 ───────────────────────────────────────────────────────────────

async function findPlaces() {
  const box = $('#find-results');
  const q = $('#find-q').value.trim();
  if (!q) return;

  box.innerHTML = notice('찾는 중…');
  const center = mapView?.center() ?? { lat: 37.4979, lng: 127.0276 };

  try {
    const places = await api.search({ q, lng: center.lng, lat: center.lat, radius: 5000, limit: 30 });
    box.innerHTML = places.length
      ? `<ul class="list">
          ${places.map((p) => `
            <li class="clickable" data-poi="${p.poiId}">
              <b>${esc(p.name)}</b>
              ${p.distanceM != null ? `<span class="dist">${Math.round(p.distanceM)}m</span>` : ''}
              <div class="meta">${esc(p.roadAddress ?? p.jibunAddress ?? '')}</div>
            </li>`).join('')}
         </ul>`
      : notice('결과가 없습니다.');

    $$('#find-results .clickable').forEach((row) => {
      row.onclick = async () => {
        try {
          await api.addBookmark(roomId, { poiId: Number(row.dataset.poi) });
          row.classList.add('added');
          await refresh();
        } catch (e) {
          row.classList.add('failed');
          row.title = e instanceof ApiError ? e.message : '담지 못했습니다';
        }
      };
    });
  } catch {
    box.innerHTML = notice('검색에 실패했습니다.', 'bad');
  }
}

// ── 갱신 ────────────────────────────────────────────────────────────────────

async function refresh() {
  try {
    const next = await api.room(roomId);
    // 바뀐 게 없으면 다시 그리지 않는다. 검색 결과를 보고 있는 중에 화면이 깜빡이면 거슬린다.
    if (JSON.stringify(next) === JSON.stringify(state)) return;
    state = next;
    paintPanels();
  } catch {
    // 일시적인 실패는 넘긴다. 다음 주기에 다시 시도한다.
  }
}
