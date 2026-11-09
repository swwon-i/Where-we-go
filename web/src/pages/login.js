/**
 * 로그인 · 회원가입.
 *
 * 탭 하나로 두 폼을 오간다. 화면을 나누면 "계정이 없으신가요?" 링크를 누를 때마다
 * 입력한 것이 날아간다.
 */

import { api, ApiError, forgetCsrf } from '../api.js';
import { render, $, busy, showError, showFieldErrors, notice } from '../dom.js';
import { go } from '../router.js';
import { loadKakao } from '../kakao.js';
import { setCurrentUser } from '../session.js';

/** 로그인 뒤 돌아갈 곳. 방 링크를 받고 들어온 사람을 그 방으로 보내기 위한 것이다. */
let returnTo = null;

export function setReturnTo(path) {
  returnTo = path;
}

/**
 * 입력 한 칸.
 *
 * `name` 은 서버가 `fields` 에 쓰는 이름이다. 화면의 id(`su-id`)와 서버의 이름(`loginId`)이
 * 달라서 칸별 오류가 한 번도 붙지 않았다 — 그래서 "입력을 확인해 주세요" 만 보였다.
 * 붙이는 쪽 기준을 서버 이름으로 맞춘다.
 *
 * `hint` 는 규칙을 미리 보여준다. 틀린 뒤에 알려주는 것보다 낫다.
 */
const field = (id, label, type, { name = id, extra = '', hint = '' } = {}) => `
  <label class="field">
    <span>${label}</span>
    <input id="${id}" type="${type}" ${extra}>
    ${hint ? `<small class="hint">${hint}</small>` : ''}
    <em class="field-error" data-for="${name}" hidden></em>
  </label>`;

/** 한강이 가운데 오도록 잡은 서울 중심. 축척 9면 서울 전체가 한 화면에 들어온다. */
const SEOUL_CENTER = { lat: 37.54, lng: 126.99 };
const SEOUL_LEVEL = 9;

/**
 * 왼쪽 아래의 실제 서울 지도. 움직일 필요가 없으므로 이미지 지도로 그린다.
 * 키가 없거나 SDK 가 안 뜨면 조용히 뺀다 — 장식 때문에 로그인 화면에 안내문을 띄울 이유가 없다.
 */
async function paintBackdropMap() {
  try {
    const kakao = await loadKakao();
    const container = $('.welcome-map');
    if (!container) return;  // 기다리는 사이 화면을 떠났다
    new kakao.maps.StaticMap(container, {
      center: new kakao.maps.LatLng(SEOUL_CENTER.lat, SEOUL_CENTER.lng),
      level: SEOUL_LEVEL,
      marker: false,
    });
  } catch {
    $('.welcome-map')?.remove();
  }
}

function welcomeSign() {
  return `
    <section class="welcome-sign">
      <div class="welcome-map"></div>
      <p class="welcome-brand">Where-We-Go!</p>
      <h1>모임 장소, 같이 정해요</h1>
      <ol class="steps">
        <li>방을 만들고 친구를 초대해요</li>
        <li>각자 출발지와 후보를 정해요</li>
        <li>누가 얼마나 걸리는지 비교해요</li>
      </ol>
    </section>`;
}

export async function loginPage() {
  render(`
    <div class="welcome">
      ${welcomeSign()}
      <div class="welcome-form">
      <div class="tabs" role="tablist">
        <button class="tab is-on" data-tab="login">로그인</button>
        <button class="tab" data-tab="signup">회원가입</button>
      </div>

      <form id="login-form" class="form">
        <em class="form-error" hidden></em>
        ${field('login-id', '아이디', 'text', {
          name: 'loginId',
          extra: 'autocomplete="username" autofocus',
        })}
        ${field('login-pw', '비밀번호', 'password', {
          name: 'password',
          extra: 'autocomplete="current-password"',
        })}
        <button class="primary" type="submit">로그인</button>
      </form>

      <form id="signup-form" class="form" hidden>
        <em class="form-error" hidden></em>
        ${field('su-nickname', '닉네임', 'text', {
          name: 'nickname',
          // maxlength 만 둔다. minlength·pattern 을 걸면 브라우저가 먼저 막으면서
          // "요청한 형식과 일치시키세요" 같은 자기 말풍선을 띄워 우리 안내를 덮는다.
          extra: 'autocomplete="nickname" maxlength="20"',
          hint: '2~20자. 다른 참가자에게 보이는 이름입니다.',
        })}
        ${field('su-id', '아이디', 'text', {
          name: 'loginId',
          extra: 'autocomplete="username" maxlength="20"',
          hint: '영문·숫자·밑줄(_) 4~20자. 대소문자는 구분하지 않습니다.<br>로그인에만 쓰는 이름입니다 — 공백·한글·그 밖의 기호는 쓸 수 없습니다.',
        })}
        ${field('su-pw', '비밀번호', 'password', {
          name: 'password',
          extra: 'autocomplete="new-password" maxlength="64"',
          hint: '8~64자',
        })}
        ${field('su-pw2', '비밀번호 확인', 'password', {
          name: 'passwordConfirm',
          extra: 'autocomplete="new-password" maxlength="64"',
        })}
        <button class="primary" type="submit">가입하고 시작하기</button>
      </form>

      <p class="foot">
        <a href="#/explore">계정 없이 장소만 둘러보기</a>
      </p>
      </div>
    </div>
  `);

  paintBackdropMap();

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
      showError(form, summarize(e));
      showFieldErrors(form, e.fields);
    }
  });
}

/**
 * 폼 위쪽에 쓸 한 줄.
 *
 * 서버의 "입력을 확인해 주세요" 만 띄우면 무엇이 틀렸는지 알 수 없다. 틀린 칸의 이유를
 * 그대로 모아서 보여준다 — 칸 아래 메시지를 못 보고 지나치는 경우가 있다.
 */
function summarize(e) {
  const reasons = Object.values(e.fields ?? {}).filter(Boolean);
  return reasons.length ? reasons.join(' · ') : e.message;
}

/** 이미 로그인한 사람이 로그인 화면으로 오면 방 목록으로 보낸다. */
export function alreadyIn() {
  return notice('이미 로그인되어 있습니다.');
}
