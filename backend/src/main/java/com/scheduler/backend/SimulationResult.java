package com.scheduler.backend;

import java.util.Map;

public record SimulationResult(String policy, SliceStats overall, SliceStats longTail,
                               SliceStats rest, Map<String, SliceStats> bands) { }