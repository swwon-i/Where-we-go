package com.wherewego.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 초대 코드. DB 없이 돈다. */
class InviteCodeTest {

    @Test
    @DisplayName("8자이고, 헷갈리는 글자가 들어가지 않는다")
    void generatedShape() {
        for (int i = 0; i < 500; i++) {
            String code = InviteCode.generate();
            assertThat(code).hasSize(8).matches("[0-9A-HJKMNP-TV-Z]{8}");
            // I·L·O·U 는 0·1·V 와 헷갈린다. 애초에 뽑지 않는다.
            assertThat(code).doesNotContain("I").doesNotContain("L")
                    .doesNotContain("O").doesNotContain("U");
        }
    }

    @Test
    @DisplayName("DB 제약이 받아들이는 형식이어야 한다")
    void matchesDatabaseConstraint() {
        // V9 의 CHECK 와 같은 정규식. 둘이 어긋나면 방을 못 만든다.
        for (int i = 0; i < 200; i++) {
            assertThat(InviteCode.generate()).matches("^[0-9A-HJKMNP-TV-Z]{8}$");
        }
    }

    @Test
    @DisplayName("같은 코드가 연달아 나오지 않는다")
    void generatedValuesDiffer() {
        var seen = new HashSet<String>();
        for (int i = 0; i < 2_000; i++) seen.add(InviteCode.generate());
        // 32^8 에서 2,000개를 뽑으면 충돌 확률이 사실상 0이다.
        assertThat(seen).hasSize(2_000);
    }

    @ParameterizedTest(name = "\"{0}\" → {1}")
    @CsvSource({
        // 있는 그대로
        "K7MP3XQR, K7MP3XQR",
        // 표시용 하이픈과 공백은 버린다
        "K7MP-3XQR, K7MP3XQR",
        "k7mp 3xqr, K7MP3XQR",
        // 헷갈려 잘못 적은 글자를 제자리로 돌린다
        "O7MP3XQR, 07MP3XQR",
        "I7MP3XQR, 17MP3XQR",
        "l7mp3xqr, 17MP3XQR",
        "U7MP3XQR, V7MP3XQR",
    })
    @DisplayName("사람이 친 것을 저장 형태로 맞춘다")
    void normalization(String typed, String expected) {
        assertThat(InviteCode.normalize(typed)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"K7MP3XQ", "K7MP3XQRR", "", "   ", "K7MP-3XQ!"})
    @DisplayName("길이나 글자가 맞지 않으면 null — 형식 자체가 아니다")
    void rejectsMalformed(String typed) {
        assertThat(InviteCode.normalize(typed)).isNull();
    }

    @Test
    @DisplayName("null 을 넣어도 터지지 않는다")
    void nullIsNull() {
        assertThat(InviteCode.normalize(null)).isNull();
    }

    @Test
    @DisplayName("표시는 네 글자씩 끊는다 — 눈으로 따라 읽기 위한 것이다")
    void formatting() {
        assertThat(InviteCode.format("K7MP3XQR")).isEqualTo("K7MP-3XQR");
    }

    @Test
    @DisplayName("끊어 보여준 것을 그대로 다시 쳐도 같은 코드가 된다")
    void formatAndNormalizeRoundTrip() {
        for (int i = 0; i < 200; i++) {
            String code = InviteCode.generate();
            assertThat(InviteCode.normalize(InviteCode.format(code))).isEqualTo(code);
        }
    }
}
