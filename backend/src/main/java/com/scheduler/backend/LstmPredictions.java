package com.scheduler.backend;

import java.util.Arrays;

/** One function's LSTM predictions: call minute -> predicted gap to the next call. */
public record LstmPredictions(long[] t, double[] gap) {
    public static final LstmPredictions EMPTY = new LstmPredictions(new long[0], new double[0]);

    /** Predicted gap for a call at this minute, or -1 if there is none. */
    public double at(long minute) {
        int i = Arrays.binarySearch(t, minute);
        return i >= 0 ? gap[i] : -1;
    }
}