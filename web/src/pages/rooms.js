/**
 * 내가 참가한 방 목록 + 방 만들기.
 *
 * 계정이 생기면서 가능해진 화면이다. 익명 구조에서는 링크를 잃으면 방도 잃었다.
 */

import { api, ApiError } from '../api.js';
import { render, $, esc, ago, busy, notice, showError, showFieldErrors } from '../dom.js';

export async function roomsPage() {
  render(`
    <div class="page">
      <section class="panel">
        <h2>방 만들기</h2>
        <form id="new-room" class="form row-form">
          <em class="form-error" hidden></em>
          <input id="title" type="text" placeholder="예: 금요일 저녁 모임" maxlength="50" autofocus>
          <em class="field-error" data-for="title" hidden></em>
          <button class="primary" type="submit">만들기</button>
        </form>
        <p class="hint">
          만들면 바로 들어가 있습니다. 주소를 복사해 친구에게 보내면 그 사람도 참가할 수 있어요.
        </p>
      </section>

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
            <span class="meta">${esc(r.ownerNickname)} · ${ago(r.createdAt)}</span>
          </a>`).join('')
      : notice('아직 방이 없습니다. 위에서 하나 만들어 보세요.');
  } catch {
    box.innerHTML = notice('목록을 불러오지 못했습니다.', 'bad');
  }
}
