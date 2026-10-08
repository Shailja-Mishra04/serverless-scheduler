package com.scheduler.backend;

public record SimulationResult(String policy, SliceStats overall,
                               SliceStats longTail, SliceStats rest) { }