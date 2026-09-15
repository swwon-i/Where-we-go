/**
 * 장소 검색 목업.
 *
 * 프레임워크를 쓰지 않는다 — 지도 하나와 목록 하나에 상태 관리 라이브러리를 세울 이유가 없다.
 * 행렬 표를 만들 때(계획서 3주 전반) 상태가 실제로 복잡해지면 그때 옮긴다.
 */

const API = '/api/v1';
const GANGNAM = { lat: 37.4979, lng: 127.0276 };

let map = null;
let markers = [];
let infowindow = null;

const $ = (id) => document.getElementById(id);
const setStat = (text) => { $('stat').textContent = text; };

const escapeHtml = (s) =>
  String(s ?? '').replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

/** 카카오맵 키는 HTML 에 박지 않고 서버에서 받는다 (저장소에 키가 들어가지 않게). */
async function loadKakaoSdk() {
  const { kakaoJsKey } = await fetch(`${API}/client-config`).then((r) => r.json());
  if (!kakaoJsKey) throw new Error('NO_KEY');

  await new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${kakaoJsKey}&autoload=false`;
    script.onload = () => window.kakao.maps.load(resolve);
    script.onerror = () => reject(new Error('SDK_LOAD_FAILED'));
    document.head.appendChild(script);
  });
}

function showMapNotice(reason) {
  const body = reason === 'NO_KEY'
    ? `카카오맵 JavaScript 키가 없습니다.<br><br>
       1. <code>developers.kakao.com</code> 에서 앱을 만들고 <b>JavaScript 키</b>를 복사<br>
       2. 플랫폼 &gt; Web 에 <code>http://localhost:8080</code> 과 <code>http://localhost:5173</code> 등록<br>
       3. <b>저장소 루트</b>의 <code>.env</code> 에 <code>WWG_KAKAO_JS_KEY=발급받은키</code><br>
       4. <code>docker compose up -d server</code> 로 재시작<br><br>
       키 없이도 목록 검색은 그대로 동작합니다.`
    : `카카오맵 SDK 를 불러오지 못했습니다. 키가 유효한지, 플랫폼에
       현재 주소가 등록됐는지 확인하세요.`;
  $('map').innerHTML = `<div class="notice">${body}</div>`;
}

function clearMarkers() {
  markers.forEach((m) => m.setMap(null));
  markers = [];
}

function openInfo(marker, place) {
  if (!marker) return;
  infowindow?.close();
  infowindow = new window.kakao.maps.InfoWindow({
    content: `<div style="padding:6px 10px;font-size:13px;white-space:nowrap">${escapeHtml(place.name)}</div>`,
  });
  infowindow.open(map, marker);
}

function render(places) {
  clearMarkers();

  $('list').innerHTML = places.length
    ? places.map((p, i) => `
        <div class="row" data-i="${i}">
          ${p.distanceM != null ? `<span class="dist">${Math.round(p.distanceM)}m</span>` : ''}
          <b>${escapeHtml(p.name)}</b>
          <div class="meta">${escapeHtml(p.categoryRaw)}${p.status === 'CLOSED' ? ' · 폐업' : ''}</div>
          <div class="meta">${escapeHtml(p.roadAddress ?? p.jibunAddress)}</div>
        </div>`).join('')
    : '<div class="notice">결과가 없습니다.</div>';

  if (map) {
    places.forEach((p) => {
      const marker = new window.kakao.maps.Marker({
        map,
        position: new window.kakao.maps.LatLng(p.lat, p.lng),
        title: p.name,
      });
      window.kakao.maps.event.addListener(marker, 'click', () => openInfo(marker, p));
      markers.push(marker);
    });
  }

  $('list').querySelectorAll('.row').forEach((row) => {
    row.onclick = () => {
      const i = Number(row.dataset.i);
      if (!map) return;
      map.panTo(new window.kakao.maps.LatLng(places[i].lat, places[i].lng));
      openInfo(markers[i], places[i]);
    };
  });
}

async function search() {
  const center = map?.getCenter();
  const lng = center ? center.getLng() : GANGNAM.lng;
  const lat = center ? center.getLat() : GANGNAM.lat;

  const q = $('q').value.trim();
  const params = new URLSearchParams({
    lng, lat,
    radius: $('radius').value,
    includeClosed: $('includeClosed').checked,
    limit: 100,
  });
  if (q) params.set('q', q);

  const url = `${API}/places/${q ? 'search' : 'nearby'}?${params}`;

  setStat('검색 중…');
  const started = performance.now();
  try {
    const places = await fetch(url).then((r) => r.json());
    setStat(`${places.length}건 · ${Math.round(performance.now() - started)}ms`);
    render(places);
  } catch (e) {
    setStat('검색 실패');
    $('list').innerHTML = `<div class="notice">서버에 연결하지 못했습니다.<br>${escapeHtml(e.message)}</div>`;
  }
}

async function init() {
  try {
    await loadKakaoSdk();
    map = new window.kakao.maps.Map($('map'), {
      center: new window.kakao.maps.LatLng(GANGNAM.lat, GANGNAM.lng),
      level: 4,
    });
    window.kakao.maps.event.addListener(map, 'dragend', search);
  } catch (e) {
    showMapNotice(e.message);
  }

  $('go').onclick = search;
  $('radius').onchange = search;
  $('includeClosed').onchange = search;
  $('q').onkeydown = (e) => { if (e.key === 'Enter') search(); };

  await search();
}

init();
