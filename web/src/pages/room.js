/**
 * 방 화면 — 지도 + 참가자 + 후보 장소.
 *
 * 서버의 `GET /rooms/{id}` 하나가 화면 전체의 상태다. 무엇을 하든 끝나면 그것을 다시 받아
 * 통째로 다시 그린다. 부분 갱신을 하지 않으므로 화면과 서버가 어긋날 자리가 없다.
 */

import { api, ApiError } from '../api.js';
import { render, $, $$, esc, notice, busy, formatCode } from '../dom.js';
import { loadKakao, MapUnavailable, mapNotice, createMap } from '../kakao.js';
import { matrixPanel, resetMatrix } from './matrix.js';

/** 출발지와 후보를 색으로 가른다. */
const ORIGIN_COLOR = '#2563eb';
const BOOKMARK_COLOR = '#e11d48';
/** 장소 찾기 결과. 아직 후보가 아니라는 뜻으로 가라앉은 색을 쓴다. */
const FOUND_COLOR = '#64748b';

/** 다른 사람이 뭔가 바꿨는지 확인하는 주기(ms). 폴링으로 충분하다 — 실시간이 아니어도 된다. */
const POLL_MS = 5000;

let poller = null;
let mapView = null;
let state = null;
let roomId = null;

/** 장소 찾기 결과. 지도에 회색 마커로 찍고, 누르면 카드가 뜬다. */
let found = [];
/**
 * 열어 둔 카드. `{ poiId }` 또는 `{ bookmarkId }`(지도에서 직접 찍은 후보).
 * 5초마다 상태를 다시 받아 지도를 통째로 다시 그리면 카드도 사라지므로, 무엇을 열어
 * 뒀는지 기억해 두었다가 다시 연다.
 */
let openKey = null;
/** 북마크만 있고 검색 결과에는 없는 장소의 상세. 카드를 채우려고 한 번만 받는다. */
const placeCache = new Map();

/** 화면을 떠날 때 폴링을 멈춘다. 안 멈추면 방을 나가도 요청이 계속 나간다. */
export function leaveRoom() {
  mapView?.clearRoute();
  clearInterval(poller);
  poller = null;
  mapView?.destroy();
  mapView = null;
  state = null;
  found = [];
  searched = false;
  openKey = null;
  pickMode = null;
  matrix = null;
  resetMatrix();
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
  mapView?.fit();  // 처음 들어올 때만 전체가 보이게 맞춘다

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
        <span class="invite">
          초대 코드
          <code id="code" class="code-chip" title="누르면 복사됩니다">${esc(formatCode(state.inviteCode))}</code>
        </span>
        <button id="share" class="link">링크 복사</button>
        ${state.iAmOwner ? '<button id="regen" class="link">코드 재발급</button>' : ''}
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
            <button class="tab" data-panel="matrix">이동시간</button>
          </div>

          <section id="panel-members" class="panel-body"></section>
          <section id="panel-bookmarks" class="panel-body" hidden></section>
          <section id="panel-find" class="panel-body" hidden>
            <form id="find-form" class="row-form">
              <input id="find-q" type="search" placeholder="상호명으로 찾기" autocomplete="off">
              <button class="primary" type="submit">검색</button>
            </form>
            <p class="hint">
              결과는 지도에 회색 핀으로 찍힙니다. 핀이나 목록을 누르면 정보가 뜨고,
              거기서 <b>북마크</b>를 눌러 후보로 담습니다.
            </p>
            <div id="find-results"></div>
          </section>
          <section id="panel-matrix" class="panel-body" hidden></section>
        </aside>
      </div>
    </div>
  `);

  const flash = () => {
    const done = $('#share-done');
    done.hidden = false;
    setTimeout(() => { done.hidden = true; }, 1500);
  };

  $('#share').onclick = async () => {
    await navigator.clipboard.writeText(window.location.href);
    flash();
  };

  // 코드를 눌러도 복사된다. 전화로 불러줄 때는 눈으로 읽고, 채팅으로 보낼 때는 복사한다.
  $('#code').onclick = async () => {
    await navigator.clipboard.writeText(state.inviteCode);
    flash();
  };

  $('#regen')?.addEventListener('click', (e) =>
    busy(e.target, async () => {
      // 되돌릴 수 없다 — 이미 코드를 받은 사람은 못 들어오게 된다.
      if (!confirm('코드를 새로 뽑으면 지금 코드는 더 이상 쓸 수 없습니다. 바꿀까요?')) return;
      try {
        await api.regenerateCode(roomId);
        await roomPage({ roomId });
      } catch (err) {
        alert(err instanceof ApiError ? err.message : '바꾸지 못했습니다.');
      }
    }));

  $$('.tab').forEach((tab) => {
    tab.onclick = () => {
      $$('.tab').forEach((t) => t.classList.toggle('is-on', t === tab));
      $$('.panel-body').forEach((p) => {
        p.hidden = p.id !== `panel-${tab.dataset.panel}`;
      });
      // 행렬은 탭을 처음 열 때 만든다. 방에 들어오자마자 계산하지 않는다 —
      // 재료가 없으면 실패할 뿐이고, 있어도 사용자가 원할 때 돌려야 한다.
      // 표를 340px 에 우겨넣으면 읽을 수 없다. 행렬 탭에서만 패널을 넓힌다.
      $('.side').classList.toggle('wide', tab.dataset.panel === 'matrix');
      if (tab.dataset.panel === 'matrix' && !matrix) {
        matrix = matrixPanel($('#panel-matrix'), roomId, {
          onRoute: (legs) => {
            if (!mapView) return;
            if (!legs) {
              mapView.clearRoute();
              return;
            }
            mapView.closeCard();
            openKey = null;
            mapView.drawRoute(legs);
            mapView.fit(legs.flatMap((l) => l.path ?? []).map(([lng, lat]) => ({ lng, lat })));
          },
          onLeg: (leg) => {
            const pts = (leg?.path ?? []).map(([lng, lat]) => ({ lng, lat }));
            if (pts.length === 1) mapView?.panTo(pts[0]);
            else if (pts.length) mapView?.fit(pts);
          },
        });
      }
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
let matrix = null;

/**
 * 마커를 다시 그린다. **지도 위치는 건드리지 않는다** — 5초마다 다시 그릴 때마다 전체가 보이게
 * 맞추면, 카드를 보며 북마크를 누를 때마다 지도가 튄다. 맞추는 것은 들어올 때와 검색할 때뿐이다.
 */
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

  // 이미 후보인 곳은 빨간 마커 하나로. 검색 결과에도 있으면 회색을 겹쳐 찍지 않는다.
  const bookmarked = new Set(state.bookmarks.map((b) => b.poiId).filter((id) => id != null));
  found.filter((p) => !bookmarked.has(p.poiId)).forEach((p) => {
    mapView.addMarker({
      lat: p.lat, lng: p.lng, label: p.name, color: FOUND_COLOR,
      onClick: () => openPlaceCard({ poiId: p.poiId }),
    });
  });

  state.bookmarks.forEach((b) => {
    mapView.addMarker({
      lat: b.lat, lng: b.lng, label: b.name, color: BOOKMARK_COLOR,
      onClick: () => openPlaceCard(b.poiId != null ? { poiId: b.poiId } : { bookmarkId: b.bookmarkId }),
    });
  });

  if (openKey) openPlaceCard(openKey);
}

// ── 정보 카드 ───────────────────────────────────────────────────────────────

function bookmarkFor(key) {
  return key.poiId != null
    ? state.bookmarks.find((b) => b.poiId === key.poiId)
    : state.bookmarks.find((b) => b.bookmarkId === key.bookmarkId);
}

const sameKey = (a, b) => !!a && !!b && a.poiId === b.poiId && a.bookmarkId === b.bookmarkId;

/**
 * 장소 카드를 연다. 마커를 눌러도, 목록 줄을 눌러도 여기로 온다 — **누른다고 담기지 않는다.**
 * 담기·빼기는 카드의 버튼으로만 한다.
 */
async function openPlaceCard(key) {
  if (!mapView || !state) return;
  const bookmark = bookmarkFor(key);
  let place = key.poiId != null
    ? found.find((p) => p.poiId === key.poiId) ?? placeCache.get(key.poiId)
    : null;
  const at = place ?? bookmark;
  if (!at) {
    // 지도에서 찍은 후보를 뺐다. 더 보여 줄 것이 없다.
    openKey = null;
    mapView.closeCard();
    return;
  }
  openKey = key;
  mapView.openCard({ lat: at.lat, lng: at.lng }, placeCard(key, place, bookmark));

  // 후보로만 알고 있는 장소는 업종·영업상태를 모른다. 상세를 받아 카드를 다시 채운다.
  if (key.poiId != null && !place) {
    try {
      place = (await api.place(key.poiId)).place;
      placeCache.set(key.poiId, place);
      if (sameKey(openKey, key)) {
        mapView.openCard({ lat: place.lat, lng: place.lng }, placeCard(key, place, bookmarkFor(key)));
      }
    } catch {
      // 상세를 못 받아도 이름·주소는 이미 보이고 있다
    }
  }
}

function placeCard(key, place, bookmark) {
  const el = document.createElement('div');
  el.className = 'place-card';

  const name = place?.name ?? bookmark?.name ?? '';
  const category = place?.categoryRaw ?? place?.categoryCode;
  const address = place?.roadAddress ?? place?.jibunAddress ?? bookmark?.address;
  const closed = place?.status === 'CLOSED';

  let action = '';
  let who = '';
  if (!bookmark) {
    action = '<button class="primary" data-act="add">북마크</button>';
  } else if (bookmark.addedByMe) {
    who = '내가 담은 후보';
    action = '<button data-act="remove">북마크 취소</button>';
  } else {
    // 담은 사람만 뺄 수 있다(서버가 403). 누를 수 없는 버튼을 두지 않고 누가 담았는지 적는다.
    who = `${esc(bookmark.addedByNickname)}님이 담음`;
  }

  el.innerHTML = `
    <button class="card-x" data-act="close" aria-label="닫기">×</button>
    <div class="card-title">
      <b>${esc(name)}</b>
      ${category ? `<span class="card-cat">${esc(category)}</span>` : ''}
    </div>
    ${address ? `<div class="card-addr">${esc(address)}</div>` : ''}
    <div class="card-meta">
      ${place ? `<span class="${closed ? 'warn' : 'ok'}">${closed ? '폐업' : '영업 중'}</span>` : ''}
      ${place?.phone ? `<span>${esc(formatPhone(place.phone))}</span>` : ''}
      ${!place && bookmark?.poiId == null ? '<span>지도에서 직접 찍은 곳</span>' : ''}
    </div>
    <div class="card-foot">
      <span class="meta">${who}</span>
      ${action}
    </div>`;

  el.querySelector('[data-act="close"]').onclick = () => {
    openKey = null;
    mapView.closeCard();
  };
  el.querySelector('[data-act="add"]')?.addEventListener('click', (e) => busy(e.currentTarget, async () => {
    try {
      await api.addBookmark(roomId, { poiId: key.poiId });
      await refresh();  // 다시 그리면서 같은 카드를 "북마크 취소" 로 다시 연다
    } catch (err) {
      alert(err instanceof ApiError ? err.message : '담지 못했습니다.');
    }
  }));
  el.querySelector('[data-act="remove"]')?.addEventListener('click', (e) => busy(e.currentTarget, async () => {
    try {
      await api.removeBookmark(roomId, bookmark.bookmarkId);
      await refresh();
    } catch (err) {
      alert(err instanceof ApiError ? err.message : '빼지 못했습니다.');
    }
  }));
  return el;
}

/** `022643207` → `02-264-3207`. 모양을 모르면 그대로 둔다. */
function formatPhone(raw) {
  const d = String(raw).replace(/\D/g, '');
  if (d.startsWith('02')) {
    if (d.length === 9) return `02-${d.slice(2, 5)}-${d.slice(5)}`;
    if (d.length === 10) return `02-${d.slice(2, 6)}-${d.slice(6)}`;
  } else if (d.length === 10) {
    return `${d.slice(0, 3)}-${d.slice(3, 6)}-${d.slice(6)}`;
  } else if (d.length === 11) {
    return `${d.slice(0, 3)}-${d.slice(3, 7)}-${d.slice(7)}`;
  }
  return raw;
}

// ── 패널 ────────────────────────────────────────────────────────────────────

function paintPanels() {
  paintMembers();
  paintBookmarks();
  paintFound();
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
    found = places;
    openKey = null;
    searched = true;
    paintFound();
    paintMarkers();
    if (places.length) mapView?.fit(places);
  } catch {
    box.innerHTML = notice('검색에 실패했습니다.', 'bad');
  }
}

/**
 * 검색 결과 목록. 상태가 바뀔 때마다(담기·빼기·다른 사람의 변경) 다시 그려 "후보" 표시를 맞춘다.
 * 검색할 때 한 번만 그리면 담아도 목록에는 표시가 안 붙는다.
 */
let searched = false;

function paintFound() {
  const box = $('#find-results');
  if (!box || !searched) return;
  const bookmarked = new Set(state.bookmarks.map((b) => b.poiId));
  box.innerHTML = found.length
    ? `<ul class="list">
        ${found.map((p) => `
          <li class="clickable" data-poi="${p.poiId}" title="누르면 지도에 정보가 뜹니다">
            <b>${esc(p.name)}</b>
            ${bookmarked.has(p.poiId) ? '<span class="tag">후보</span>' : ''}
            ${p.distanceM != null ? `<span class="dist">${Math.round(p.distanceM)}m</span>` : ''}
            <div class="meta">${esc(p.roadAddress ?? p.jibunAddress ?? '')}</div>
          </li>`).join('')}
       </ul>`
    : notice('결과가 없습니다.');

  // 줄을 눌러도 바로 담지 않는다. 지도에서 그 자리를 보여 주고 카드에서 고르게 한다.
  $$('#find-results .clickable').forEach((row) => {
    row.onclick = () => {
      const p = found.find((x) => x.poiId === Number(row.dataset.poi));
      if (!p) return;
      mapView?.panTo(p);
      openPlaceCard({ poiId: p.poiId });
    };
  });
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
