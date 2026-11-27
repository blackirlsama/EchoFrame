package com.tianmu.agent.runtime.spi;

import java.util.List;

/**
 * Optional observation point for tool-argument rejections. It is deliberately free of business
 * tool names and values: only fixed phases, declared field paths and {@code Problem} names travel.
 * A production context simply has no implementation, which keeps the execution kernel unchanged;
 * an evaluation wiring may install one to persist safe diagnostics.
 */
public interface ToolArgumentObserver {
    /**
     * One rejection as decided by the dispatcher, before any stop returns. {@code phase} is one of
     * SCHEMA, PREFLIGHT, HANDLER, RESULT, BATCH or BATCH_GROUP. Handler/RESULT layers have no
     * structured field, so both lists stay empty and {@code failureClass} names the fixed kind.
     */
    record Rejection(String runId, int callIndex, String tool, String phase, String code,
                     List<String> paths, List<String> problems, int issueCount,
                     boolean issuesTruncated, String failureClass, boolean handlerExecuted) {
        public Rejection {
            paths = paths == null ? List.of() : List.copyOf(paths);
            problems = problems == null ? List.of() : List.copyOf(problems);
        }
    }

    void rejected(Rejection rejection);

    /** The existing pre-handler correction granted another model turn; nothing else changed. */
    default void corrected(String runId, String tool, String phase, int correction) {}

    /** The run is stopping on a tool-argument code; {@code phase} is empty when it is unknown. */
    default void stopped(String runId, String tool, String phase, String code, int corrections) {}

    /**
     * Process-wide fallback for harnesses that assemble the kernel without Spring. Production
     * never installs one; the explicitly armed evaluation gate does, beside its own install.
     */
    static void install(ToolArgumentObserver observer) { Holder.value = observer; }

    static void clear() { Holder.value = null; }

    static ToolArgumentObserver installed() { return Holder.value; }

    final class Holder {
        private static volatile ToolArgumentObserver value;
        private Holder() {}
    }
}
