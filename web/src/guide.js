/**
 * 사용 방법 — 화면 캡쳐를 한 장씩 넘겨 보는 팝업.
 *
 * 이 서비스는 "방을 만들고 → 각자 출발지를 정하고 → 후보를 담고 → 이동시간을 비교한다"는
 * 순서를 따라야 쓸 수 있는데, 그 순서가 화면 어디에도 적혀 있지 않았다. 처음 온 사람이
 * 빈 방을 보고 무엇부터 해야 할지 몰라 멈추는 자리다.
 *
 * <p>캡쳐는 `web/public/guide/` 에 있고 실제 화면을 찍은 것이다. 화면을 고치면
 * 그림도 같이 낡으므로, 디자인을 바꿀 때 다시 찍어 같은 이름으로 덮어쓴다.
 *
 * <p>`<dialog>` 를 쓴다 — Esc 로 닫기, 바깥 영역 어둡게, 포커스 가두기를 브라우저가 해 준다.
 */

import { $, $$ } from './dom.js';

/** 한 장이 한 단계다. 순서가 곧 쓰는 순서다. */
const STEPS = [
  {
    title: '방을 만들고 친구를 부릅니다',
    image: '1-rooms',
    body: `모임 이름을 적고 만들면 초대 코드가 나옵니다.
           코드를 알려 주거나 방 링크를 보내면 친구가 같은 방으로 들어옵니다.`,
  },
  {
    title: '각자 출발지를 정합니다',
    image: '2-origins',
    body: `참가자마다 어디서 출발하는지 정합니다. 지하철역 이름을 적거나 지도에서 직접 고릅니다.
           파란 핀이 출발지입니다.`,
  },
  {
    title: '갈 만한 곳을 후보로 담습니다',
    image: '3-find',
    body: `상호명으로 찾으면 지도에 회색 핀이 찍힙니다. 핀이나 목록을 누르면 가게 정보가 뜨고,
           거기서 북마크를 누르면 후보가 됩니다. 담은 후보는 빨간 핀으로 바뀝니다.`,
  },
  {
    title: '누가 얼마나 걸리는지 봅니다',
    image: '4-matrix',
    body: `계산을 누르면 후보마다 사람별 이동시간이 나옵니다. 출발 시각대를 바꾸면
           그 시간대의 버스·지하철 배차로 다시 계산합니다.
           순위는 매기지 않습니다 — 가장 빨리 닿는 사람만 파란 숫자로 표시합니다.`,
  },
  {
    title: '경로를 펼쳐 봅니다',
    image: '5-route',
    body: `칸을 누르면 도보 · 승차 · 주행 · 환승이 한 줄씩 펼쳐지고 지도에 경로가 그려집니다.
           기다리는 시간이 얼마나 되는지도 함께 보입니다.`,
  },
];

let dialog = null;
let at = 0;

/** 헤더의 "사용 방법" 버튼을 잇는다. 앱이 시작할 때 한 번 부른다. */
export function wireGuide() {
  const button = document.getElementById('nav-guide');
  if (button) button.onclick = () => openGuide();
}

export function openGuide(step = 0) {
  if (!dialog) build();
  go(step);
  dialog.showModal();
}

function build() {
  dialog = document.createElement('dialog');
  dialog.className = 'guide';
  dialog.innerHTML = `
    <div class="guide-head">
      <h2 id="guide-title"></h2>
      <button class="guide-x" data-act="close" aria-label="닫기">×</button>
    </div>
    <div class="guide-shot">
      <img id="guide-img" alt="">
    </div>
    <p class="guide-text" id="guide-text"></p>
    <div class="guide-foot">
      <button class="guide-arrow prev" data-act="prev" aria-label="이전">
        <span class="chev" aria-hidden="true"></span>
      </button>
      <div class="guide-dots" id="guide-dots" role="tablist"></div>
      <button class="guide-arrow next" data-act="next" aria-label="다음">
        <span class="chev" aria-hidden="true"></span>
      </button>
    </div>`;
  document.body.appendChild(dialog);

  $('#guide-dots', dialog).innerHTML = STEPS
    .map((s, i) => `<button class="guide-dot" data-to="${i}" role="tab"
                      aria-label="${i + 1}단계"><span></span></button>`)
    .join('');

  dialog.onclick = (e) => {
    // 바깥(::backdrop)을 눌러도 닫는다. dialog 자신이 클릭 대상이면 내용 밖이다.
    if (e.target === dialog) { dialog.close(); return; }
    const act = e.target.closest('[data-act]')?.dataset.act;
    if (act === 'close') dialog.close();
    if (act === 'prev') go(at - 1);
    if (act === 'next') go(at + 1);
    const to = e.target.closest('[data-to]')?.dataset.to;
    if (to != null) go(Number(to));
  };

  // 문서에 건다. 끝 단계에서 화살표 버튼이 꺼지면 포커스가 버튼 밖으로 밀려나는데,
  // dialog 에만 걸어 두면 그때부터 좌우 키가 먹지 않는다.
  document.addEventListener('keydown', (e) => {
    if (!dialog.open) return;
    if (e.key === 'ArrowLeft') { e.preventDefault(); go(at - 1); }
    if (e.key === 'ArrowRight') { e.preventDefault(); go(at + 1); }
  });
}

function go(next) {
  at = Math.max(0, Math.min(STEPS.length - 1, next));
  const step = STEPS[at];

  $('#guide-title', dialog).textContent = `${at + 1}. ${step.title}`;
  $('#guide-text', dialog).textContent = step.body.replace(/\s+/g, ' ').trim();

  const img = $('#guide-img', dialog);
  img.src = `/guide/${step.image}.webp`;
  img.alt = step.title;

  // 다음 장을 미리 받아 둔다. 넘길 때 빈 칸이 보이지 않게 한다.
  const after = STEPS[at + 1];
  if (after) new Image().src = `/guide/${after.image}.webp`;

  $('.guide-arrow.prev', dialog).disabled = at === 0;
  $('.guide-arrow.next', dialog).disabled = at === STEPS.length - 1;
  $$('.guide-dot', dialog).forEach((d, i) => {
    d.classList.toggle('is-on', i === at);
    d.setAttribute('aria-selected', String(i === at));
  });
}
