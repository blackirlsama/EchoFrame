package com.tianmu.agent.evaluation;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.runtime.control.CancellationToken;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Port-level checks for the formal evaluation material boundary (no Spring context needed). */
class EvaluationFormalSnapshotPortTest {
    static final String CONFIG = "eval/report-trial-synthetic.example.json";
    static final long SYNTHETIC_VIDEO = 730013L;
    static final Instant TASK_NOW = Instant.parse("2026-11-15T16:00:00Z");
    static final Clock TASK_CLOCK = Clock.fixed(TASK_NOW, ZoneId.of("Asia/Shanghai"));

    static Request syntheticRequest() {
        return new Request(new com.tianmu.agent.core.DanmakuTypes.Query(SYNTHETIC_VIDEO,
                LocalDateTime.parse("2026-11-03T00:00:00"), LocalDateTime.parse("2026-11-10T00:00:00")),
                Set.of(Source.COMMENT, Source.DANMAKU), Grounding.FEEDBACK_ONLY, null, null,
                "feedback-preparation-v1");
    }

    static AccessScope owner() {
        return new AccessScope("demo", "alice", Set.of(SYNTHETIC_VIDEO, 730011L, 730012L));
    }

    static CallControl control() {
        return new CallControl(Instant.now().plusSeconds(30), new CancellationToken());
    }

    @Test void syntheticWindowServesTheFixtureCounts() {
        Path store = null;
        try {
            store = Files.createTempDirectory("entry-port-store-");
            var port = new EvaluationFormalSnapshotPort(Path.of(CONFIG),
                    EvaluationFormalSnapshotPort.FreezeMode.RECORD_VERIFY, TASK_CLOCK, store, null);
            List<Unit> rows = new ArrayList<>();
            var receipt = port.export(syntheticRequest(), owner(), control(), rows::addAll);
            assertEquals(4, receipt.counts().comments(), "synthetic comments");
            assertEquals(6, receipt.counts().danmaku(), "synthetic danmaku");
            assertEquals(10, rows.size());
            assertEquals(TASK_NOW, receipt.dataAsOf(), "receipt dataAsOf comes from the task clock");
        } catch (Exception error) {
            fail("export failed: " + error, error);
        }
    }

    @Test void demoVideoIdIsNeverAFormalMaterial() {
        Path store = null;
        try {
            store = Files.createTempDirectory("entry-port-demo-");
            var port = new EvaluationFormalSnapshotPort(Path.of(CONFIG),
                    EvaluationFormalSnapshotPort.FreezeMode.RECORD_VERIFY, TASK_CLOCK, store, null);
            var demo = new Request(new com.tianmu.agent.core.DanmakuTypes.Query(1001L,
                    LocalDateTime.parse("2026-11-03T00:00:00"), LocalDateTime.parse("2026-11-10T00:00:00")),
                    Set.of(Source.COMMENT, Source.DANMAKU), Grounding.FEEDBACK_ONLY, null, null,
                    "feedback-preparation-v1");
            var refused = assertThrows(AgentException.class,
                    () -> port.export(demo, new AccessScope("demo", "alice", Set.of(1001L, SYNTHETIC_VIDEO)), control(), rows -> {}));
            assertTrue(refused.getMessage().contains("formal material binding"), refused.getMessage());
        } catch (Exception error) {
            fail("setup failed: " + error, error);
        }
    }
}
