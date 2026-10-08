# Baseline Results

Trace-driven simulation of keep-alive policies on a subset of the Azure Functions Trace 2019 dataset.
Recorded: 2026-10-09. These are baselines only. The LSTM, confidence score and confidence-gated policy are not built yet.

## 1. Setup

| Item | Value |
|---|---|
| Dataset | Azure Functions Trace 2019, `invocations_per_function` |
| Functions | 2,000 (first 2,000 in file order, not a random sample) |
| Days | 1 to 7 |
| Granularity | 1 minute |
| Event | one function active in one minute (several calls in the same minute count once) |
| Total events | 3,049,041 |
| Concurrency | ignored (one container per function) |
| Cold start | invocation arrives when the container is not warm |
| Idle warm-minutes | minutes a container is kept warm after an invocation (cost proxy) |
| "Long tail" slice | bottom 20% of functions by event count (429 functions; ties can make it slightly larger than 20%) |

## 2. Fixed keep-alive timeout sweep

| Timeout | Cold starts | Cold-start rate | Idle warm-minutes |
|---|---|---|---|
| 1 min | 1,047,323 | 34.35% | 3,049,041 |
| 5 min | 234,472 | 7.69% | 5,955,052 |
| 10 min | 124,473 | 4.08% | 6,913,971 |
| 20 min | 69,241 | 2.27% | 7,882,014 |
| 30 min | 48,295 | 1.58% | 8,525,565 |
| 60 min | 16,990 | 0.56% | 9,747,851 |

This is the reference curve. A smarter policy is only better if it has fewer cold starts than a fixed timeout at the same idle cost.

### Per-slice detail (fixed timeouts)

| Timeout | Long-tail functions | Long-tail events | Long-tail cold starts | Long-tail rate | Long-tail idle | Rest rate | Rest idle |
|---|---|---|---|---|---|---|---|
| 1 min | 429 | 2,264 | 2,190 | 96.73% | 2,264 | 34.30% | 3,046,777 |
| 5 min | 429 | 2,264 | 2,150 | 94.96% | 10,957 | 7.63% | 5,944,095 |
| 10 min | 429 | 2,264 | 2,127 | 93.95% | 21,659 | 4.02% | 6,892,312 |
| 20 min | 429 | 2,264 | 2,117 | 93.51% | 42,886 | 2.20% | 7,839,128 |
| 30 min | 429 | 2,264 | 2,111 | 93.24% | 64,022 | 1.52% | 8,461,543 |
| 60 min | 429 | 2,264 | 2,101 | 92.80% | 127,208 | 0.49% | 9,620,643 |

The "rest" slice is 1,571 functions with 3,046,777 events.

## 3. EWMA policy sweep

Policy: keep-alive = ceil(multiplier x EWMA of the function's gaps between active minutes), clamped to 1..60 minutes, with a 10-minute fallback before any history exists.

| alpha | multiplier | Cold starts | Cold-start rate | Idle warm-minutes | Long-tail rate |
|---|---|---|---|---|---|
| 0.1 | 0.5 | 869,527 | 28.52% | 6,258,229 | 94.21% |
| 0.1 | 1.0 | 170,830 | 5.60% | 8,753,885 | 94.08% |
| 0.1 | 2.0 | 52,379 | 1.72% | 9,378,181 | 93.82% |
| 0.1 | 3.0 | 31,186 | 1.02% | 9,539,150 | 93.64% |
| 0.3 | 0.5 | 921,477 | 30.22% | 6,208,102 | 94.26% |
| 0.3 | 1.0 | 177,457 | 5.82% | 8,795,840 | 94.08% |
| 0.3 | 2.0 | 61,161 | 2.01% | 9,504,267 | 93.73% |
| 0.3 | 3.0 | 35,718 | 1.17% | 9,753,429 | 93.55% |

## 4. Function distribution by call count

| Band | Functions | Events | Share of events |
|---|---|---|---|
| 1-9 events | 481 | 2,704 | 0.09% |
| 10-49 events | 301 | 7,433 | 0.24% |
| 50-499 events | 492 | 98,295 | 3.22% |
| 500-4,999 events | 468 | 824,815 | 27.05% |
| 5,000+ events | 258 | 2,115,794 | 69.39% |

## 5. What the results show

1. **EWMA, as implemented, does not beat a tuned fixed timeout.** For example, fixed-30 (1.58% cold, 8.53M idle) is better on both axes than EWMA alpha 0.1 x2.0 (1.72% cold, 9.38M idle) and EWMA alpha 0.3 x2.0 (2.01% cold, 9.50M idle). The best EWMA run (alpha 0.1 x3.0: 1.02% cold, 9.54M idle) is beaten by fixed-60 (0.56% cold, 9.75M idle) at about 2% more idle time.
2. **The bottom-20% "long tail" is not a usable slice.** It is 429 functions with 2,264 events in total (about 5 calls per function in a week). Its cold-start rate stays at 93% to 97% under every policy tested, because gaps are far longer than any keep-alive window, and there is too little history to train a model on.
3. **Aggregate cold-start rate is dominated by a few busy functions.** The 258 functions with 5,000+ events produce 69% of all events, while the 1,274 functions with fewer than 500 events produce about 3.6%. Aggregate numbers therefore say little about the sparse majority.

## 6. Open items and caveats

- Report per band, and also report a per-function average (each function weighted equally), not just totals.
- Redefine the target slice. Candidate: functions with enough history to predict from (roughly 10 to 500 events) but irregular gaps. The SRS wording about "long-tail = bottom 20%" needs updating.
- The 2,000 functions are the first 2,000 in file order and may be clustered. Consider a random sample with a fixed seed.
- Next baseline: a percentile-of-gaps policy (keep warm for the 90th-percentile recent gap), then the LSTM and the confidence gate.
- Simulator assumptions (minute granularity, one event per active minute, no concurrency, no container memory limits) must be stated in the report.

## 7. Reproducing these numbers

Backend endpoints, with MySQL running in Docker and the data loaded:

- `GET /api/simulate/fixed?minutes=10`
- `GET /api/simulate/fixed-sweep`
- `GET /api/simulate/ewma?alpha=0.3&multiplier=2.0`
- `GET /api/simulate/ewma-sweep`
- `GET /api/simulate/bands`

The first request after starting the backend loads the table into memory (30 to 90 seconds). Later requests are fast.

## Appendix A. Exact simulator output (full precision)

Copied from the backend's JSON responses. Rates are fractions (0.0408 = 4.08%). Each run has three slices: overall (2,000 functions, 3,049,041 events), longTail (429 functions, 2,264 events) and rest (1,571 functions, 3,046,777 events). The `events` column is therefore the same for every run and is not repeated.

### A.1 Fixed keep-alive sweep

| Policy | Slice | Cold starts | Cold-start rate | Idle warm-minutes |
|---|---|---|---|---|
| fixed-1min | overall | 1047323 | 0.3434925932448924 | 3049041 |
| fixed-1min | longTail | 2190 | 0.9673144876325088 | 2264 |
| fixed-1min | rest | 1045133 | 0.34302904347774715 | 3046777 |
| fixed-5min | overall | 234472 | 0.07690024502786286 | 5955052 |
| fixed-5min | longTail | 2150 | 0.9496466431095406 | 10957 |
| fixed-5min | rest | 232322 | 0.0762517243631549 | 5944095 |
| fixed-10min | overall | 124473 | 0.040823655700267726 | 6913971 |
| fixed-10min | longTail | 2127 | 0.9394876325088339 | 21659 |
| fixed-10min | rest | 122346 | 0.04015587619310504 | 6892312 |
| fixed-20min | overall | 69241 | 0.022709107552177882 | 7882014 |
| fixed-20min | longTail | 2117 | 0.9350706713780919 | 42886 |
| fixed-20min | rest | 67124 | 0.022031149637797582 | 7839128 |
| fixed-30min | overall | 48295 | 0.01583940655438874 | 8525565 |
| fixed-30min | longTail | 2111 | 0.9324204946996466 | 64022 |
| fixed-30min | rest | 46184 | 0.015158313194565929 | 8461543 |
| fixed-60min | overall | 16990 | 0.005572243862906403 | 9747851 |
| fixed-60min | longTail | 2101 | 0.9280035335689046 | 127208 |
| fixed-60min | rest | 14889 | 0.004886803333489783 | 9620643 |

### A.2 EWMA sweep

Run names are `ewma-a<alpha>-x<multiplier>`; min 1, max 60, fallback 10.

| Policy | Slice | Cold starts | Cold-start rate | Idle warm-minutes |
|---|---|---|---|---|
| ewma-a0.1-x0.5 | overall | 869527 | 0.28518048789767014 | 6258229 |
| ewma-a0.1-x0.5 | longTail | 2133 | 0.9421378091872792 | 104450 |
| ewma-a0.1-x0.5 | rest | 867394 | 0.2846923158472051 | 6153779 |
| ewma-a0.1-x1.0 | overall | 170830 | 0.05602745256623312 | 8753885 |
| ewma-a0.1-x1.0 | longTail | 2130 | 0.9408127208480566 | 104910 |
| ewma-a0.1-x1.0 | rest | 168700 | 0.05536998605411555 | 8648975 |
| ewma-a0.1-x2.0 | overall | 52379 | 0.01717884410212916 | 9378181 |
| ewma-a0.1-x2.0 | longTail | 2124 | 0.9381625441696113 | 105460 |
| ewma-a0.1-x2.0 | rest | 50255 | 0.016494479248070994 | 9272721 |
| ewma-a0.1-x3.0 | overall | 31186 | 0.010228134026403712 | 9539150 |
| ewma-a0.1-x3.0 | longTail | 2120 | 0.9363957597173145 | 105844 |
| ewma-a0.1-x3.0 | rest | 29066 | 0.009539917099282291 | 9433306 |
| ewma-a0.3-x0.5 | overall | 921477 | 0.30221863202233096 | 6208102 |
| ewma-a0.3-x0.5 | longTail | 2134 | 0.9425795053003534 | 104619 |
| ewma-a0.3-x0.5 | rest | 919343 | 0.30174279246561203 | 6103483 |
| ewma-a0.3-x1.0 | overall | 177457 | 0.05820092284754452 | 8795840 |
| ewma-a0.3-x1.0 | longTail | 2130 | 0.9408127208480566 | 105252 |
| ewma-a0.3-x1.0 | rest | 175327 | 0.05754507139839903 | 8690588 |
| ewma-a0.3-x2.0 | overall | 61161 | 0.020059093990536698 | 9504267 |
| ewma-a0.3-x2.0 | longTail | 2122 | 0.9372791519434629 | 105778 |
| ewma-a0.3-x2.0 | rest | 59039 | 0.019377525824830634 | 9398489 |
| ewma-a0.3-x3.0 | overall | 35718 | 0.01171450301914602 | 9753429 |
| ewma-a0.3-x3.0 | longTail | 2118 | 0.9355123674911661 | 106062 |
| ewma-a0.3-x3.0 | rest | 33600 | 0.011028047014927577 | 9647367 |

### A.3 Function distribution by call count

| Band | Functions | Events |
|---|---|---|
| 1-9 events | 481 | 2704 |
| 10-49 events | 301 | 7433 |
| 50-499 events | 492 | 98295 |
| 500-4999 events | 468 | 824815 |
| 5000+ events | 258 | 2115794 |

Sanity checks: functions sum to 2,000 and events sum to 3,049,041. For every run, cold starts plus warm hits equal total events, and the overall cold-start count equals longTail plus rest (for example fixed-10min: 2,127 + 122,346 = 124,473).
