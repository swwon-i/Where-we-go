/**
 * 로그인 · 회원가입.
 *
 * 탭 하나로 두 폼을 오간다. 화면을 나누면 "계정이 없으신가요?" 링크를 누를 때마다
 * 입력한 것이 날아간다.
 */

import { api, ApiError, forgetCsrf } from '../api.js';
import { render, $, busy, showError, showFieldErrors, notice } from '../dom.js';
import { go } from '../router.js';
import { setCurrentUser } from '../session.js';

/** 로그인 뒤 돌아갈 곳. 방 링크를 받고 들어온 사람을 그 방으로 보내기 위한 것이다. */
let returnTo = null;

export function setReturnTo(path) {
  returnTo = path;
}

const field = (id, label, type, extra = '') => `
  <label class="field">
    <span>${label}</span>
    <input id="${id}" type="${type}" ${extra}>
    <em class="field-error" data-for="${id}" hidden></em>
  </label>`;

export async function loginPage() {
  render(`
    <div class="center-card">
      <h2>Where-We-Go!</h2>
      <p class="sub">각자 어디서 출발하든, 얼마나 걸리는지 나란히 놓고 본다.</p>

      <div class="tabs" role="tablist">
        <button class="tab is-on" data-tab="login">로그인</button>
        <button class="tab" data-tab="signup">회원가입</button>
      </div>

      <form id="login-form" class="form">
        <em class="form-error" hidden></em>
        ${field('login-id', '아이디', 'text', 'autocomplete="username" autofocus')}
        ${field('login-pw', '비밀번호', 'password', 'autocomplete="current-password"')}
        <button class="primary" type="submit">로그인</button>
      </form>

      <form id="signup-form" class="form" hidden>
        <em class="form-error" hidden></em>
        ${field('su-nickname', '닉네임', 'text', 'autocomplete="nickname"')}
        ${field('su-id', '아이디', 'text', 'autocomplete="username"')}
        ${field('su-pw', '비밀번호', 'password', 'autocomplete="new-password"')}
        ${field('su-pw2', '비밀번호 확인', 'password', 'autocomplete="new-password"')}
        <button class="primary" type="submit">가입하고 시작하기</button>
        <p class="hint">
          닉네임은 다른 참가자에게 보이는 이름입니다. 아이디는 로그인에만 씁니다.<br>
          비밀번호는 8자 이상.
        </p>
      </form>

      <p class="foot">
        <a href="#/explore">계정 없이 장소만 둘러보기</a>
      </p>
    </div>
  `);

  const loginForm = $('#login-form');
  const signupForm = $('#signup-form');

  document.querySelectorAll('.tab').forEach((tab) => {
    tab.onclick = () => {
      document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('is-on', t === tab));
      const wantSignup = tab.dataset.tab === 'signup';
      loginForm.hidden = wantSignup;
      signupForm.hidden = !wantSignup;
    };
  });

  loginForm.onsubmit = (e) => {
    e.preventDefault();
    submit(loginForm, () => api.login($('#login-id').value.trim(), $('#login-pw').value));
  };

  signupForm.onsubmit = (e) => {
    e.preventDefault();
    submit(signupForm, () =>
      api.signup({
        nickname: $('#su-nickname').value.trim(),
        loginId: $('#su-id').value.trim(),
        password: $('#su-pw').value,
        passwordConfirm: $('#su-pw2').value,
      }));
  };
}

async function submit(form, call) {
  const button = $('button.primary', form);
  showError(form, null);
  showFieldErrors(form, {});

  await busy(button, async () => {
    try {
      // 세션이 바뀌므로 들고 있던 CSRF 토큰은 버린다.
      forgetCsrf();
      const me = await call();
      setCurrentUser(me);

      const target = returnTo;
      returnTo = null;
      go(target ?? '/rooms', { replace: true });
    } catch (e) {
      if (!(e instanceof ApiError)) {
        showError(form, '서버에 연결하지 못했습니다.');
        return;
      }
      showError(form, e.message);
      showFieldErrors(form, e.fields);
    }
  });
}

/** 이미 로그인한 사람이 로그인 화면으로 오면 방 목록으로 보낸다. */
export function alreadyIn() {
  return notice('이미 로그인되어 있습니다.');
}
