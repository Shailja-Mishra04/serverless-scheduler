"""Train one small pooled LSTM on gaps between calls and predict the next gap.

Days 1-4 train, day 5 validates (early stopping), and predictions are written for
every call from day 5 on (day 5 warms up the confidence gate, days 6-7 are scored).
Run from the ml/ folder.
"""
import math
import os
import time
from pathlib import Path

import numpy as np
import pandas as pd
import torch
from torch import nn

SEQ = 20                 # gaps of history the model sees
CAP = 1440               # gaps longer than a day are clipped to a day
NORM = math.log1p(CAP)
DAY = 1440
TRAIN_SAMPLES = 500_000
VAL_SAMPLES = 100_000
BATCH = 1024
EPOCHS = 10
PATIENCE = 2
SEED = 42

DATA = Path("data/processed/invocations_subset.csv")
OUT = Path("data/processed/lstm_predictions.csv")
MODEL = Path("models/lstm.pt")

torch.manual_seed(SEED)
rng = np.random.default_rng(SEED)
torch.set_num_threads(os.cpu_count() or 4)
start_time = time.time()

# 1. Calls -> per-function gaps (one row per active minute)
df = pd.read_csv(DATA, usecols=["function_id", "day", "minute_of_day"])
df["t"] = (df["day"] - 1) * DAY + (df["minute_of_day"] - 1)
df = df.drop_duplicates(["function_id", "t"]).sort_values(["function_id", "t"])
fid = df["function_id"].to_numpy()
t = df["t"].to_numpy(np.int64)

same = fid[1:] == fid[:-1]
gf = fid[:-1][same]                  # function of each gap
gs = t[:-1][same]                    # call time when the gap starts (prediction time)
ge = t[1:][same]                     # next call time (gap end)
gap = np.minimum(ge - gs, CAP)

# 2. Per-gap features: scaled log gap, time of day at the gap's end
angle = 2 * np.pi * (ge % DAY) / DAY
feats = np.stack([np.log1p(gap) / NORM, np.sin(angle), np.cos(angle)], axis=1).astype(np.float32)
target = feats[:, 0]

# A position k is usable when the SEQ gaps before it belong to the same function
k = np.arange(SEQ, len(gap))
k = k[gf[k - SEQ] == gf[k]]

train_idx = k[ge[k] < 4 * DAY]
val_idx = k[(gs[k] >= 4 * DAY) & (ge[k] < 5 * DAY)]
out_idx = k[gs[k] >= 4 * DAY]
if len(train_idx) > TRAIN_SAMPLES:
    train_idx = rng.choice(train_idx, TRAIN_SAMPLES, replace=False)
if len(val_idx) > VAL_SAMPLES:
    val_idx = rng.choice(val_idx, VAL_SAMPLES, replace=False)
print(f"gaps={len(gap):,} train={len(train_idx):,} val={len(val_idx):,} to_predict={len(out_idx):,}")

OFFSETS = np.arange(-SEQ, 0)


def windows(idx):
    return torch.from_numpy(feats[idx[:, None] + OFFSETS])


class GapLSTM(nn.Module):
    def __init__(self):
        super().__init__()
        self.lstm = nn.LSTM(input_size=3, hidden_size=32, batch_first=True)
        self.head = nn.Linear(32, 1)

    def forward(self, x):
        out, _ = self.lstm(x)
        return self.head(out[:, -1]).squeeze(-1)


def predict(model, idx, chunk=16384):
    model.eval()
    parts = []
    with torch.no_grad():
        for s in range(0, len(idx), chunk):
            parts.append(model(windows(idx[s:s + chunk])).numpy())
    return np.concatenate(parts) if parts else np.empty(0, np.float32)


def to_minutes(scaled):
    return np.clip(np.expm1(scaled * NORM), 1, CAP)


# 3. Train with L1 loss (predicts a typical gap, robust to outliers), stop early on day 5
model = GapLSTM()
opt = torch.optim.Adam(model.parameters(), lr=1e-3)
loss_fn = nn.L1Loss()
true_val = to_minutes(target[val_idx])
best_mae, best_state, bad_epochs = float("inf"), None, 0

for epoch in range(1, EPOCHS + 1):
    model.train()
    order = rng.permutation(train_idx)
    for s in range(0, len(order), BATCH):
        b = order[s:s + BATCH]
        opt.zero_grad()
        loss = loss_fn(model(windows(b)), torch.from_numpy(target[b]))
        loss.backward()
        opt.step()

    mae = np.mean(np.abs(to_minutes(predict(model, val_idx)) - true_val))
    print(f"epoch {epoch}: day-5 MAE = {mae:.2f} min  ({time.time() - start_time:.0f}s)")
    if mae < best_mae - 0.01:
        best_mae, bad_epochs = mae, 0
        best_state = {n: p.clone() for n, p in model.state_dict().items()}
    else:
        bad_epochs += 1
        if bad_epochs >= PATIENCE:
            break

model.load_state_dict(best_state)
naive_mae = np.mean(np.abs(to_minutes(target[val_idx - 1]) - true_val))
print(f"best day-5 MAE: LSTM {best_mae:.2f} min vs 'repeat last gap' {naive_mae:.2f} min")

MODEL.parent.mkdir(parents=True, exist_ok=True)
torch.save(model.state_dict(), MODEL)

# 4. Predict the next gap at every call from day 5 on
pred = to_minutes(predict(model, out_idx))
pd.DataFrame({"function_id": gf[out_idx], "t": gs[out_idx], "pred_gap": np.round(pred, 2)}) \
  .to_csv(OUT, index=False)
print(f"wrote {len(out_idx):,} predictions to {OUT} in {time.time() - start_time:.0f}s")