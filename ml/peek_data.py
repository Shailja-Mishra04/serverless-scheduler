import pandas as pd

df = pd.read_feather("data/raw/invocations_per_function_md.feather")
print("Shape (rows, columns):", df.shape)
print("\nColumn names:")
print(df.columns.tolist())
print("\nFirst 5 rows:")
print(df.head())