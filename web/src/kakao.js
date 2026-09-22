/**
 * 카카오맵 SDK 적재.
 *
 * 키를 HTML 에 박아 커밋하지 않기 위해 서버에서 받아온다. 화면이 여럿이 되었으므로
 * **한 번만 적재하고 재사용**한다 — 화면을 옮길 때마다 스크립트를 다시 넣으면
 * 같은 SDK 가 여러 번 초기화된다.
 */

import { api } from './api.js';

let loading = null;

/** 왜 실패했는지 화면이 다르게 설명할 수 있도록 구분한다. */
export class MapUnavailable extends Error {}

export function loadKakao() {
  if (loading) return loading;

  loading = (async () => {
    const { kakaoJsKey } = await api.clientConfig();
    if (!kakaoJsKey) throw new MapUnavailable('NO_KEY');

    await new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src =
        `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${kakaoJsKey}&autoload=false&libraries=services`;
      script.onload = () => window.kakao.maps.load(resolve);
      script.onerror = () => reject(new MapUnavailable('SDK_LOAD_FAILED'));
      document.head.appendChild(script);
    });
    return window.kakao;
  })().catch((e) => {
    // 실패를 기억해 두면 다시 시도할 수 없다. 다음 화면에서 재시도하게 비운다.
    loading = null;
    throw e;
  });

  return loading;
}

/** 키가 없거나 SDK 가 안 뜰 때 지도 자리에 넣을 안내. */
export function mapNotice(reason) {
  if (reason === 'NO_KEY') {
    return `
      <b>카카오맵 JavaScript 키가 없습니다.</b><br><br>
      1. <code>developers.kakao.com</code> 에서 앱을 만들고 <b>JavaScript 키</b> 복사<br>
      2. 플랫폼 &gt; Web 에 <code>http://localhost:8080</code>, <code>http://localhost:5173</code> 등록<br>
      3. <b>저장소 루트</b>의 <code>.env</code> 에 <code>WWG_KAKAO_JS_KEY=발급받은키</code><br>
      4. <code>docker compose up -d server</code> 로 재시작<br><br>
      키 없이도 검색과 방 기능은 그대로 동작합니다.`;
  }
  return `카카오맵 SDK 를 불러오지 못했습니다.
          키가 유효한지, 플랫폼에 현재 주소가 등록됐는지 확인하세요.`;
}

/** 지도 하나를 만들고 마커를 관리하는 얇은 껍데기. */
export function createMap(container, { lat, lng }, level = 5) {
  const kakao = window.kakao;
  const map = new kakao.maps.Map(container, {
    center: new kakao.maps.LatLng(lat, lng),
    level,
  });

  let markers = [];
  let infoWindow = null;
  /** 그려 둔 경로(선·점). 마커와 따로 둔다 — 5초마다 마커를 다시 그려도 경로는 남아야 한다. */
  let routeShapes = [];
  /** 떠 있는 정보 카드. 한 번에 하나만. */
  let card = null;

  function closeCard() {
    card?.setMap(null);
    card = null;
  }

  /**
   * 컨테이너 크기가 바뀌면 지도에게 알려 준다.
   *
   * 카카오맵은 만들어질 때의 크기를 기억하고 있어서, CSS 로 컨테이너가 커져도 타일은
   * 옛 크기 그대로 그린다. 창을 넓히거나 세로 배치(모바일)에서 가로 배치로 넘어갈 때
   * 지도만 한쪽 구석에 작게 남는다 — 레이아웃이 아니라 지도 내부 상태 문제다.
   */
  const watcher = new ResizeObserver(() => map.relayout());
  watcher.observe(container);

  return {
    raw: map,

    /** 화면을 떠날 때. 안 부르면 사라진 요소를 계속 관찰한다. */
    destroy() {
      watcher.disconnect();
    },

    center() {
      const c = map.getCenter();
      return { lat: c.getLat(), lng: c.getLng() };
    },

    panTo({ lat: y, lng: x }) {
      map.panTo(new kakao.maps.LatLng(y, x));
    },

    clear() {
      markers.forEach((m) => m.setMap(null));
      markers = [];
      infoWindow?.close();
      closeCard();
    },

    /**
     * @param {{lat:number,lng:number,label:string,color?:string,onClick?:Function}} spec
     *   `onClick` 이 있으면 이름 말풍선 대신 그것을 부른다 — 부르는 쪽이 카드를 띄운다.
     */
    addMarker({ lat: y, lng: x, label, color, onClick }) {
      const marker = new kakao.maps.Marker({
        map,
        position: new kakao.maps.LatLng(y, x),
        title: label,
        image: color ? coloredPin(color) : undefined,
      });
      kakao.maps.event.addListener(marker, 'click', () => {
        infoWindow?.close();
        if (onClick) {
          onClick();
          return;
        }
        infoWindow = new kakao.maps.InfoWindow({
          content: `<div class="pin-label">${label.replace(/[<>&"]/g, '')}</div>`,
        });
        infoWindow.open(map, marker);
      });
      markers.push(marker);
      return marker;
    },

    /**
     * 좌표 위에 정보 카드를 띄운다. 핀 바로 위에 꼬리가 오도록 아래 가운데를 기준점으로 둔다.
     *
     * 카드 안을 누르거나 끌어도 지도가 그 클릭을 받지 않게 막는다 — 안 막으면 버튼을 누를 때
     * 지도 클릭(출발지·후보 고르기)까지 같이 일어나고, 드래그하면 지도가 끌려간다.
     *
     * @param {HTMLElement} element 카드 내용
     */
    openCard({ lat: y, lng: x }, element) {
      closeCard();
      for (const type of ['mousedown', 'touchstart', 'click', 'dblclick', 'wheel']) {
        element.addEventListener(type, (e) => e.stopPropagation());
      }
      card = new kakao.maps.CustomOverlay({
        map,
        position: new kakao.maps.LatLng(y, x),
        content: element,
        xAnchor: 0.5,
        yAnchor: 1,
        zIndex: 10,
        clickable: true,
      });
    },

    closeCard,

    /**
     * 경로를 그린다. 구간마다 색이 다른 선, 도보는 점선, 승차·환승 자리에는 흰 테두리 점.
     *
     * @param {{kind:string, path:number[][], color:string}[]} legs
     */
    drawRoute(legs) {
      this.clearRoute();
      const toLatLng = ([x, y]) => new kakao.maps.LatLng(y, x);
      // 점은 픽셀 크기로 그린다. Circle 은 반지름이 미터라 축척을 바꾸면 사라지거나 커진다 —
      // 1km 축척에서 반지름 18m 는 한 픽셀도 안 됐다.
      const dot = (p, className, color, title) => {
        const el = document.createElement('div');
        el.className = `route-dot ${className}`;
        el.style.setProperty('--c', color);
        if (title) el.title = title;
        routeShapes.push(new kakao.maps.CustomOverlay({
          map, position: toLatLng(p), content: el, xAnchor: 0.5, yAnchor: 0.5, zIndex: 5,
        }));
      };
      for (const leg of legs) {
        if (!leg.path?.length) continue;
        if (leg.kind === 'BOARD' || leg.kind === 'TRANSFER') {
          dot(leg.path[0], 'stop', leg.color, `${leg.toName ?? ''} ${leg.label ?? ''}`.trim());
          continue;
        }
        const walk = leg.kind === 'WALK';
        // 흰 테두리를 먼저 깔아 지도 위에서 선이 묻히지 않게 한다
        if (!walk) {
          routeShapes.push(new kakao.maps.Polyline({
            map, path: leg.path.map(toLatLng), strokeWeight: 9,
            strokeColor: '#ffffff', strokeOpacity: 0.9, zIndex: 3,
          }));
        }
        routeShapes.push(new kakao.maps.Polyline({
          map, path: leg.path.map(toLatLng),
          strokeWeight: walk ? 4 : 6, strokeColor: leg.color, strokeOpacity: walk ? 0.9 : 1,
          strokeStyle: walk ? 'shortdash' : 'solid', zIndex: 4,
        }));
      }
      // 출발·도착
      const first = legs.find((l) => l.path?.length)?.path[0];
      const last = [...legs].reverse().find((l) => l.path?.length)?.path.at(-1);
      if (first) dot(first, 'end start', '#2563eb', '출발');
      if (last) dot(last, 'end goal', '#e11d48', '도착');
    },

    clearRoute() {
      routeShapes.forEach((s) => s.setMap(null));
      routeShapes = [];
    },

    onClick(handler) {
      kakao.maps.event.addListener(map, 'click', (e) => {
        closeCard();  // 빈 곳을 누르면 카드를 닫는다
        handler({ lat: e.latLng.getLat(), lng: e.latLng.getLng() });
      });
    },

    onIdle(handler) {
      kakao.maps.event.addListener(map, 'dragend', handler);
    },

    /**
     * 모든 마커(또는 `points`)가 보이도록 맞춘다. 비어 있으면 아무것도 하지 않는다.
     * @param {{lat:number,lng:number}[]} [points]
     */
    fit(points) {
      const positions = points
        ? points.map((p) => new kakao.maps.LatLng(p.lat, p.lng))
        : markers.map((m) => m.getPosition());
      if (!positions.length) return;
      const bounds = new kakao.maps.LatLngBounds();
      positions.forEach((p) => bounds.extend(p));
      map.setBounds(bounds);
    },
  };
}

/**
 * 출발지와 후보를 색으로 구분한다.
 *
 * SVG 를 data URI 로 넣는다 — 핀 이미지 파일을 따로 두면 빌드에 정적 자산이 늘고,
 * 색을 바꿀 때마다 파일이 하나씩 생긴다.
 */
function coloredPin(color) {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="26" height="34" viewBox="0 0 26 34">
      <path d="M13 0C5.8 0 0 5.8 0 13c0 9.7 13 21 13 21s13-11.3 13-21C26 5.8 20.2 0 13 0z"
            fill="${color}"/>
      <circle cx="13" cy="13" r="5" fill="#fff"/>
    </svg>`;
  return new window.kakao.maps.MarkerImage(
    'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg),
    new window.kakao.maps.Size(26, 34),
    { offset: new window.kakao.maps.Point(13, 34) },
  );
}
