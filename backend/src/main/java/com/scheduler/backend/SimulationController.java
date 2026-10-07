package com.scheduler.backend;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/simulate")
public class SimulationController {

    private final SimulationService service;

    public SimulationController(SimulationService service) { this.service = service; }

    @GetMapping("/fixed")
    public SimulationResult fixed(@RequestParam(defaultValue = "10") int minutes) {
        return service.run("fixed-" + minutes + "min", () -> new FixedKeepAlivePolicy(minutes));
    }
}