// Half-open completion windows; warmup straddles are deliberately excluded.
export function classifyCompletion(startMs, endMs, warmupMs, measurementMs, bucketMs) {
  if (![startMs,endMs,warmupMs,measurementMs,bucketMs].every(Number.isFinite) ||
      startMs < 0 || endMs < startMs || warmupMs < 0 || measurementMs <= 0 ||
      bucketMs <= 0 || measurementMs % bucketMs !== 0) {
    throw new RangeError('invalid measurement clock or window');
  }
  const stopMs = warmupMs + measurementMs;
  if (startMs < warmupMs) return {phase:'warmup'};
  if (startMs >= stopMs) return {phase:'outside'};
  if (endMs >= stopMs) return {phase:'late'};
  return {phase:'window',bucket:Math.floor((endMs-warmupMs)/bucketMs)};
}
