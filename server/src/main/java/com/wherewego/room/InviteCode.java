package com.wherewego.room;

import java.security.SecureRandom;

/**
 * 방 초대 코드.
 *
 * <h2>왜 UUID 로 충분하지 않은가</h2>
 *
 * UUID 는 링크에 박아 보내기에는 좋지만 <b>사람이 받아 적을 수 없다</b>. 전화로 불러주거나
 * 칠판에 쓰는 일이 안 된다. 코드는 그 통로를 여는 것이고, UUID 는 그대로 기본키로 남는다.
 *
 * <h2>글자 고르기 — Crockford Base32</h2>
 *
 * {@code I L O U} 를 뺀 32글자를 쓴다. {@code 0}과 {@code O}, {@code 1}과 {@code I}·{@code L}은
 * 손글씨로도 화면으로도 헷갈린다. 받아 적은 코드가 한 글자 틀려 못 들어가면
 * "코드가 안 되는데요"가 되고, 그것은 우리가 고를 수 있었던 문제다.
 * ({@code U}는 뜻하지 않은 비속어가 만들어지는 것을 줄이려고 뺀다.)
 *
 * <h2>왜 8자인가</h2>
 *
 * 방에 들어가면 다른 참가자의 출발지가 보인다. 누가 어디서 출발하는지는 가볍게 흘릴 정보가
 * 아니므로 코드가 추측 가능하면 안 된다.
 *
 * <pre>
 *   6자 → 32^6 ≈ 10억      초당 10번 찍으면 몇 년
 *   8자 → 32^8 ≈ 1조       사실상 불가능
 * </pre>
 *
 * 한 글자 더 받아 적는 값으로 충분히 산다.
 */
public final class InviteCode {

    /** Crockford Base32. I·L·O·U 가 없다. */
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    public static final int LENGTH = 8;

    /** 추측을 막는 것이 목적이므로 {@code Random} 이 아니라 {@code SecureRandom} 이다. */
    private static final SecureRandom RANDOM = new SecureRandom();

    private InviteCode() {}

    public static String generate() {
        var out = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            out[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(out);
    }

    /**
     * 사람이 친 것을 저장 형태로 맞춘다.
     *
     * <p>하이픈·공백은 버리고, 소문자는 올리고, 헷갈리기 쉬운 글자는 제자리로 돌린다.
     * {@code O}를 친 사람은 {@code 0}을 뜻한 것이고 {@code l}을 친 사람은 {@code 1}을 뜻한 것이다 —
     * 애초에 그 글자들을 쓰지 않으므로 다른 해석이 없다.
     *
     * @return 정규화된 8자, 또는 형식이 맞지 않으면 null
     */
    public static String normalize(String raw) {
        if (raw == null) return null;

        var out = new StringBuilder(LENGTH);
        for (char c : raw.toUpperCase().toCharArray()) {
            char mapped = switch (c) {
                case 'O' -> '0';
                case 'I', 'L' -> '1';
                case 'U' -> 'V';
                default -> c;
            };
            if (mapped == '-' || mapped == ' ') continue;   // 표시용 구분자
            if (Character.isLetterOrDigit(mapped)) out.append(mapped);
        }

        String code = out.toString();
        if (code.length() != LENGTH) return null;
        for (char c : code.toCharArray()) {
            if (new String(ALPHABET).indexOf(c) < 0) return null;
        }
        return code;
    }

    /** 화면에 보일 형태. 끊어 두면 눈으로 따라 읽기 쉽다. */
    public static String format(String code) {
        if (code == null || code.length() != LENGTH) return code;
        return code.substring(0, 4) + "-" + code.substring(4);
    }
}
