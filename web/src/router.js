/**
 * 해시 라우터.
 *
 * 화면이 넷(로그인·방 목록·방·둘러보기)이고 전부 폼과 표다. 여기에 라우팅 라이브러리를
 * 넣으면 의존성만 늘고 얻는 것이 없다.
 *
 * <p>해시(`#/rooms/…`)를 쓰는 이유는 **서버 설정이 필요 없기 때문**이다. 경로 방식
 * (`/rooms/…`)을 쓰면 새로고침했을 때 서버가 그 경로로 index.html 을 돌려주도록
 * 따로 맞춰야 한다. 해시는 서버에 전달되지 않으므로 그럴 일이 없다.
 */

const routes = [];

/**
 * @param {string} pattern `'/rooms/:roomId'` 처럼. `:이름` 은 한 조각을 잡는다
 * @param {(params: object) => Promise<void>|void} handler
 */
export function route(pattern, handler) {
  const names = [];
  const regex = new RegExp(
    '^'
      + pattern.replace(/:([A-Za-z]+)/g, (_, name) => {
        names.push(name);
        return '([^/]+)';
      })
      + '$',
  );
  routes.push({ regex, names, handler });
}

/** 못 찾았을 때. */
let fallback = () => {};

export function notFound(handler) {
  fallback = handler;
}

function currentPath() {
  const hash = window.location.hash.slice(1);
  return hash.startsWith('/') ? hash : '/';
}

export function go(path, { replace = false } = {}) {
  const target = '#' + path;
  if (window.location.hash === target) {
    // 같은 곳으로 이동하면 hashchange 가 안 뜬다. 수동으로 다시 그린다.
    resolve();
    return;
  }
  if (replace) window.location.replace(target);
  else window.location.hash = target;
}

export function path() {
  return currentPath();
}

async function resolve() {
  const here = currentPath();
  for (const { regex, names, handler } of routes) {
    const match = here.match(regex);
    if (!match) continue;
    const params = Object.fromEntries(names.map((n, i) => [n, decodeURIComponent(match[i + 1])]));
    await handler(params);
    return;
  }
  await fallback();
}

export function start() {
  window.addEventListener('hashchange', resolve);
  return resolve();
}
