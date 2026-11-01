package com.devoid.keysync.domain.touch

/** Bounded anonymous timings/state; no key history or game content. Retains the last stopped session. */
object TouchDebugRegistry {
    private var active: TouchPipeline? = null
    private var stopped: TouchDiagnostics? = null
    @Synchronized fun attach(pipeline: TouchPipeline) { active = pipeline }
    @Synchronized fun detach(pipeline: TouchPipeline) {
        if (active === pipeline) { stopped = pipeline.diagnostics(); active = null }
    }
    @Synchronized fun snapshot(): TouchDiagnostics? = active?.diagnostics() ?: stopped
}
