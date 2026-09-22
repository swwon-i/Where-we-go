/**
 * 서버와 이야기하는 유일한 통로.
 *
 * 화면마다 fetch 를 직접 부르면 CSRF 토큰과 401 처리를 화면 수만큼 반복하게 되고,
 * 한 군데를 빠뜨리면 거기서만 조용히 깨진다. 전부 여기로 모은다.
 */

const BASE = '/api/v1';

/**
 * CSRF 토큰.
 *
 * 세션 쿠키는 브라우저가 알아서 붙이므로 남의 사이트에서 우리 API 를 불러도 쿠키가 함께 간다.
 * 그것을 막는 것이 이 토큰이다 — 다른 출처의 스크립트는 우리 쿠키를 읽지 못하므로
 * 헤더를 만들 수 없다.
 *
 * **토큰은 누군가 요구해야 만들어진다.** 서버가 먼저 내려주지 않는다.
 */
let csrf = null;

async function csrfToken() {
  if (!csrf) {
    const r = await fetch(`${BASE}/auth/csrf`, { credentials: 'same-origin' });
    csrf = await r.json();
  }
  return csrf;
}

/** 서버가 돌려준 실패. 화면이 상태 코드와 칸별 메시지를 보고 반응한다. */
export class ApiError extends Error {
  constructor(status, body) {
    super(body?.message ?? `요청이 실패했습니다 (${status})`);
    this.status = status;
    /** 입력 오류일 때 `{ 칸이름: 메시지 }`. 없으면 빈 객체. */
    this.fields = body?.fields ?? {};
  }
}

/** 로그인이 필요한 요청이 401 을 받으면 이것이 불린다. main.js 가 채운다. */
let onUnauthorized = () => {};

export function setUnauthorizedHandler(handler) {
  onUnauthorized = handler;
}

async function request(method, path, body, { retry = true, silent401 = false } = {}) {
  const headers = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';

  if (method !== 'GET') {
    const token = await csrfToken();
    headers[token.headerName] = token.token;
  }

  const response = await fetch(BASE + path, {
    method,
    headers,
    // 세션 쿠키를 주고받아야 한다. 이게 없으면 로그인해도 매 요청이 남남이 된다.
    credentials: 'same-origin',
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  if (response.status === 204) return null;

  let payload = null;
  try {
    payload = await response.json();
  } catch {
    // 본문이 없을 수 있다. 상태 코드만으로 판단한다.
  }

  if (response.ok) return payload;

  // 토큰이 만료됐거나 세션이 갈렸다. 한 번만 새로 받아 재시도한다 —
  // 무한히 반복하면 서버가 계속 403 을 줄 때 브라우저가 멈춘다.
  if (response.status === 403 && retry && method !== 'GET') {
    csrf = null;
    return request(method, path, body, { retry: false });
  }

  // 로그인 여부를 확인하는 요청은 401 이 정상적인 답이다. 그것까지 "세션이 끊겼다"로
  // 취급하면, 로그인하지 않은 사람이 둘러보기 화면에 들어오자마자 로그인으로 튕긴다.
  if (response.status === 401 && !silent401) onUnauthorized();

  throw new ApiError(response.status, payload);
}

export const api = {
  get: (path) => request('GET', path),
  post: (path, body) => request('POST', path, body),
  put: (path, body) => request('PUT', path, body),
  del: (path) => request('DELETE', path),

  // ── 계정 ──────────────────────────────────────────────────────────────
  signup: (form) => request('POST', '/auth/signup', form),
  login: (loginId, password) => request('POST', '/auth/login', { loginId, password }),
  logout: () => request('POST', '/auth/logout'),

  /** 로그인 상태 확인. 로그인하지 않았으면 예외 대신 null 을 준다 — 흔한 상태지 오류가 아니다. */
  async me() {
    try {
      return await request('GET', '/auth/me', undefined, { silent401: true });
    } catch (e) {
      if (e.status === 401) return null;
      throw e;
    }
  },

  // ── 방 ────────────────────────────────────────────────────────────────
  myRooms: () => request('GET', '/rooms'),
  createRoom: (title) => request('POST', '/rooms', { title }),
  room: (roomId) => request('GET', `/rooms/${roomId}`),
  joinRoom: (roomId) => request('POST', `/rooms/${roomId}/members`),
  joinByCode: (code) => request('POST', '/rooms/join', { code }),
  regenerateCode: (roomId) => request('POST', `/rooms/${roomId}/code`),
  setOrigin: (roomId, origin) =>
    request('PUT', `/rooms/${roomId}/members/me/origin`, origin),
  addBookmark: (roomId, bookmark) => request('POST', `/rooms/${roomId}/bookmarks`, bookmark),
  removeBookmark: (roomId, bookmarkId) =>
    request('DELETE', `/rooms/${roomId}/bookmarks/${bookmarkId}`),

  // ── 행렬 ──────────────────────────────────────────────────────────────
  matrix: (roomId, departureHour) =>
    request('POST', `/rooms/${roomId}/matrix`, { departureHour }),
  routeDetail: (roomId, params) =>
    request('GET', `/rooms/${roomId}/routes?${new URLSearchParams(params)}`),

  // ── 장소 ──────────────────────────────────────────────────────────────
  nearby: (params) => request('GET', `/places/nearby?${new URLSearchParams(params)}`),
  search: (params) => request('GET', `/places/search?${new URLSearchParams(params)}`),
  place: (poiId) => request('GET', `/places/${poiId}`),

  clientConfig: () => request('GET', '/client-config'),

  // 파이프라인 운영 기록 — 로그인 없이 읽힌다
  adminIngestRuns: () => request('GET', '/admin/ingest-runs'),
  adminValidationSummary: () => request('GET', '/admin/validation-summary'),
  adminValidationResults: (params) =>
    request('GET', `/admin/validation-results?${new URLSearchParams(params)}`),
  adminGraphStats: () => request('GET', '/admin/graph-stats'),
};

/** 로그아웃하면 토큰도 버린다. 세션이 바뀌면 토큰도 무효다. */
export function forgetCsrf() {
  csrf = null;
}
