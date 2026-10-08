package com.scheduler.backend;

import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/simulate")
public class SimulationController {

    private final SimulationService service;

    public SimulationController(SimulationService service) { this.service = service; }

    @GetMapping("/fixed")
    public SimulationResult fixed(@RequestParam(defaultValue = "10") int minutes) {
        return service.run("fixed-" + minutes + "min", () -> new FixedKeepAlivePolicy(minutes));
    }

    @GetMapping("/fixed-sweep")
    public List<SimulationResult> fixedSweep() {
        return List.of(1, 5, 10, 20, 30, 60).stream()
                .map(m -> service.run("fixed-" + m + "min", () -> new FixedKeepAlivePolicy(m)))
                .toList();
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

    @GetMapping("/ewma-sweep")
    public List<SimulationResult> ewmaSweep() {
        List<SimulationResult> out = new ArrayList<>();
        for (double a : new double[]{0.1, 0.3}) {
            for (double x : new double[]{0.5, 1.0, 2.0, 3.0}) {
                out.add(service.run("ewma-a" + a + "-x" + x,
                        () -> new EwmaKeepAlivePolicy(a, x, 1, 60, 10)));
            }
        }
        return out;
    }

    @GetMapping("/bands")
    public List<BandStats> bands() { return service.bands(); }
}