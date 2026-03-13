use aiken/collection/dict
use cardano/assets.{AssetName}
use tokens

const fixture_tokens_contains_asset_name: AssetName = #"746f6b656e"

const fixture_tokens_contains_superset: Pairs<AssetName, Int> =
  dict.empty
    |> dict.insert(fixture_tokens_contains_asset_name, 100)
    |> dict.to_pairs

const fixture_tokens_contains_superset_with_ada: Pairs<AssetName, Int> =
  dict.empty
    |> dict.insert(fixture_tokens_contains_asset_name, 100)
    |> dict.to_pairs

const fixture_tokens_contains_subset: Pairs<AssetName, Int> =
  dict.empty
    |> dict.insert(fixture_tokens_contains_asset_name, 50)
    |> dict.to_pairs

// Test 'tokens_contains' with exact match
test tokens_contains_exact_match() {
  tokens.contains(
    fixture_tokens_contains_superset,
    fixture_tokens_contains_superset,
  )
}

// Test 'tokens_contains' with subset
test tokens_contains_subset() {
  and {
    tokens.contains(
      fixture_tokens_contains_superset,
      fixture_tokens_contains_subset,
    )?,
    tokens.contains(
      fixture_tokens_contains_superset_with_ada,
      fixture_tokens_contains_subset,
    )?,
  }
}

// Test 'tokens_contains' with insufficient funds
test tokens_contains_insufficient() {
  !tokens.contains(
    fixture_tokens_contains_subset,
    fixture_tokens_contains_superset,
  )
}
