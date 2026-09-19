package com.wherewego.graph;

/** 교통수단. 노드가 어느 망에 속하는지. 보행 노드는 어느 쪽도 아니다. */
public enum TransitMode {
    SUBWAY,
    BUS;

    /** 수단 없음(보행 노드). {@code byte} 로 눌러 담으므로 null 대신 이 값을 쓴다. */
    public static final byte NONE = -1;

    private static final TransitMode[] VALUES = values();

    public static TransitMode of(byte code) {
        return code == NONE ? null : VALUES[code];
    }

    public static byte codeOf(String name) {
        if (name == null) return NONE;
        return (byte) valueOf(name).ordinal();
    }

    public byte code() {
        return (byte) ordinal();
    }
}
