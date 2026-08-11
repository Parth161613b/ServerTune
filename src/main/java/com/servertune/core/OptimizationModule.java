package com.servertune.core;

public interface OptimizationModule {

    String getName();

    ModuleState getState();

    void enable();

    void disable();

    void suspend();

    void resume();

    void optimize();
}
