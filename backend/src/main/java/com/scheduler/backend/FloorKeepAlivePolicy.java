package com.scheduler.backend;

public class FloorKeepAlivePolicy implements KeepAlivePolicy {
    private final int floor;
    private final KeepAlivePolicy inner;

    public FloorKeepAlivePolicy(int floor, KeepAlivePolicy inner) {
        this.floor = floor; this.inner = inner;
    }

    @Override public void onInvocation(long t) { inner.onInvocation(t); }

    @Override public int keepAliveMinutes() {
        return Math.max(floor, inner.keepAliveMinutes()); // never shorter than the floor
    }
}