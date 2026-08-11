package com.servertune.fallback;

public enum FallbackState {
    NORMAL,      // TPS >= healthy threshold
    WARNING,     // TPS < healthy but >= warning
    CRITICAL,    // TPS < critical threshold
    FALLBACK,    // TPS < fallback threshold - plugin in safe mode
    RECOVERING   // TPS recovering from fallback
}
