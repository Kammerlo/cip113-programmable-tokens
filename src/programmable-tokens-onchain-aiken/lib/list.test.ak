use list.{has_or_fail}

// Test has_signatory
test has_or_fail_present() {
  let vkh = #"aabbccdd"
  has_or_fail([vkh], vkh)
}

// Test has_signatory absent
test has_or_fail_absent() fail {
  let vkh1 = #"aabbccdd"
  let vkh2 = #"ddeeff00"
  has_or_fail([vkh1], vkh2)
}
