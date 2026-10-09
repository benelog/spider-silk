-- Prints one line that run.sh parses: requests/s, latency percentiles and
-- maximum in milliseconds, the error count, and the bytes per response.
done = function(summary, latency, requests)
  local errors = summary.errors.connect + summary.errors.read + summary.errors.write
      + summary.errors.status + summary.errors.timeout
  io.write(string.format("RESULT %.1f %.3f %.3f %.3f %.3f %d %d\n",
      summary.requests / (summary.duration / 1e6),
      latency:percentile(50) / 1000,
      latency:percentile(90) / 1000,
      latency:percentile(99) / 1000,
      latency.max / 1000,
      errors,
      summary.bytes / math.max(summary.requests, 1)))
end
