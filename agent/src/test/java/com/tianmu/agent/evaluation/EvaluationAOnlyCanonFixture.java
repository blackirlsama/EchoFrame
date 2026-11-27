package com.tianmu.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.access.IdentityPort;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.VideoContent;
import com.tianmu.agent.feedback.FeedbackAnalysisService;
import com.tianmu.agent.feedback.FeedbackEmbedding;
import com.tianmu.agent.feedback.FeedbackModels;
import com.tianmu.agent.feedback.FeedbackSnapshotPort;
import com.tianmu.agent.feedback.FeedbackVectorIndex;
import com.tianmu.agent.model.ModelPort;
import com.tianmu.agent.model.deepseek.DeepSeekClient;
import com.tianmu.agent.model.deepseek.DeepSeekFeedbackModels;
import com.tianmu.agent.model.deepseek.DeepSeekHarnessModel;
import com.tianmu.agent.model.deepseek.DeepSeekProperties;
import com.tianmu.agent.prompt.PromptCatalog;
import com.tianmu.agent.runtime.RunService;
import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.runtime.control.CancellationToken;
import com.tianmu.agent.video.VideoContentPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Batch-6 A-only wiring. Exactly ONE harness model exists (controller A); there is no B bean and
 * no active-controller switch, so "only A" is a property of the object graph. The three paid exits
 * are pointed at the loopback double; identity, snapshot, gate, ledger, clients and parsers are
 * the product's own classes.
 */
@TestConfiguration
public class EvaluationAOnlyCanonFixture {
    static final Instant TASK_NOW = EvaluationAOnlyCanonEntry.TASK_NOW;
    static final Clock TASK_CLOCK = Clock.fixed(TASK_NOW, ZoneId.of("Asia/Shanghai"));
    static final String KEY = EvaluationAOnlyCanonEntry.KEY;
    private static final String PROTOCOL = EvaluationAOnlyCanonEntry.PROVIDER;

    @Bean
    @Primary
    @org.springframework.context.annotation.Lazy
    FeedbackSnapshotPort formalCanonSnapshot() throws IOException {
        EvaluationAOnlyCanonEntry.requireEvidenceRoot();
        return new EvaluationFormalSnapshotPort(
                java.nio.file.Path.of("eval/report-trial-admission.example.json"),
                EvaluationFormalSnapshotPort.FreezeMode.RECORD_VERIFY, TASK_CLOCK,
                EvaluationVideoCanonEvidence.ROOT.resolve("freeze"),
                EvaluationVideoCanonEvidence.ROOT.resolve("snapshot-audit.jsonl"));
    }

    @Bean
    @Primary
    IdentityPort evaluationIdentity() {
        return credential -> {
            if (EvaluationAOnlyCanonEntry.CREDENTIAL.equals(credential))
                return new AccessScope("demo", "alice", Set.of(EvaluationVideoCanonEvidence.CANON_VIDEO));
            throw new AgentException("UNAUTHORIZED", "Use Bearer demo-alice in the A-only evaluation wiring");
        };
    }

    @Bean
    FeedbackEmbedding fixtureEmbedding() {
        return new FeedbackEmbedding() {
            public String version() {
                return "fixture-text-v1";
            }

            public int dimension() {
                return 3;
            }

            public List<List<Float>> embed(List<String> input, CallControl control) {
                return input.stream().map(text -> List.of(1f, 0f, 0f)).toList();
            }
        };
    }

    @Bean
    FeedbackVectorIndex fixtureVectors() {
        var vectors = mock(FeedbackVectorIndex.class);
        when(vectors.dimension()).thenReturn(3);
        when(vectors.fetch(any(), anyList(), any())).thenReturn(Map.of());
        return vectors;
    }

    /** Frozen frames only: source identity is the manifest's video digest, and the media is a
     *  deterministic derivative of the copied JPEGs (see frames-audit.json). */
    @Bean
    @Primary
    VideoContentPort frozenCanonFrames() {
        return new VideoContentPort() {
            @Override
            public VideoContent.Source source(long videoId, AccessScope scope, CallControl control) {
                if (videoId != EvaluationVideoCanonEvidence.CANON_VIDEO)
                    throw new AgentException("FORBIDDEN", "Only the frozen Canon video is bound");
                return new VideoContent.Source(videoId, EvaluationVideoCanonEvidence.sourceVersion(),
                        EvaluationVideoCanonEvidence.durationMs());
            }

            @Override
            public VideoContent.Media media(long videoId, String version, long startMs, long endMs,
                                           AccessScope scope, CallControl control) {
                var source = source(videoId, scope, control);
                if (!source.sourceVersion().equals(version))
                    throw new AgentException("INVALID_EVIDENCE", "Video source changed");
                var frames = EvaluationVideoCanonEvidence.frames().stream()
                        .filter(frame -> frame.atMs() >= startMs && frame.atMs() < endMs)
                        .map(frame -> new VideoContent.Frame(frame.atMs(),
                                Base64.getEncoder().encodeToString(frame.jpeg())))
                        .toList();
                return new VideoContent.Media(source, startMs, endMs, frames, null);
            }
        };
    }

    /** ONE harness client: controller A. B is not wired, so there is nothing to "also run". */
    @Bean
    @Primary
    ModelPort evaluationHarness(ObjectMapper mapper, PromptCatalog prompts) {
        EvaluationAOnlyCanonEntry.requireLoopback(URI.create(EvaluationVideoCanonLoopback.BASE_URL));
        return harness(mapper, prompts);
    }

    /** The fixed analysis role shares A's request name; only the request shape separates them. */
    @Bean
    @Primary
    FeedbackModels fixedAnalysisModel(ObjectMapper mapper, PromptCatalog prompts) {
        EvaluationAOnlyCanonEntry.requireLoopback(URI.create(EvaluationVideoCanonLoopback.BASE_URL));
        var properties = properties(EvaluationAOnlyCanonEntry.MODEL_A);
        return new DeepSeekFeedbackModels(new DeepSeekClient(properties, mapper), properties, mapper, prompts);
    }

    @Bean
    EvaluationAOnlyCanonEntry evaluationAOnlyCanonEntry(RunService runs, FeedbackAnalysisService analysis,
                                                        MockMvc mvc) {
        return new EvaluationAOnlyCanonEntry(runs, analysis, mvc);
    }

    private static DeepSeekHarnessModel harness(ObjectMapper mapper, PromptCatalog prompts) {
        var properties = properties(EvaluationAOnlyCanonEntry.MODEL_A);
        return new DeepSeekHarnessModel(new DeepSeekClient(properties, mapper), properties, mapper, prompts);
    }

    private static DeepSeekProperties properties(String harnessModel) {
        return new DeepSeekProperties(URI.create(EvaluationVideoCanonLoopback.BASE_URL), KEY,
                EvaluationAOnlyCanonEntry.MODEL_A, harnessModel, Duration.ofSeconds(30), 384, 3072,
                EvaluationAOnlyCanonEntry.MODEL_A, true, "high", "low", 16384, PROTOCOL);
    }
}
