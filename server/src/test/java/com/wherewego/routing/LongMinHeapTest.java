package com.wherewego.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 거리와 노드를 long 하나에 담는 힙. 다익스트라가 맞으려면 이게 먼저 맞아야 한다. */
class LongMinHeapTest {

    @Test
    @DisplayName("거리와 노드가 왕복해도 그대로다")
    void packAndUnpack() {
        long packed = LongMinHeap.pack(123_456, 654_321);
        assertThat(LongMinHeap.distanceOf(packed)).isEqualTo(123_456);
        assertThat(LongMinHeap.nodeOf(packed)).isEqualTo(654_321);
    }

    @Test
    @DisplayName("거리가 상위 비트라 long 을 그냥 비교해도 거리순이다")
    void distanceDominatesOrdering() {
        assertThat(LongMinHeap.pack(1, 999_999)).isLessThan(LongMinHeap.pack(2, 0));
    }

    @Test
    @DisplayName("꺼내는 순서가 거리 오름차순이다")
    void popsInAscendingOrder() {
        var heap = new LongMinHeap(4);
        var random = new Random(7);
        var pushed = new ArrayList<Integer>();
        for (int i = 0; i < 5_000; i++) {
            int d = random.nextInt(1_000_000);
            pushed.add(d);
            heap.push(LongMinHeap.pack(d, i));
        }
        pushed.sort(null);

        var popped = new ArrayList<Integer>();
        while (!heap.isEmpty()) {
            popped.add(LongMinHeap.distanceOf(heap.pop()));
        }
        assertThat(popped).isEqualTo(pushed);
    }

    @Test
    @DisplayName("초기 용량을 넘겨도 늘어난다")
    void growsBeyondInitialCapacity() {
        var heap = new LongMinHeap(2);
        for (int i = 100; i > 0; i--) heap.push(LongMinHeap.pack(i, i));
        assertThat(heap.size()).isEqualTo(100);
        assertThat(LongMinHeap.distanceOf(heap.pop())).isEqualTo(1);
    }
}
