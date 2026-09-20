/**
 * 내가 참가한 방 목록 + 방 만들기 + 초대 코드로 참가.
 *
 * 계정이 생기면서 가능해진 화면이다. 익명 구조에서는 링크를 잃으면 방도 잃었다.
 */

import { api, ApiError } from '../api.js';
import {
  render, $, esc, ago, busy, notice, showError, showFieldErrors, formatCode,
} from '../dom.js';

export async function roomsPage() {
  render(`
    <div class="page">
      <div class="two-up">
        <section class="panel">
          <h2>방 만들기</h2>
          <form id="new-room" class="form row-form">
            <em class="form-error" hidden></em>
            <input id="title" type="text" placeholder="예: 금요일 저녁 모임" maxlength="50" autofocus>
            <em class="field-error" data-for="title" hidden></em>
            <button class="primary" type="submit">만들기</button>
          </form>
          <p class="hint">만들면 바로 들어가 있습니다. 초대 코드가 같이 나와요.</p>
        </section>

        <section class="panel">
          <h2>초대 코드로 참가</h2>
          <form id="join-room" class="row-form">
            <input id="code" type="text" class="code-input" placeholder="XXXX-XXXX"
                   maxlength="9" autocomplete="off" spellcheck="false">
            <button class="primary" type="submit">참가</button>
          </form>
          <em id="join-error" class="form-error" hidden></em>
          <p class="hint">대소문자와 하이픈은 가리지 않습니다. 링크를 받았다면 그냥 눌러도 됩니다.</p>
        </section>
      </div>

      <section class="panel">
        <h2>내 방</h2>
        <div id="room-list">${notice('불러오는 중…')}</div>
      </section>
    </div>
  `);

  const form = $('#new-room');
  form.onsubmit = async (e) => {
    e.preventDefault();
    showError(form, null);
    showFieldErrors(form, {});

    await busy($('button.primary', form), async () => {
      try {
        const room = await api.createRoom($('#title').value.trim());
        window.location.hash = `#/rooms/${room.roomId}`;
      } catch (err) {
        if (!(err instanceof ApiError)) {
          showError(form, '서버에 연결하지 못했습니다.');
          return;
        }
        showError(form, err.message);
        showFieldErrors(form, err.fields);
      }
    });
  };

  const joinForm = $('#join-room');
  const joinError = $('#join-error');

  // 치는 동안 코드 모양을 맞춰 준다. 서버가 어차피 정리하지만, 화면에서 바로 보여주면
  // 잘못 옮겨 적었을 때 눈으로 먼저 알아챈다.
  $('#code').oninput = (e) => {
    const raw = e.target.value.toUpperCase().replace(/[^0-9A-Z]/g, '').slice(0, 8);
    e.target.value = raw.length > 4 ? `${raw.slice(0, 4)}-${raw.slice(4)}` : raw;
  };

  joinForm.onsubmit = async (e) => {
    e.preventDefault();
    joinError.hidden = true;

    await busy($('button.primary', joinForm), async () => {
      try {
        const room = await api.joinByCode($('#code').value);
        window.location.hash = `#/rooms/${room.roomId}`;
      } catch (err) {
        joinError.textContent =
          err instanceof ApiError ? err.message : '서버에 연결하지 못했습니다.';
        joinError.hidden = false;
      }
    });
  };

  await paintList();
}

async function paintList() {
  const box = $('#room-list');
  try {
    const rooms = await api.myRooms();
    box.innerHTML = rooms.length
      ? rooms.map((r) => `
          <a class="room-row" href="#/rooms/${r.roomId}">
            <b>${esc(r.title)}</b>
            <span class="meta">
              ${esc(r.ownerNickname)} · ${ago(r.createdAt)}
              <code class="code-chip">${esc(formatCode(r.inviteCode))}</code>
            </span>
          </a>`).join('')
      : notice('아직 방이 없습니다. 위에서 하나 만들거나 초대 코드로 들어가 보세요.');
  } catch {
    box.innerHTML = notice('목록을 불러오지 못했습니다.', 'bad');
  }
}
