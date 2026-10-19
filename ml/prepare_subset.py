import numpy as np
import pandas as pd

NUM_FUNCTIONS = 2000
SEED = 42          # fixed seed -> reproducible sample
DAYS = 7
CHUNK = 50_000
KEYS = ["HashOwner", "HashApp", "HashFunction"]

df = pd.read_feather("data/raw/invocations_per_function_md.feather")
assert df.columns[4] == "1" and df.columns[1443] == "1440" and df.columns[1444] == "day"

# Calls per function-day row, computed in chunks to limit memory
totals = np.empty(len(df), dtype=np.int64)
for s in range(0, len(df), CHUNK):
    totals[s:s + CHUNK] = df.iloc[s:s + CHUNK, 4:1444].to_numpy().sum(axis=1, dtype=np.int64)

# Functions with at least one call in days 1..DAYS
mask = (totals > 0) & (df["day"].to_numpy() <= DAYS)
active_keys = df.loc[mask, KEYS].drop_duplicates()
print("Active functions in days 1-%d: %d" % (DAYS, len(active_keys)))

sampled = active_keys.sample(NUM_FUNCTIONS, random_state=SEED)

# Keep only sampled functions, days 1..DAYS
sel = pd.MultiIndex.from_frame(df[KEYS]).isin(pd.MultiIndex.from_frame(sampled[KEYS]))
sub = df[sel & (df["day"].to_numpy() <= DAYS)].reset_index(drop=True)

# Wide -> long using numpy (only non-zero minutes)
counts = sub.iloc[:, 4:1444].to_numpy()
rows, cols = np.nonzero(counts)

key_str = sub["HashOwner"] + "_" + sub["HashApp"] + "_" + sub["HashFunction"]
codes, uniques = pd.factorize(key_str)

final_df = pd.DataFrame({
    "function_id": codes[rows] + 1,
    "day": sub["day"].to_numpy()[rows].astype(int),
    "minute_of_day": cols + 1,
    "invocation_count": counts[rows, cols].astype(np.int64),
})

pd.DataFrame({"function_hash": uniques,
              "function_id": np.arange(1, len(uniques) + 1)}) \
  .to_csv("data/processed/functions_lookup.csv", index=False)

print("Distinct functions:", final_df["function_id"].nunique())
print("Final shape:", final_df.shape)
print(final_df.head(10))
final_df.to_csv("data/processed/invocations_subset.csv", index=False)
print("Saved to data/processed/invocations_subset.csv")