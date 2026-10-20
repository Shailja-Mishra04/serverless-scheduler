// Shared helpers: policy families, colors and number formatting.

export const BANDS = ["1-9", "10-49", "50-499", "500-4999", "5000+"];

// Chart colors (checked for color-blind separation on the dark card color).
export const COLORS = {
  gate: "#0d9488", // teal
  hist: "#8b5cf6", // purple
  lstm: "#d97706", // amber
  blue: "#3b82f6",
  fixed: "#94a3b8", // neutral gray reference curve
  other: "#4b5578", // muted dots for the remaining baselines
};

// Colors for the up-to-4 policies picked on the Per band page, in fixed order.
export const PICK_COLORS = [COLORS.gate, COLORS.hist, COLORS.lstm, COLORS.blue];

export const FAMILIES = {
  fixed: "Fixed timeout",
  ewma: "EWMA",
  pct: "Percentile",
  floor: "Floor + adaptive",
  hist: "Hybrid histogram",
  gate: "Confidence gate",
  lstm: "LSTM always",
  other: "Other",
};

export function familyOf(policy) {
  if (policy.startsWith("fixed-")) return "fixed";
  if (policy.startsWith("ewma-")) return "ewma";
  if (policy.startsWith("pct-")) return "pct";
  if (policy.startsWith("floor")) return "floor";
  if (policy.startsWith("hist-")) return "hist";
  if (policy.startsWith("gate-")) return "gate";
  if (policy.startsWith("lstm-")) return "lstm";
  return "other";
}

// Policies that get their own color on the Results scatter.
export const HIGHLIGHT_FAMILIES = ["hist", "gate", "lstm"];

export const DEFAULT_BAND_PICKS = [
  "gate-hist240",
  "hist-max240-fb60",
  "lstm-always-hist240",
  "fixed-60min",
];

export const pct = (x, digits = 3) =>
  x === null || x === undefined || Number.isNaN(x) ? "—" : `${(x * 100).toFixed(digits)}%`;

export const int = (x) =>
  x === null || x === undefined || Number.isNaN(x) ? "—" : Math.round(x).toLocaleString("en-US");

export const millions = (x, digits = 2) =>
  x === null || x === undefined || Number.isNaN(x) ? "—" : `${(x / 1e6).toFixed(digits)}M`;

// Relative change of a vs b, as a signed percent string, e.g. "-4.3%".
export function relChange(a, b) {
  if (!b) return "—";
  const v = ((a - b) / b) * 100;
  return `${v > 0 ? "+" : ""}${v.toFixed(1)}%`;
}

// Legend labels stay in text color; the colored marker next to them carries the identity.
export const legendText = (value) => <span style={{ color: "#c3c8db" }}>{value}</span>;
