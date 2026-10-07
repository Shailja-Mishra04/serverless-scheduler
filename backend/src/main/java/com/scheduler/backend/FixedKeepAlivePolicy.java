package com.scheduler.backend;

public class FixedKeepAlivePolicy implements KeepAlivePolicy {
    private final int minutes;
    public FixedKeepAlivePolicy(int minutes) { this.minutes = minutes; }
    @Override public void onInvocation(long minute) { }
    @Override public int keepAliveMinutes() { return minutes; }
}