package com.tanuki276.fpssc;

/** Relative queue growth only. Image timestamps have no guaranteed elapsedRealtime epoch. */
final class FrameTiming {
    private long previousSourceNs;
    private long previousReceiptMs;
    private long extraNs;
    long observe(long sourceNs, long receiptMs) {
        if (sourceNs <= 0) return -1;
        if (previousSourceNs == 0) {
            previousSourceNs = sourceNs;
            previousReceiptMs = receiptMs;
            return 0;
        }
        if (sourceNs <= previousSourceNs || receiptMs < previousReceiptMs) return -1;
        long sourceDelta = sourceNs - previousSourceNs;
        long receiptDelta = receiptMs - previousReceiptMs;
        previousSourceNs = sourceNs;
        previousReceiptMs = receiptMs;
        extraNs = Math.max(0, extraNs + receiptDelta * 1_000_000L - sourceDelta);
        // Bound arithmetic and retain a stale result rather than pretending a stall is fresh.
        extraNs = Math.min(extraNs, 10_000_000_000L);
        return extraNs / 1_000_000L;
    }
}
