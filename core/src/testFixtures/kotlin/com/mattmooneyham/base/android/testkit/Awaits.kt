package com.mattmooneyham.base.android.testkit

/**
 * Polls [condition] in real time (the suite's dispatchers are real
 * threads) and fails with [description] after [timeoutMillis]. For
 * conditions with no completion signal to await, such as garbage
 * collection or a background sweep landing.
 */
fun awaitTrue(
    description: String,
    timeoutMillis: Long = 10_000,
    condition: () -> Boolean,
) {
    val deadlineMillis = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadlineMillis) {
        if (condition()) return
        Thread.sleep(25)
    }
    throw AssertionError("Timed out waiting until: $description")
}
