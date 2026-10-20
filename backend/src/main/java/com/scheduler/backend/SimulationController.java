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

    private static KeepAlivePolicy hist(int maxEnd) {
        return new HybridHistogramPolicy(240, 10, 0.05, 0.99, 0.10, 2.0, maxEnd, 60);
    }

    /** One compact table of every policy, run in parallel. Scores days 6-7 only. */
    @GetMapping("/summary")
    public List<SummaryRow> summary() {
        List<Supplier<SimulationResult>> jobs = new ArrayList<>();

        for (int m : new int[]{1, 5, 10, 20, 30, 40, 45, 50, 60}) {
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
        for (int fl : new int[]{5, 10, 20}) {
            for (double p : new double[]{0.9, 0.99}) {
                jobs.add(() -> service.run("floor" + fl + "-pct-p" + p + "-max60",
                        () -> new FloorKeepAlivePolicy(fl,
                                new PercentileKeepAlivePolicy(p, 30, 3, 1, 60, 10, 1.0))));
            }
            for (double x : new double[]{1.0, 2.0}) {
                jobs.add(() -> service.run("floor" + fl + "-ewma-a0.1-x" + x,
                        () -> new FloorKeepAlivePolicy(fl,
                                new EwmaKeepAlivePolicy(0.1, x, 1, 60, 10))));
            }
        }
        // hybrid histogram: {max window end, fallback keep-alive}
        for (int[] cfg : new int[][]{{60, 10}, {60, 60}, {240, 60}}) {
            jobs.add(() -> service.run("hist-max" + cfg[0] + "-fb" + cfg[1],
                    () -> new HybridHistogramPolicy(240, 10, 0.05, 0.99, 0.10, 2.0, cfg[0], cfg[1])));
        }
        // LSTM + confidence gate: (predictions, fallback, max error variance, min errors,
        //                          k std devs, error window, min window end, max window end)
        jobs.add(() -> service.runPerFunction("gate-hist240", fid ->
                new LstmGatedPolicy(service.predictionsFor(fid), hist(240), 0.25, 10, 2.0, 20, 10, 240)));
        jobs.add(() -> service.runPerFunction("gate-hist60", fid ->
                new LstmGatedPolicy(service.predictionsFor(fid), hist(60), 0.25, 10, 2.0, 20, 10, 60)));
        jobs.add(() -> service.runPerFunction("gate-ewma", fid ->
                new LstmGatedPolicy(service.predictionsFor(fid),
                        new EwmaKeepAlivePolicy(0.1, 2.0, 1, 60, 10), 0.25, 10, 2.0, 20, 10, 60)));
        jobs.add(() -> service.runPerFunction("lstm-always-hist240", fid ->
                new LstmGatedPolicy(service.predictionsFor(fid), hist(240),
                        Double.MAX_VALUE, 0, 2.0, 20, 10, 240)));

        return jobs.parallelStream().map(Supplier::get)
                .map(SimulationController::summarize).toList();
    }

    private static SummaryRow summarize(SimulationResult r) {
        Map<String, Double> rate = new LinkedHashMap<>();
        Map<String, Long> idle = new LinkedHashMap<>();
        r.bands().forEach((k, v) -> {
            rate.put(k, v.meanFunctionColdStartRate());
            idle.put(k, v.idleWarmMinutes());
        });
        return new SummaryRow(r.policy(), r.overall().coldStartRate(),
                r.overall().meanFunctionColdStartRate(), r.overall().idleWarmMinutes(), rate, idle);
    }
}