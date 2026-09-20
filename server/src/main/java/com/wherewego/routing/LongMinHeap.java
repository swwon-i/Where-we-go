package com.wherewego.routing;

import java.util.Arrays;

/**
 * {@code long} 하나를 원소로 쓰는 최소 힙.
 *
 * <p>{@code PriorityQueue<long[]>} 나 {@code PriorityQueue<Long>} 를 쓰면 원소마다 객체가
 * 하나씩 생긴다. 다익스트라는 엣지 수만큼 넣으므로 60만 개짜리 그래프에서 한 번 탐색에
 * 수십만 개의 쓰레기가 나오고, 비교할 때마다 참조를 따라가야 한다.
 *
 * <p>여기서는 <b>거리와 노드를 long 하나에 눌러 담는다</b>.
 *
 * <pre>
 *   상위 32비트 = 거리(초)   하위 32비트 = 노드 번호
 * </pre>
 *
 * 거리가 상위 비트이므로 long 을 그냥 크기순으로 비교하면 거리순 정렬이 된다. 비교자도,
 * 객체도 필요 없이 배열 하나로 끝난다.
 *
 * <p>거리는 31비트에 담기므로 약 68년까지 표현된다. 경로 탐색에서 넘을 일이 없다.
 */
final class LongMinHeap {

    private long[] heap;
    private int size;

    LongMinHeap(int initialCapacity) {
        this.heap = new long[Math.max(16, initialCapacity)];
    }

    static long pack(int distance, int node) {
        return ((long) distance << 32) | (node & 0xFFFF_FFFFL);
    }

    static int distanceOf(long packed) {
        return (int) (packed >>> 32);
    }

    static int nodeOf(long packed) {
        return (int) packed;
    }

    boolean isEmpty() {
        return size == 0;
    }

    void push(long value) {
        if (size == heap.length) {
            heap = Arrays.copyOf(heap, size * 2);
        }
        int i = size++;
        // 위로 올리기 — 부모보다 작으면 자리를 바꾼다
        while (i > 0) {
            int parent = (i - 1) >>> 1;
            if (heap[parent] <= value) break;
            heap[i] = heap[parent];
            i = parent;
        }
        heap[i] = value;
    }

    long pop() {
        long top = heap[0];
        long last = heap[--size];
        if (size > 0) {
            // 아래로 내리기 — 두 자식 중 작은 쪽과 비교한다
            int i = 0;
            int half = size >>> 1;
            while (i < half) {
                int child = (i << 1) + 1;
                int right = child + 1;
                if (right < size && heap[right] < heap[child]) {
                    child = right;
                }
                if (heap[child] >= last) break;
                heap[i] = heap[child];
                i = child;
            }
            heap[i] = last;
        }
        return top;
    }

    int size() {
        return size;
    }
}
