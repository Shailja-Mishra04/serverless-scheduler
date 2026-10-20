// All calls to the Spring Boot backend live here.
// Change the base URL with a .env file (see .env.example) if your backend is not on port 8080.
export const API_BASE = import.meta.env.VITE_API_BASE || "http://localhost:8080";

// Query parameter names for each endpoint. If your SimulationController uses
// different @RequestParam names, change them here and nowhere else.
export const ENDPOINTS = {
  fixed: { path: "/api/simulate/fixed", params: ["minutes"] },
  ewma: { path: "/api/simulate/ewma", params: ["alpha", "multiplier"] },
  percentile: { path: "/api/simulate/percentile", params: ["p", "maxMinutes"] },
};

// The first call after backend start-up loads the whole table (30-90 s), so allow 3 minutes.
const TIMEOUT_MS = 180000;

async function getJson(path, query = {}) {
  const url = new URL(path, API_BASE);
  Object.entries(query).forEach(([k, v]) => url.searchParams.set(k, v));
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  let res;
  try {
    res = await fetch(url, { signal: controller.signal });
  } catch (err) {
    if (err.name === "AbortError") throw new Error("The backend took longer than 3 minutes to answer.");
    // fetch throws a TypeError for both "server not running" and "blocked by CORS".
    throw new Error(
      `Could not reach ${API_BASE}. Check that Spring Boot is running and that CorsConfig.java is in the backend.`
    );
  } finally {
    clearTimeout(timer);
  }
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`Backend answered ${res.status} for ${url.pathname}. ${text.slice(0, 200)}`);
  }
  return res.json();
}

export function fetchSummary() {
  return getJson("/api/simulate/summary");
}

// kind is "fixed", "ewma" or "percentile"; values maps each param name to its value.
export function runPolicy(kind, values) {
  const ep = ENDPOINTS[kind];
  const query = {};
  ep.params.forEach((p) => (query[p] = values[p]));
  return getJson(ep.path, query).then((data) => ({ url: `${ep.path}?${new URLSearchParams(query)}`, data }));
}
