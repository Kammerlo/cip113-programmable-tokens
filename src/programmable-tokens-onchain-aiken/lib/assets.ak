use aiken/builtin.{less_than_bytearray}
use aiken/collection/dict
use aiken/collection/dict/strategy as dict_strategy
use cardano/assets.{PolicyId, Value}
use cardano/transaction.{Output}
use env
use list
use tokens.{Tokens}

pub type Assets =
  Pairs<PolicyId, Tokens>

pub type SumStrategy<i> =
  fn(i, fn(Output) -> Assets, fn() -> Assets) -> Assets

pub fn from_value(self: Value) -> Assets {
  self |> assets.to_dict |> dict.to_pairs
}

/// Split an dictionnary at the given key. Returning the elements before (in reverse key order), the
/// value at the key, and the elements after (in same key order).
pub fn split_at(
  self: Value,
  at: PolicyId,
  return: fn(Pairs<PolicyId, Tokens>, Tokens, Pairs<PolicyId, Tokens>) -> result,
) -> result {
  do_split_at(from_value(self), at, [], return)
}

fn do_split_at(
  self: Assets,
  at: PolicyId,
  before: Assets,
  return: fn(Assets, Tokens, Assets) -> result,
) -> result {
  when self is {
    [] -> return(before, dict.empty, [])
    [head, ..tail] -> {
      let k = head.1st
      if less_than_bytearray(k, at) {
        // Skip while keys are smaller (strictly) than searched key
        do_split_at(tail, at, [head, ..before], return)
      } else if k == at {
        // Done searching, get the value and return the tail after
        return(before, head.2nd, tail)
      } else {
        // The head and tail are all after the key; no need to continue searching.
        return(before, dict.empty, self)
      }
    }
  }
}

pub fn peek_first(self: Value) -> PolicyId {
  list.head(list.tail(from_value(self))).1st
}

/// A faster version of assets.merge that preserves empty maps. This allows to
/// bypass the null check on value since quantities can only ever increase.
/// Importantly, it is also *necessary* to ensure that policies are retained in
/// the map for later validations even if they result in no outputs (e.g. a burn
/// fully compensate a spend).
///
/// It also focuses on assets and completely ignores Ada.
///
/// The function recurses over a list or input-like objects, and let the
/// caller select whether to add an output or not.
///
/// ## Example
///
/// ```aiken
/// collect(
///   self.inputs,
///   fn(input, select, discard) {
///     let output = input.output
///     if output.address.payment_credential == needle {
///       select(output)
///     } else {
///       discard()
///     }
///   },
/// )
/// ```
///
/// /!\ PRE-CONDITION /!\
/// The given zero value is not expected to contain any Ada. This is enforced
/// when assertions are enabled (`--env with_assertions`).
///
/// This is generally true when the initial zero is empty or coming from the
/// mint value.
pub fn collect(elems: List<i>, zero: Assets, strategy: SumStrategy<i>) -> Assets {
  do_collect(elems, strategy, env.assert_no_ada_policy(zero))
}

fn do_collect(elems: List<i>, strategy: SumStrategy<i>, sum: Assets) -> Assets {
  when elems is {
    [] -> sum
    [head, ..tail] ->
      do_collect(
        tail,
        strategy,
        strategy(
          head,
          // Output is selected
          fn(output) {
            output.value
              |> from_value
              |> list.tail
              |> union(sum, _)
          },
          // Output is discarded
          fn() { sum },
        ),
      )
  }
}

/// Merge two Assets by summing token quantities.
/// Used to combine validated input prog value with validated mint prog value.
fn union(left: Assets, right: Assets) -> Assets {
  when left is {
    [] -> right
    [Pair(k, v), ..rest] -> union(rest, do_insert(right, k, v))
  }
}

fn do_insert(self: Assets, k: PolicyId, v: Tokens) -> Assets {
  when self is {
    [] -> [Pair(k, v)]
    [Pair(k2, v2), ..rest] ->
      if less_than_bytearray(k, k2) {
        [Pair(k, v), ..self]
      } else {
        if k == k2 {
          // NOTE: Preserving null assets
          // It is primordial here to not discard assets if even they result in an
          // empty set. This is because the zero value may have negative quantities
          // coming from burns. If a burn is fully cover by a spend, we must preserve
          // the key in the map to ensure that validations necessary to that policy
          // occur as expected.
          [Pair(k, dict.union_with(v, v2, dict_strategy.sum())), ..rest]
        } else {
          [Pair(k2, v2), ..do_insert(rest, k, v)]
        }
      }
  }
}
