/** 화면을 그릴 때 반복되는 조각들. */

/**
 * 사용자가 넣은 값을 HTML 에 그대로 꽂지 않는다.
 *
 * 닉네임·방 이름·북마크 이름은 전부 사용자가 쓴 문자열이고, 그것이 태그로 해석되면
 * 남의 화면에서 스크립트가 돈다. 서버가 막아 줄 것이라고 믿지 않는다 — 넣는 쪽에서 막는다.
 */
export const esc = (value) =>
  String(value ?? '').replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

export const $ = (selector, root = document) => root.querySelector(selector);
export const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];

/** 화면 전체를 갈아 끼운다. 부분 갱신을 하지 않으므로 상태 동기화 문제가 없다. */
export function render(html) {
  $('#app').innerHTML = html;
  return $('#app');
}

/** 줄글 안내. 비어 있음·오류·설명에 모두 쓴다. */
export const notice = (html, kind = '') =>
  `<div class="notice ${kind}">${html}</div>`;

/** 폼 위쪽에 뜨는 오류 한 줄. */
export function showError(container, message) {
  const box = $('.form-error', container);
  if (!box) return;
  box.textContent = message ?? '';
  box.hidden = !message;
}

/** 칸별 오류. 서버가 `fields` 로 알려준 것을 그 칸 아래에 붙인다. */
export function showFieldErrors(container, fields = {}) {
  $$('.field-error', container).forEach((el) => {
    const message = fields[el.dataset.for];
    el.textContent = message ?? '';
    el.hidden = !message;
  });
}

/** 사람이 읽는 시간. "3분 전", "어제". */
export function ago(isoString) {
  const seconds = (Date.now() - new Date(isoString).getTime()) / 1000;
  if (seconds < 60) return '방금';
  if (seconds < 3600) return `${Math.floor(seconds / 60)}분 전`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}시간 전`;
  if (seconds < 172800) return '어제';
  return new Date(isoString).toLocaleDateString('ko-KR');
}

/** 버튼을 누르는 동안 두 번 눌리지 않게 잠근다. */
export async function busy(button, task) {
  const label = button.textContent;
  button.disabled = true;
  button.textContent = '…';
  try {
    return await task();
  } finally {
    button.disabled = false;
    button.textContent = label;
  }
}
