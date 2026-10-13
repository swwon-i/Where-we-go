/**
 * 장소 둘러보기. 로그인 없이 열려 있다.
 *
 * 방을 만들기 전에 어떤 데이터가 들어 있는지 볼 수 있어야 한다. 심사자가 클론해서
 * 처음 여는 화면이기도 하다 — 여기서 점이 찍히면 적재 파이프라인이 돌았다는 뜻이다.
 */

import { api } from '../api.js';
import { render, $, esc, notice } from '../dom.js';
import { loadKakao, MapUnavailable, mapNotice, createMap } from '../kakao.js';

const GANGNAM = { lat: 37.4979, lng: 127.0276 };

let mapView = null;

export async function explorePage() {
  render(`
    <div class="explore">
      <div class="bar">
        <input id="q" type="search" placeholder="상호명 (비우면 반경 검색)">
        <select id="radius">
          <option value="300">300m</option>
          <option value="500" selected>500m</option>
          <option value="1000">1km</option>
          <option value="2000">2km</option>
        </select>
        <label><input type="checkbox" id="closed"> 폐업 포함</label>
        <button id="go" class="primary">이 지점에서 검색</button>
        <span id="stat" class="meta"></span>
      </div>
      <div class="room-body">
        <div id="map" class="map"></div>
        <aside class="side"><div id="list" class="panel-body"></div></aside>
      </div>
    </div>
  `);

  try {
    await loadKakao();
    mapView = createMap($('#map'), GANGNAM, 4);
    mapView.onIdle(search);
  } catch (e) {
    mapView = null;
    $('#map').innerHTML = notice(
      mapNotice(e instanceof MapUnavailable ? e.message : ''), 'bad');
  }

  $('#go').onclick = search;
  $('#radius').onchange = search;
  $('#closed').onchange = search;
  $('#q').onkeydown = (e) => { if (e.key === 'Enter') search(); };

  await search();
}

async function search() {
  const center = mapView?.center() ?? GANGNAM;
  const q = $('#q').value.trim();

  const params = {
    lng: center.lng,
    lat: center.lat,
    includeClosed: $('#closed').checked,
    limit: 100,
  };

  // 상호명을 치면 반경을 보내지 않는다. 서울 전역에서 찾고, 지도 중심에서 가까운 순으로 받는다 —
  // 이름을 아는 사람은 그 가게가 지금 보는 화면 밖에 있어도 찾으려는 것이다.
  $('#radius').disabled = !!q;

  $('#stat').textContent = '검색 중…';
  const started = performance.now();

  try {
    const places = q
      ? await api.search({ ...params, q })
      : await api.nearby({ ...params, radius: $('#radius').value });
    const took = Math.round(performance.now() - started);
    $('#stat').textContent = `${places.length}건 · ${took}ms${q ? ' · 서울 전역' : ''}`;
    paint(places, q);
  } catch {
    $('#stat').textContent = '';
    $('#list').innerHTML = notice('서버에 연결하지 못했습니다.', 'bad');
  }
}

function paint(places, q = '') {
  $('#list').innerHTML = places.length
    ? `<ul class="list">
        ${places.map((p, i) => `
          <li class="clickable" data-i="${i}">
            <b>${esc(p.name)}</b>
            ${p.distanceM != null ? `<span class="dist">${Math.round(p.distanceM)}m</span>` : ''}
            <div class="meta">
              ${esc(p.categoryRaw)}${p.status === 'CLOSED' ? ' · <span class="warn">폐업</span>' : ''}
            </div>
            <div class="meta">${esc(p.roadAddress ?? p.jibunAddress ?? '')}</div>
          </li>`).join('')}
       </ul>`
    : notice(q
      // 서울 전역을 뒤져도 없었다는 뜻이다. 담고 있는 것이 서울시 자료뿐이라는 것까지 말해 준다.
      ? `서울시 안에서 "${esc(q)}"를 찾지 못했습니다.`
      : '이 반경 안에 결과가 없습니다. 반경을 넓히거나 지도를 옮겨 보세요.');

  if (!mapView) return;
  mapView.clear();
  places.forEach((p) => mapView.addMarker({ lat: p.lat, lng: p.lng, label: p.name }));

  $('#list').querySelectorAll('.clickable').forEach((row) => {
    row.onclick = () => mapView.panTo(places[Number(row.dataset.i)]);
  });
}
