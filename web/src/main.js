/**
 * 화면 뼈대와 경로 등록.
 *
 * 프레임워크를 쓰지 않는다 — 화면이 넷이고 전부 폼과 표다. 라우팅·상태 관리 라이브러리를
 * 넣으면 "의존성 없이 클론하면 바로 돈다"는 이 프로젝트의 강점만 희석된다.
 * 행렬 표를 만들 때(계획서 3주 전반) 상태가 실제로 복잡해지면 그때 다시 본다.
 */

import { setUnauthorizedHandler, forgetCsrf } from './api.js';
import { route, notFound, start, go, path } from './router.js';
import { render, notice } from './dom.js';
import { loadCurrentUser, currentUser, setCurrentUser } from './session.js';
import { loginPage, setReturnTo } from './pages/login.js';
import { roomsPage } from './pages/rooms.js';
import { roomPage, leaveRoom } from './pages/room.js';
import { explorePage } from './pages/explore.js';

/**
 * 로그인이 필요한 화면을 감싼다.
 *
 * **어디로 가려 했는지 기억해 둔다.** 방 링크를 받고 들어온 사람을 로그인 뒤에 방 목록으로
 * 보내면, 받은 링크를 다시 찾아 눌러야 한다.
 */
function guard(handler) {
  return async (params) => {
    if (!currentUser()) {
      setReturnTo(path());
      go('/login', { replace: true });
      return;
    }
    await handler(params);
  };
}

/** 방 화면은 폴링을 돌리므로, 떠날 때 멈춰 줘야 한다. */
function withCleanup(handler) {
  return async (params) => {
    leaveRoom();
    await handler(params);
  };
}

route('/', async () => go(currentUser() ? '/rooms' : '/login', { replace: true }));

route('/login', withCleanup(async () => {
  if (currentUser()) {
    go('/rooms', { replace: true });
    return;
  }
  await loginPage();
}));

route('/explore', withCleanup(explorePage));
route('/rooms', withCleanup(guard(roomsPage)));
route('/rooms/:roomId', guard(roomPage));

notFound(() => render(notice('없는 화면입니다. <a href="#/">처음으로</a>')));

/**
 * 세션이 끊겼을 때.
 *
 * 로그인해 둔 채로 서버가 재시작되면 다음 요청이 401 로 돌아온다. 세션이 서버 메모리에
 * 있기 때문이다(계획서 §8). 그때 조용히 실패하는 대신 로그인 화면으로 보낸다.
 */
setUnauthorizedHandler(() => {
  // **들고 있던 사용자부터 버린다.** 이걸 안 하면 로그인 화면이 "이미 로그인했네" 하고
  // 방 목록으로 되돌리고, 그 화면이 다시 401 을 받아 무한히 오간다.
  // 세션은 서버 메모리에 있으므로 서버를 다시 띄우기만 해도 이 상황이 된다.
  setCurrentUser(null);
  forgetCsrf();

  if (path() === '/login') return;
  setReturnTo(path());
  go('/login', { replace: true });
});

(async () => {
  await loadCurrentUser();
  await start();
})();
