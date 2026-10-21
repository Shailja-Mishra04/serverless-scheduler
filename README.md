# Confidence-Gated Serverless Keep-Alive Scheduler

A trace-driven simulator that compares keep-alive and pre-warm policies for serverless functions, using real traffic from the Azure Functions Trace 2019. It also tests a new policy: a small LSTM predicts when each function will be called next, and a per-prediction confidence gate decides whether to trust that prediction or fall back to a proven simple policy.

Semester project for the Software Engineering course, VIT Chennai.

## Contents

1. [The problem](#the-problem)
2. [What this project does](#what-this-project-does)
3. [Results](#results)
4. [Architecture](#architecture)
5. [Repository layout](#repository-layout)
6. [Tech stack](#tech-stack)
7. [Getting started (Windows)](#getting-started-windows)
8. [API reference](#api-reference)
9. [Testing](#testing)
10. [Simulation assumptions](#simulation-assumptions)
11. [Limitations](#limitations)
12. [Project management](#project-management)
13. [References](#references)
14. [Authors](#authors)

## The problem

Serverless platforms put idle functions to sleep to save resources. When a sleeping function is called, it must be started again, which is slow. This is called a cold start. Keeping functions awake for longer avoids cold starts, but every minute a function sits awake with no work is wasted cost (an idle warm-minute).

Most platforms use one fixed keep-alive timeout for every function. A busy function and a function called twice a week get the same treatment. This project asks whether a smarter policy can give fewer cold starts for the same idle cost.

## What this project does

- Replays 7 days of real per-minute invocation data for 2,000 randomly sampled functions (seed 42) through a simulator written in Java.
- Implements and compares 46 policy settings:
  - **Fixed timeout:** keep warm for N minutes after each call (1 to 60 minutes).
  - **EWMA:** keep warm for a multiple of the smoothed average gap between calls.
  - **Percentile:** keep warm for the p-th percentile of recent gaps.
  - **Floor plus adaptive:** never go below a minimum timeout, otherwise follow an adaptive policy.
  - **Hybrid histogram:** a re-implementation of the policy from Shahrad et al. (ATC 2020), which keeps a per-function histogram of idle times and can unload a container and pre-warm it just before the next expected call.
  - **LSTM with confidence gate (this project):** a pooled LSTM predicts the next gap for each function. The gate trusts the prediction only when that function's recent prediction errors are stable, and otherwise falls back to the hybrid histogram.
- Reports cold-start rate and idle warm-minutes overall, per call-count band, and as a per-function average.
- Shows the results in a React dashboard.

**How the gate differs from prior work:** the hybrid histogram decides once per function whether its histogram can be trusted. The confidence gate decides for every single prediction, based on how reliable the model has been for that function recently.

## Results

Held-out evaluation: every policy replays all 7 days, but only days 6 and 7 are scored. LSTM settings were chosen using days 1 to 5 only.

| Policy | Cold-start rate | Idle warm-minutes |
|---|---|---|
| fixed-10min | 4.076% | 1,382,365 |
| fixed-60min | 0.519% | 1,941,225 |
| hist-max240-fb60 (Shahrad et al.) | 0.529% | 1,629,906 |
| **gate-hist240 (this project)** | **0.507%** | **1,542,194** |
| lstm-always-hist240 (gate always open) | 0.451% | 1,574,356 |

Compared with the hybrid histogram, the gated policy has 4.3% fewer cold starts and 5.4% fewer idle warm-minutes. Compared with a 60-minute fixed timeout, it has slightly fewer cold starts and about 21% less idle time.

Using the LSTM for every prediction gives the fewest cold starts but costs more idle time than the gate, so the gate is a trade-off rather than a strict improvement over always trusting the model. The LSTM's validation error on day 5 was 1.06 minutes (mean absolute error), against 2.13 minutes for simply repeating the last gap.

Full tables, including per-band results, are in `results/final_days6-7.md` and `results/summary_days6-7.csv`.

## Architecture

```
Azure Functions Trace 2019 (.feather)
        |
        v
ml/prepare_subset.py  -->  invocations_subset.csv  -->  MySQL (invocations)
                                                              |
ml/train_lstm.py      -->  lstm_predictions.csv    -->  MySQL (predictions)
                                                              |
                                                              v
                                Spring Boot backend: SimulationService + policies
                                                              |
                                                       REST API (/api/simulate/*)
                                                              |
                                                              v
                                               React dashboard (Vite + Recharts)
```

The LSTM is trained offline in Python. Its predictions are written to a CSV and loaded into MySQL, so the Java simulator never calls Python at run time.

## Repository layout

```
serverless-scheduler/
  backend/                 Spring Boot application (package com.scheduler.backend)
    src/main/java/...      DataLoader, PredictionLoader, SimulationService,
                           SimulationController, CorsConfig and the policy classes
    src/test/java/...      JUnit 5 tests
  ml/
    prepare_subset.py      Samples 2,000 functions and reshapes the trace to long format
    train_lstm.py          Trains the pooled LSTM and writes lstm_predictions.csv
  frontend/                React dashboard
  tests/selenium/          Selenium IDE test suite (.side file)
  results/                 Result tables in Markdown and CSV
  docker-compose.yml       MySQL 8 container
  .env                     Database credentials (gitignored, you create it)
```

Policy classes: `KeepAlivePolicy` (interface), `FixedKeepAlivePolicy`, `EwmaKeepAlivePolicy`, `PercentileKeepAlivePolicy`, `FloorKeepAlivePolicy`, `HybridHistogramPolicy`, `LstmGatedPolicy`.

The raw trace, the processed CSV files and `.env` are not in the repository because of their size and because they contain credentials.

## Tech stack

| Layer | Technology |
|---|---|
| Backend and simulator | Java 21, Spring Boot, Maven wrapper |
| Database | MySQL 8 in Docker (host port 3307) |
| Machine learning | Python 3, PyTorch (CPU), pandas, NumPy |
| Frontend | React 18, Vite, Recharts |
| Testing | JUnit 5, Selenium IDE |
| Project management | Jira (Scrum board organised as Spiral iterations), GitHub |

## Getting started (Windows)

### Prerequisites

- JDK 21 or newer
- Docker Desktop (must be running before the backend starts)
- Python 3.10 or newer
- Node.js 18 or newer
- About 3 GB of free disk space for the trace

### 1. Clone the repository

```powershell
git clone https://github.com/Shailja-Mishra04/serverless-scheduler.git
cd serverless-scheduler
```

### 2. Create the `.env` file

Create a file called `.env` in the repository root with the MySQL database name, user and password that `docker-compose.yml` and the backend expect. Never commit this file; it is already listed in `.gitignore`.

### 3. Start MySQL

```powershell
docker compose up -d
docker ps
```

MySQL listens on host port 3307, so it does not clash with a local MySQL on 3306.

### 4. Prepare the data

Download `invocations_per_function_md.feather` (Azure Functions Trace 2019, Kaggle mirror, about 681 MB) and place it in `ml/data/raw/`.

```powershell
cd ml
python -m venv venv
.\venv\Scripts\Activate.ps1
pip install pandas numpy pyarrow
pip install torch --index-url https://download.pytorch.org/whl/cpu
python prepare_subset.py
python train_lstm.py
```

If PowerShell refuses to run `Activate.ps1`, run `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` once and try again.

Copy `invocations_subset.csv` and `lstm_predictions.csv` into `backend/src/main/resources/data/`.

### 5. Run the backend

```powershell
cd ..\backend
.\mvnw spring-boot:run
```

On the first start, `DataLoader` and `PredictionLoader` bulk-load the CSV files into MySQL. Let this finish. If it is stopped partway, empty the tables with `TRUNCATE TABLE invocations;` before restarting.

The JDBC URL must end in `?rewriteBatchedStatements=true`, otherwise loading is very slow.

The first simulation request after startup takes 30 to 90 seconds because the data is loaded into memory. Later requests are fast.

### 6. Run the dashboard

```powershell
cd ..\frontend
npm install
npm run dev
```

Open http://localhost:5173. The dashboard includes the saved days 6-7 results, so the Results and Bands pages work even with the backend off. "Load live from backend" fetches fresh numbers.

## API reference

Base URL: `http://localhost:8080`

| Method and path | Description |
|---|---|
| `GET /api/simulate/fixed` | Runs a fixed keep-alive policy |
| `GET /api/simulate/fixed-sweep` | Runs fixed timeouts across a range of values |
| `GET /api/simulate/ewma` | Runs an EWMA policy |
| `GET /api/simulate/ewma-sweep` | Runs a set of EWMA settings |
| `GET /api/simulate/percentile` | Runs a percentile-of-gaps policy |
| `GET /api/simulate/bands` | Results per call-count band |
| `GET /api/simulate/summary` | Runs all 46 policy settings and returns one row per policy |

Each result contains the cold-start rate, the idle warm-minutes, per-band statistics and the per-function mean cold-start rate.

## Testing

### Unit tests (JUnit 5)

19 tests cover the critical modules: the simulator's warm-window accounting, the baseline policies, the hybrid histogram and the confidence gate. They use an in-memory fake `JdbcTemplate`, so they do not need MySQL or Docker.

```powershell
cd backend
.\mvnw test "-Dtest=SimulationServiceTest,HybridHistogramPolicyTest,LstmGatedPolicyTest,BaselinePoliciesTest"
```

The quotes around `-Dtest=...` are needed in PowerShell. The default `BackendApplicationTests` starts the whole application and needs MySQL running.

### UI tests (Selenium IDE)

`tests/selenium/keepalive-dashboard.side` contains 5 tests in two suites:

- **Dashboard (no backend needed):** results page and key numbers, table sorting, page navigation, form validation messages.
- **Dashboard + backend:** submits a fixed 10-minute policy and checks the result against the expected values.

To run them, start the dashboard, open the Selenium IDE browser extension, choose "Open an existing project", select the `.side` file and run a suite.

## Simulation assumptions

- Time is measured in whole minutes. An event is one function active in one minute; several calls in the same minute count once.
- Concurrency is ignored: each function has at most one container.
- There are no memory limits on how many containers can be warm.
- A call is a cold start if it arrives when the function's container is not warm.
- A policy may unload a container and pre-warm it later. The warm window after a call at minute t is [t + pre, t + pre + keep].
- An unload shorter than 5 minutes is treated as staying warm and is charged as idle time, for every policy.

## Limitations

- Functions called fewer than 10 times in the week (the 1-9 band) have cold-start rates above 95% under every policy, because there is too little history to predict them.
- The data is a sample of 2,000 functions from one week of one provider's trace.
- Cold-start latency and real money cost are not modelled; idle warm-minutes are a proxy for cost.
- The comparison with the hybrid histogram uses our own re-implementation, not the original Azure code.

## Project management

The project followed the Spiral model. Work was tracked in Jira as four sprints, one per spiral loop, each with a risk-analysis ticket:

| Spiral | Focus |
|---|---|
| 1 | Requirements (SRS), data preparation, MySQL setup |
| 2 | Simulator and baseline policies |
| 3 | Random sample, adaptive policies, hybrid histogram |
| 4 | LSTM, confidence gate, held-out evaluation, dashboard, testing, report |

## References

1. M. Shahrad et al., "Serverless in the Wild: Characterizing and Optimizing the Serverless Workload at a Large Cloud Provider," USENIX ATC 2020.
2. Azure Functions Trace 2019, Microsoft Azure Public Dataset.

## Author

- Shailja Mishra 

