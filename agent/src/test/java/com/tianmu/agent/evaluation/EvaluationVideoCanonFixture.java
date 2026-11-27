package com.tianmu.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.access.IdentityPort;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.AgentException;
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
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Batch-4 wiring: the REAL product entry over the frozen Canon material, with three paid exits all
 * pointed at the local double. Only supplier responses are scripted; identity, snapshot, gate,
 * ledger, clients and parsers are the product's own classes.
 */
@TestConfiguration
public class EvaluationVideoCanonFixture {
    static final Instant TASK_NOW = EvaluationVideoCanonEntry.TASK_NOW;
    static final Clock TASK_CLOCK = Clock.fixed(TASK_NOW, ZoneId.of("Asia/Shanghai"));
    static final String KEY = "synthetic-loopback-only-not-a-credential";
    private static final String PROTOCOL = "siliconflow";
    /** One shared compensation policy for controller, analysis and vision. */
    static final EvaluationRecovery RECOVERY = new EvaluationRecovery(EvaluationVideoCanonEvidence.ROOT);

    @Bean
    @Primary
    EvaluationRecovery evaluationRecovery() {
        return RECOVERY;
    }

    @Bean
    @Primary
    @org.springframework.context.annotation.Lazy
    FeedbackSnapshotPort formalCanonSnapshot() throws IOException {
        Path config = Path.of(System.getProperty("eval.material.config",
                "eval/report-trial-admission.example.json"));
        return new EvaluationFormalSnapshotPort(
                config,
                EvaluationFormalSnapshotPort.FreezeMode.RECORD_VERIFY, TASK_CLOCK,
                EvaluationVideoCanonEvidence.ROOT.resolve("freeze"),
                EvaluationVideoCanonEvidence.ROOT.resolve("snapshot-audit.jsonl"));
    }

    @Bean
    @Primary
    IdentityPort evaluationIdentity() {
        return credential -> {
            if (EvaluationVideoCanonEntry.CREDENTIAL.equals(credential))
                return new AccessScope("demo", "alice", Set.of(EvaluationVideoCanonEvidence.CANON_VIDEO));
            throw new AgentException("UNAUTHORIZED", "Use Bearer demo-alice in the Canon evaluation wiring");
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

    @Bean
    @Primary
    com.tianmu.agent.video.VideoModelClient evaluationVision(
            com.tianmu.agent.video.VideoModelProperties config, ObjectMapper mapper,
            com.tianmu.agent.video.VideoRuntimeProperties runtime) {
        var transport = com.tianmu.agent.evaluation.admission.ProviderHttpTransport.using(
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(runtime.modelConnectSeconds()))
                        .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build());
        return new com.tianmu.agent.video.VideoModelClient(config, mapper, runtime, transport, RECOVERY);
    }

    /** Frozen frames only: source identity is the manifest's video digest, and the media is a
     *  deterministic derivative of the copied JPEGs (see frames-audit.json). */
    @Bean
    @Primary
    VideoContentPort frozenCanonFrames() {        return new VideoContentPort() {
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

    /** Two REAL harness clients; the active controller routes the call. */
    @Bean
    @Primary
    ModelPort evaluationHarness(ObjectMapper mapper, PromptCatalog prompts) {
        var harnessA = harness(mapper, prompts, EvaluationVideoCanonEntry.MODEL_A);
        var harnessB = harness(mapper, prompts, EvaluationVideoCanonEntry.MODEL_B);
        return new ModelPort() {
            private DeepSeekHarnessModel active() {
                return EvaluationVideoCanonEntry.MODEL_B.equals(EvaluationVideoCanonEntry.ACTIVE_CONTROLLER.get())
                        ? harnessB : harnessA;
            }

            public boolean streamsText() {
                return active().streamsText();
            }

            public String modelName() {
                return active().modelName();
            }

            public com.tianmu.agent.core.AgentTypes.Decision decide(com.tianmu.agent.core.AgentTypes.ModelContext context) {
                return active().decide(context, new CallControl(TASK_NOW.plusSeconds(60), new CancellationToken()));
            }

            public com.tianmu.agent.core.AgentTypes.Decision decide(
                    com.tianmu.agent.core.AgentTypes.ModelContext context, CallControl control) {
                return active().decide(context, control);
            }
        };
    }

    /** The analysis role is pinned for BOTH members: only the controller may change between A/B. */
    @Bean
    @Primary
    FeedbackModels fixedAnalysisModel(ObjectMapper mapper, PromptCatalog prompts) {
        var properties = new DeepSeekProperties(URI.create(EvaluationVideoCanonLoopback.BASE_URL), KEY,
                EvaluationVideoCanonEntry.MODEL_A, EvaluationVideoCanonEntry.MODEL_A, Duration.ofSeconds(10),
                384, 3072, EvaluationVideoCanonEntry.ANALYSIS_MODEL, true, "high", "low", 16384, PROTOCOL);
        return new DeepSeekFeedbackModels(new DeepSeekClient(properties, mapper,
                com.tianmu.agent.model.ContextPolicy.defaults(), null, null, RECOVERY), properties, mapper, prompts);
    }

    @Bean
    EvaluationVideoCanonEntry evaluationVideoCanonEntry(RunService runs, FeedbackAnalysisService analysis, MockMvc mvc) {
        return new EvaluationVideoCanonEntry(runs, analysis, mvc);
    }

    private static DeepSeekHarnessModel harness(ObjectMapper mapper, PromptCatalog prompts, String model) {
        var properties = new DeepSeekProperties(URI.create(EvaluationVideoCanonLoopback.BASE_URL), KEY,
                EvaluationVideoCanonEntry.MODEL_A, model, Duration.ofSeconds(10), 384, 3072,
                EvaluationVideoCanonEntry.ANALYSIS_MODEL, true, "high", "low", 16384, PROTOCOL);
        return new DeepSeekHarnessModel(new DeepSeekClient(properties, mapper,
                com.tianmu.agent.model.ContextPolicy.defaults(), null, null, RECOVERY),
                properties, mapper, prompts);
    }
}
