/**
 * 노선 색. 지도의 경로 선과 상세 타임라인의 막대가 같은 색을 쓴다 — 눈으로 둘을 맞춰 볼 수 있어야 한다.
 *
 * 지하철은 공식 노선색, 버스는 서울시 노선 유형색이다. 급행은 같은 호선 색을 쓰고 이름으로 가른다.
 */

const SUBWAY = {
  1: '#0052A4', 2: '#00A84D', 3: '#EF7C1C', 4: '#00A5DE', 5: '#996CAC',
  6: '#CD7C2F', 7: '#747F00', 8: '#E6186C', 9: '#BDB092',
};

/** 서울 버스 유형색. 심야(N)는 유형이 간선이어도 따로 본다. */
const BUS = {
  간선: '#3D5BAB', 지선: '#5BB025', 광역: '#E60012', 순환: '#F2B70A',
  마을: '#53B332', 공항: '#00A0E9', 심야: '#2B3A55',
};

export const WALK_COLOR = '#9aa1ab';
const FALLBACK = '#64748b';

/** @param {{kind:string, line?:string, label?:string, routeType?:string}} leg */
export function legColor(leg) {
  if (leg.kind === 'WALK') return WALK_COLOR;
  if (leg.kind === 'SUBWAY' || isSubwayWait(leg)) {
    const base = String(leg.line ?? '').replace('급행', '');
    return SUBWAY[base] ?? FALLBACK;
  }
  if (/^N\d/.test(leg.label ?? '')) return BUS.심야;
  return BUS[leg.routeType] ?? FALLBACK;
}

/** 승차·환승 줄은 타려는 노선의 색을 따른다. 지하철 노선 식별자는 '5', '9급행' 처럼 짧다. */
function isSubwayWait(leg) {
  return (leg.kind === 'BOARD' || leg.kind === 'TRANSFER') && /^\d(급행)?$/.test(String(leg.line ?? ''));
}
