package com.scheduler.backend;

public interface KeepAlivePolicy {
    void onInvocation(long minute);   // lets stateful policies (EWMA, LSTM) learn
    int keepAliveMinutes();           // how long to stay warm after this invocation
}
