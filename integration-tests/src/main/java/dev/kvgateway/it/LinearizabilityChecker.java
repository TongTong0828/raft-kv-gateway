package dev.kvgateway.it;

import java.util.*;

/** Bounded single-key CAS register checker. Unknown writes may commit or be omitted. */
public final class LinearizabilityChecker {
  public record Op(
      long start,
      long end,
      String type,
      String value,
      long expected,
      String result,
      long revision) {}

  private record State(String value, long revision) {}

  public static boolean check(List<Op> history) {
    if (history.size() > 24) throw new IllegalArgumentException("checker limit is 24 operations");
    for (var op : history)
      if (op.end() < op.start()) throw new IllegalArgumentException("negative interval");
    return search(history, 0, new State(null, 0), new HashSet<>());
  }

  private static boolean search(List<Op> ops, long done, State state, Set<String> failed) {
    if (done == (1L << ops.size()) - 1) return true;
    String memo = done + ":" + state.revision + ":" + state.value;
    if (!failed.add(memo)) return false;
    for (int i = 0; i < ops.size(); i++) {
      if ((done & (1L << i)) != 0) continue;
      var op = ops.get(i);
      boolean blocked = false;
      for (int j = 0; j < ops.size(); j++)
        if (i != j
            && (done & (1L << j)) == 0
            && !ops.get(j).result().equals("UNKNOWN")
            && ops.get(j).end() < op.start()) {
          blocked = true;
          break;
        }
      if (blocked) continue;
      long next = done | (1L << i);
      boolean expected = op.expected() == (state.value == null ? 0 : state.revision);
      if (op.type().equals("read")) {
        if ((op.result().equals("NOT_FOUND") && state.value == null)
            || (op.result().equals("OK")
                && Objects.equals(op.value(), state.value)
                && op.revision() == state.revision))
          if (search(ops, next, state, failed)) return true;
      } else if (op.result().equals("CONFLICT")) {
        if (!expected && search(ops, next, state, failed)) return true;
      } else if (op.result().equals("OK")) {
        if (expected
            && op.revision() > state.revision
            && search(ops, next, new State(op.value(), op.revision()), failed)) return true;
      } else if (op.result().equals("UNKNOWN")) {
        if (search(ops, next, state, failed)) return true;
        if (expected) {
          var candidates = new TreeSet<Long>();
          if (state.revision < Long.MAX_VALUE) candidates.add(state.revision + 1);
          for (var candidate : ops) {
            candidates.add(candidate.revision());
            candidates.add(candidate.expected());
          }
          for (long candidate : candidates)
            if (candidate > state.revision
                && search(ops, next, new State(op.value(), candidate), failed)) return true;
        }
      } else throw new IllegalArgumentException("unsupported outcome " + op.result());
    }
    return false;
  }
}
