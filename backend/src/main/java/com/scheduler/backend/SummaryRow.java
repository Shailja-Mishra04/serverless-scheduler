package com.scheduler.backend;

import java.util.Map;

public record SummaryRow(String policy, double coldStartRate, double meanFunctionColdStartRate,
                         long idleWarmMinutes, Map<String, Double> bandMeanFunctionColdStartRate,
                         Map<String, Long> bandIdleWarmMinutes) { }