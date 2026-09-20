/**
 * 지금 로그인한 사람.
 *
 * 화면마다 `/auth/me` 를 부르면 이동할 때마다 왕복이 하나씩 붙는다. 앱이 시작할 때
 * 한 번 확인하고 여기 둔다 — 로그인·로그아웃할 때만 바뀐다.
 *
 * <p>**진짜 인증은 서버 세션이다.** 이 값은 화면을 그리기 위한 사본일 뿐이라,
 * 여기를 고쳐도 권한이 생기지 않는다.
 */

import { api } from './api.js';

let current = null;

export function currentUser() {
  return current;
}

export function setCurrentUser(user) {
  current = user;
  paintHeader();
}

/** 앱 시작 때 한 번. 로그인하지 않았으면 null 이다. */
export async function loadCurrentUser() {
  current = await api.me();
  paintHeader();
  return current;
}

function paintHeader() {
  const box = document.getElementById('who');
  if (!box) return;

  if (!current) {
    box.innerHTML = '<a href="#/login">로그인</a>';
    return;
  }
  box.innerHTML = `
    <span class="nick">${current.nickname.replace(/[<>&"]/g, '')}</span>
    <button id="logout" class="link">로그아웃</button>`;

  document.getElementById('logout').onclick = async () => {
    await api.logout();
    setCurrentUser(null);
    window.location.hash = '#/login';
  };
}
