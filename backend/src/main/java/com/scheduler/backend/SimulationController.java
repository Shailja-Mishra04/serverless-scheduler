package com.scheduler.backend;

import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/simulate")
public class SimulationController {

    private final SimulationService service;

    public SimulationController(SimulationService service) { this.service = service; }

    @GetMapping("/fixed")
    public SimulationResult fixed(@RequestParam(defaultValue = "10") int minutes) {
        return service.run("fixed-" + minutes + "min", () -> new FixedKeepAlivePolicy(minutes));
    }

    @GetMapping("/ewma")
    public SimulationResult ewma(@RequestParam(defaultValue = "0.3") double alpha,
                                 @RequestParam(defaultValue = "2.0") double multiplier,
                                 @RequestParam(defaultValue = "1") int min,
                                 @RequestParam(defaultValue = "60") int max,
                                 @RequestParam(defaultValue = "10") int fallback) {
        return service.run("ewma-a" + alpha + "-x" + multiplier,
                () -> new EwmaKeepAlivePolicy(alpha, multiplier, min, max, fallback));
    }

    @GetMapping("/percentile")
    public SimulationResult percentile(@RequestParam(defaultValue = "0.9") double p,
                                       @RequestParam(defaultValue = "30") int window,
                                       @RequestParam(defaultValue = "3") int minGaps,
                                       @RequestParam(defaultValue = "1") int min,
                                       @RequestParam(defaultValue = "60") int max,
                                       @RequestParam(defaultValue = "10") int fallback,
                                       @RequestParam(defaultValue = "1.0") double margin) {
        return service.run("pct-p" + p + "-max" + max,
                () -> new PercentileKeepAlivePolicy(p, window, minGaps, min, max, fallback, margin));
    }

    @GetMapping("/bands")
    public List<BandStats> bands() { return service.bands(); }

    /** One compact table: fixed sweep + EWMA sweep + percentile sweep, run in parallel. */
    @GetMapping("/summary")
    public List<SummaryRow> summary() {
        List<Supplier<SimulationResult>> jobs = new ArrayList<>();

        for (int m : new int[]{1, 5, 10, 20, 30, 60}) {
            jobs.add(() -> service.run("fixed-" + m + "min", () -> new FixedKeepAlivePolicy(m)));
        }
        for (double a : new double[]{0.1, 0.3}) {
            for (double x : new double[]{0.5, 1.0, 2.0, 3.0}) {
                jobs.add(() -> service.run("ewma-a" + a + "-x" + x,
                        () -> new EwmaKeepAlivePolicy(a, x, 1, 60, 10)));
            }
        }
        for (int mx : new int[]{30, 60}) {
            for (double p : new double[]{0.5, 0.8, 0.9, 0.95, 0.99}) {
                jobs.add(() -> service.run("pct-p" + p + "-max" + mx,
                        () -> new PercentileKeepAlivePolicy(p, 30, 3, 1, mx, 10, 1.0)));
            }
        }
        return jobs.parallelStream().map(Supplier::get)
                .map(SimulationController::summarize).toList();
    }

    private static SummaryRow summarize(SimulationResult r) {
        Map<String, Double> b = new LinkedHashMap<>();
        r.bands().forEach((k, v) -> b.put(k, v.meanFunctionColdStartRate()));
        return new SummaryRow(r.policy(), r.overall().coldStartRate(),
                r.overall().meanFunctionColdStartRate(), r.overall().idleWarmMinutes(), b);
    }
}