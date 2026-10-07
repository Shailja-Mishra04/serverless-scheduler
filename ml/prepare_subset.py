import pandas as pd

# Load the full dataset
df = pd.read_feather("data/raw/invocations_per_function_md.feather")



# Step 1: pick a manageable subset of functions (per our SRS TBD-2 decision)
NUM_FUNCTIONS = 2000
subset_functions = df[["HashOwner", "HashApp", "HashFunction"]].drop_duplicates().head(NUM_FUNCTIONS)

df_subset = df.merge(subset_functions, on=["HashOwner", "HashApp", "HashFunction"], how="inner")
df_subset = df_subset[df_subset["day"] <= 7]
# Step 2: reshape wide -> long (melt the 1440 minute columns into rows)
minute_cols = [str(i) for i in range(1, 1441)]

long_df = df_subset.melt(
    id_vars=["HashOwner", "HashApp", "HashFunction", "Trigger", "day"],
    value_vars=minute_cols,
    var_name="minute_of_day",
    value_name="invocation_count"
)

# Step 3: build a readable function identifier, then map to a short integer ID
long_df["function_id_full"] = long_df["HashOwner"] + "_" + long_df["HashApp"] + "_" + long_df["HashFunction"]
long_df["minute_of_day"] = long_df["minute_of_day"].astype(int)

# Create a lookup table: full hash -> short integer ID
unique_functions = long_df["function_id_full"].unique()
function_id_map = {full_id: short_id for short_id, full_id in enumerate(unique_functions, start=1)}

long_df["function_id"] = long_df["function_id_full"].map(function_id_map)

# Save the lookup table separately (so we don't lose the original hash mapping)
functions_lookup = pd.DataFrame(list(function_id_map.items()), columns=["function_hash", "function_id"])
functions_lookup.to_csv("data/processed/functions_lookup.csv", index=False)

# Optional but recommended: drop rows with 0 invocations to keep file size sane
long_df = long_df[long_df["invocation_count"] > 0]

# Step 4: keep only the columns our schema actually needs
final_df = long_df[["function_id", "day", "minute_of_day", "invocation_count"]]

print("Final shape:", final_df.shape)
print(final_df.head(10))

# Step 5: export clean, ready-to-ingest CSV
final_df.to_csv("data/processed/invocations_subset.csv", index=False)
print("Saved to data/processed/invocations_subset.csv")