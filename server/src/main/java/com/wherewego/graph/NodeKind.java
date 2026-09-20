package com.wherewego.graph;

/** 노드의 종류. {@code graph_node.kind} 와 같다. */
public enum NodeKind {
    /** OSM 교차점. */
    WALK,
    /** 역·정류장 자체. 진출입의 문. */
    STOP,
    /** (정류장 × 노선). 실제로 타는 자리. */
    PLATFORM;

    private static final NodeKind[] VALUES = values();

    public static NodeKind of(byte code) {
        return VALUES[code];
    }

    public byte code() {
        return (byte) ordinal();
    }
}
