package com.scheduler.backend;

public record SliceStats(long functions, long events, long coldStarts,
                         double coldStartRate, long idleWarmMinutes) { }