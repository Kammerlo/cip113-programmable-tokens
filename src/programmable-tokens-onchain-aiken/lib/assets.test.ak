use aiken/collection/dict
use assets
use cardano/address
use cardano/assets as value
use cardano/transaction.{NoDatum, Output}

const output_placeholder =
  Output {
    address: address.from_verification_key(
      #"00000000000000000000000000000000000000000000000000000000",
    ),
    value: value.zero,
    datum: NoDatum,
    reference_script: None,
  }

test collect_many_assets() {
  let out1 =
    Output {
      ..output_placeholder,
      value: value.zero
        |> value.add("", "", 42)
        |> value.add("foo", "bar", 1),
    }

  let out2 =
    Output {
      ..output_placeholder,
      value: value.zero
        |> value.add("", "", 1337)
        |> value.add("foo", "bar", 1)
        |> value.add("baz", "", 1),
    }

  let out3 =
    Output {
      ..output_placeholder,
      value: value.zero
        |> value.add("", "", 14)
        |> value.add("bar", "foo", 1),
    }

  let expected =
    value.zero
      |> value.add("bar", "foo", 1)
      |> value.add("baz", "", 1)
      |> value.add("foo", "bar", 2)
      |> value.to_dict
      |> dict.to_pairs

  assets.collect([out1, out2, out3], [], fn(o, keep, _discard) { keep(o) }) == expected
}

test collect_mixed_mint_and_output() {
  let mint =
    value.zero
      |> value.add("foo", "bar", 1)
      |> value.to_dict
      |> dict.to_pairs

  let output_value =
    Output {
      ..output_placeholder,
      value: value.zero
        |> value.add("", "", 1337)
        |> value.add("foo", "bar", 1),
    }

  let expected =
    value.zero
      |> value.add("foo", "bar", 2)
      |> value.to_dict
      |> dict.to_pairs

  assets.collect([output_value], mint, fn(o, keep, _discard) { keep(o) }) == expected
}
