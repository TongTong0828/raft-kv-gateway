# Pool snapshot test review addendum

Reviewed only the dirty `EndpointPoolTest.java` diff against `f38faa8`; no tests, builds, production code changes or additional review seats were used.

**Finding:** the fix closes the reported sampling race without weakening the drain invariant. `awaitPoolDrained` reads acquired slots, native pending acquires, borrowed leases, wrapper waiters and releases together in the test's sole event loop. It returns from that one zero snapshot, eliminating the former helper's second predicate evaluation across callbacks. Including native pending acquires also prevents cancelled wrapper promises from hiding native queue occupancy.

The test still requires all 32 wrapper futures to finish, reacquires an active real lease while the pool remains open, drains again after release, checks one retained idle physical connection, then awaits zero physical connections after asynchronous closure. Reflection is confined to this test and intentionally depends on the pinned Netty field.

**Critical / Important / Minor findings:** none in this follow-up diff.

**Behaviors set aside:** production behavior, benchmark evidence, final documentation and publication are outside this narrowly requested follow-up; the original review remains unchanged.

**Ready to merge: Yes**, subject to root's running final verification. The reported 50 repetitions are supporting evidence supplied by root, not tests independently rerun in this review.
