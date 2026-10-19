package com.scheduler.backend;

public interface KeepAlivePolicy {
    void onInvocation(long minute);   // lets stateful policies (EWMA, LSTM) learn
    int keepAliveMinutes();           // how long to stay warm (after the pre-warm delay)
    default int preWarmMinutes() { return 0; } // minutes to stay unloaded before warming up again
}