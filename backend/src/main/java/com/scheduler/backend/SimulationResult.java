package com.scheduler.backend;

public record SimulationResult(String policy, long functions, long invocationEvents,
                               long coldStarts, long warmHits,
                               double coldStartRate, long idleWarmMinutes) { }