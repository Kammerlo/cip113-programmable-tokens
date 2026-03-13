use aiken/builtin.{head_list, tail_list}

pub fn head(self: List<a>) -> a {
  head_list(self)
}

pub fn tail(self: List<a>) -> List<a> {
  tail_list(self)
}

/// Check quickly if an element is an list; fails loudly otherwise.
pub fn has_or_fail(xs: List<a>, x: a) -> Bool {
  head(xs) == x || has_or_fail(tail(xs), x)
}

/// Get an element at index from a list, or fails if the element is missing.
pub fn get_or_fail(xs: List<a>, i: Int) -> a {
  if i >= 2 {
    // unroll recursion twice as fast for large indexes
    get_or_fail(tail(tail(xs)), i - 2)
  } else if i >= 1 {
    get_or_fail(tail(xs), i - 1)
  } else {
    head(xs)
  }
}

/// Drop n elements from a list, fast.
pub fn drop_or_fail(self: List<a>, n: Int) -> List<a> {
  if n >= 2 {
    // Unroll recursion twice as fast for large n
    drop_or_fail(tail(tail(self)), n - 2)
  } else if n >= 1 {
    drop_or_fail(tail(self), n - 1)
  } else {
    self
  }
}

/// Drop n element from a list while enforcing that any dropped element satisfies
/// a given predicate.
pub fn skip_assert_or_fail(
  self: List<a>,
  n: Int,
  assert: fn(a) -> Bool,
) -> List<a> {
  if n > 0 {
    expect assert(head(self))
    skip_assert_or_fail(tail(self), n - 1, assert)
  } else {
    self
  }
}
