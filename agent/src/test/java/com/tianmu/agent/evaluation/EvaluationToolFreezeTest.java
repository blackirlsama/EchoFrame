package com.tianmu.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.DanmakuTypes;
import com.tianmu.agent.evaluation.admission.EvaluationToolFreeze;
import com.tianmu.agent.evaluation.admission.EvaluationToolFreezeStore;
import com.tianmu.agent.model.deepseek.DeepSeekClient;
import com.tianmu.agent.model.deepseek.DeepSeekProperties;
import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.runtime.control.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 4B-1 tool result freeze (Repair-01 semantics): the shared freeze-key-v2 canonicalization
 * must produce byte-identical keys across Python and Java for the whole declared number domain
 * (the acceptance fraction vector previously diverged: 1e-05 vs 1.0E-5). The store itself is
 * immutable and contained: same key + same bytes is idempotent, same key + different bytes is
 * refused with the original untouched, invalid keys and anything resolving outside the real root
 * (including a planted junction) are refused on read and write, and the loopback replay endpoint
 * serves exactly the stored bytes. Coverage level: evaluation-wiring freeze primitive; product
 * tool-runtime wiring stays a later slice (documented in the implementation plan).
 */
class EvaluationToolFreezeTest {
    static final String GOLDEN_KEY = "0b33b75368239805266cc54d401343c7748340ee7f831705705a67578ce87517";
    /** freeze-key-v2 canonical bytes for the acceptance fraction vector, computed by the Python side. */
    static final String FRACTION_CANONICAL =
            "{\"arguments\":{\"offset_seconds\":1e-05},\"material\":{\"comment_sha256\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\",\"danmaku_sha256\":\"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc\",\"video_sha256\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"},\"sampling_strategy\":\"same\",\"task_clock\":{\"reference_time\":\"2026-11-10 00:00:00\",\"timezone\":\"Asia/Shanghai\"},\"task_window\":{\"kind\":\"absolute\"},\"tool\":\"read_video\",\"tool_config_version\":\"v1\"}";
    /** The fraction key equals the Python v2 key for the same payload (acceptance R5 fixed). */
    static final String FRACTION_KEY = "06b9cc5fcc229f1c53e86958b65b9324606bba4159f293237183f8c37be5b052";
    /** Non-integral floats normalize to one scientific form on both sides. */
    static final String FLOAT_PLAIN_KEY = "31ed5647d28992004e648c163174cd36a362f31b98dad9884a06394872914037";
    /** Integral floats collapse to integer digits; -0.0 normalizes to 0 by value. */
    static final String FLOAT_INTEGRAL_KEY = "2794b0edb9b0f898f396fada8b4f6fd2cd4cb93e35941685d8ea4da7410ff383";
    static final String NEGATIVE_ZERO_KEY = "39db4c768fbb521f48206b5d48263d8365d505ea1aec8da3e894b6ad81708f72";
    static final ObjectMapper json = new ObjectMapper();

    static ObjectNode goldenSample() {
        var material = json.createObjectNode();
        material.put("video_sha256", "1e9f5d4b28ff89c863a0c0e034a6a39c230dce318dbc2fe770df5be2b20ff53a");
        material.put("comment_sha256", "2ae4338d24c3af7cdbc22474dd19f4811e8f19a2e1fb46b508d83a6c2c4af0e1");
        material.put("danmaku_sha256", "7bb28506501be822a4dc84e59b6f3d5e9f635a57bf4f633d6f70a064c59f610f");
        var arguments = json.createObjectNode();
        arguments.put("dataset_key", "ysjf-canon");
        arguments.put("limit", 50);
        var clock = json.createObjectNode();
        clock.put("timezone", "Asia/Shanghai");
        clock.put("reference_time", "2026-11-10 00:00:00");
        var window = json.createObjectNode();
        window.put("kind", "absolute");
        window.put("start", "2026-11-03 00:00:00");
        window.put("end", "2026-11-10 00:00:00");
        var sample = json.createObjectNode();
        sample.put("tool", "analyze_comments");
        sample.set("arguments", arguments);
        sample.set("material", material);
        sample.set("task_clock", clock);
        sample.set("task_window", window);
        sample.put("sampling_strategy", "frame-12-window-full");
        sample.put("tool_config_version", "freeze-key-v1");
        return sample;
    }

    static JsonNode fractionPayload() {
        var arguments = json.createObjectNode();
        arguments.put("offset_seconds", 0.00001);
        return sharedShape(arguments);
    }

    static JsonNode floatPayload(double value) {
        var arguments = json.createObjectNode();
        arguments.put("offset_seconds", value);
        return sharedShape(arguments);
    }

    static JsonNode sharedShape(JsonNode arguments) {
        var material = json.createObjectNode();
        material.put("video_sha256", "a".repeat(64));
        material.put("comment_sha256", "b".repeat(64));
        material.put("danmaku_sha256", "c".repeat(64));
        var clock = json.createObjectNode();
        clock.put("timezone", "Asia/Shanghai");
        clock.put("reference_time", "2026-11-10 00:00:00");
        var window = json.createObjectNode();
        window.put("kind", "absolute");
        var sample = json.createObjectNode();
        sample.put("tool", "read_video");
        sample.set("arguments", arguments);
        sample.set("material", material);
        sample.set("task_clock", clock);
        sample.set("task_window", window);
        sample.put("sampling_strategy", "same");
        sample.put("tool_config_version", "v1");
        return sample;
    }

    @Test void freezeKeyMatchesTheSharedGoldenVectorByteForByte() {
        // The v1 golden sample carries integers and strings only: byte-identical under v2.
        assertEquals(GOLDEN_KEY, EvaluationToolFreeze.key(goldenSample()));
    }

    @Test void fractionVectorMatchesThePythonV2CanonicalAndKey() {
        assertEquals(FRACTION_CANONICAL, EvaluationToolFreeze.canonicalJson(fractionPayload()),
                "the acceptance divergence (1.0E-5 vs 1e-05) must collapse to one canonical form");
        assertEquals(FRACTION_KEY, EvaluationToolFreeze.key(fractionPayload()));
        assertEquals(FLOAT_PLAIN_KEY, EvaluationToolFreeze.key(floatPayload(1.5)));
        assertEquals(FLOAT_INTEGRAL_KEY, EvaluationToolFreeze.key(floatPayload(100.0)));
        assertEquals(NEGATIVE_ZERO_KEY, EvaluationToolFreeze.key(floatPayload(-0.0)));
    }

    @Test void numberDomainRefusalsAreControlled() {
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                1e-320, 1e305}) {
            assertThrows(RuntimeException.class, () -> EvaluationToolFreeze.key(floatPayload(bad)),
                    () -> "value outside the v2 domain must be refused: " + bad);
        }
    }

    @Test void componentChangesProduceDifferentKeys() {
        var altered = goldenSample().deepCopy();
        altered.put("sampling_strategy", "changed");
        assertNotEquals(GOLDEN_KEY, EvaluationToolFreeze.key(altered));
        var otherTool = goldenSample().deepCopy();
        otherTool.put("tool", "analyze_danmaku");
        assertNotEquals(GOLDEN_KEY, EvaluationToolFreeze.key(otherTool));
    }

    @Test void storeIsImmutableSameKeyDifferentBytesRefusedOriginalsUntouched() throws Exception {
        Path root = Files.createTempDirectory("admission-freeze-java-");
        var store = new EvaluationToolFreezeStore(root);
        byte[] v1 = "{\"version\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] v2 = "{\"version\":2}".getBytes(StandardCharsets.UTF_8);
        store.put(GOLDEN_KEY, v1);
        var conflict = assertThrows(EvaluationToolFreezeStore.FreezeWriteRefused.class, () -> store.put(GOLDEN_KEY, v2));
        assertTrue(conflict.getMessage().contains("different bytes"));
        assertArrayEquals(v1, store.get(GOLDEN_KEY), "same key must keep returning the stored original");
        assertArrayEquals(v1, Files.readAllBytes(root.resolve(GOLDEN_KEY + ".json")));
        // same bytes again is an idempotent success
        store.put(GOLDEN_KEY, v1);
        assertArrayEquals(v1, store.get(GOLDEN_KEY));
    }

    @Test void invalidKeysAndPathEscapesAreRefusedWithSentinelUntouched() throws Exception {
        Path root = Files.createTempDirectory("admission-freeze-java-");
        Path sentinel = root.getParent().resolve("escaped.json");
        Files.writeString(sentinel, "{\"sentinel\":\"only-within-repair\"}", StandardCharsets.UTF_8);
        try {
            var store = new EvaluationToolFreezeStore(root);
            assertThrows(EvaluationToolFreezeStore.FreezeWriteRefused.class,
                    () -> store.put("../escaped", "{\"escape\":true}".getBytes(StandardCharsets.UTF_8)));
            assertThrows(EvaluationToolFreezeStore.ToolFreezeMiss.class, () -> store.get("../escaped"));
            assertThrows(EvaluationToolFreezeStore.FreezeWriteRefused.class,
                    () -> store.put("A".repeat(64), "x".getBytes(StandardCharsets.UTF_8)), "uppercase refused");
            assertThrows(EvaluationToolFreezeStore.ToolFreezeMiss.class, () -> store.get("short"));
            assertEquals("{\"sentinel\":\"only-within-repair\"}",
                    Files.readString(sentinel, StandardCharsets.UTF_8), "nothing may be written outside the root");
        } finally {
            Files.deleteIfExists(sentinel);
        }
    }

    @Test void missingOrCorruptSidecarMakesTheArtifactUnreadable() throws Exception {
        Path root = Files.createTempDirectory("admission-freeze-java-");
        var store = new EvaluationToolFreezeStore(root);
        store.put(GOLDEN_KEY, "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        Files.deleteIfExists(root.resolve(GOLDEN_KEY + ".sha256"));
        assertThrows(EvaluationToolFreezeStore.ToolFreezeMiss.class, () -> store.get(GOLDEN_KEY),
                "missing sidecar must be an explicit failure");
        Files.writeString(root.resolve(GOLDEN_KEY + ".sha256"), "0".repeat(64), StandardCharsets.US_ASCII);
        assertThrows(EvaluationToolFreezeStore.ToolFreezeMiss.class, () -> store.get(GOLDEN_KEY),
                "mismatched sidecar must be an explicit failure");
        // repairing the sidecar to the true digest restores readability (same bytes never replaced)
        var digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest("{\"a\":1}".getBytes(StandardCharsets.UTF_8)));
        Files.writeString(root.resolve(GOLDEN_KEY + ".sha256"), digest, StandardCharsets.US_ASCII);
        assertArrayEquals("{\"a\":1}".getBytes(StandardCharsets.UTF_8), store.get(GOLDEN_KEY));
    }

    @Test void loopbackReplayEndpointServesStoredBytesAndRefusesMisses() throws Exception {
        Path root = Files.createTempDirectory("admission-freeze-java-");
        var store = new EvaluationToolFreezeStore(root);
        byte[] payload = "{\"frozen\":true,\"note\":\"原样回放\"}".getBytes(StandardCharsets.UTF_8);
        store.put(GOLDEN_KEY, payload);
        var server = store.serveLoopback();
        try {
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/freeze/";
            var first = http.send(HttpRequest.newBuilder(URI.create(base + GOLDEN_KEY)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            var second = http.send(HttpRequest.newBuilder(URI.create(base + GOLDEN_KEY)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertArrayEquals(payload, first.body());
            assertArrayEquals(first.body(), second.body(), "same request returns byte-identical originals");
            var miss = http.send(HttpRequest.newBuilder(URI.create(base + "0".repeat(64))).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(409, miss.statusCode(), "missing key is an explicit refusal, never a nearest match");
            var traversal = http.send(HttpRequest.newBuilder(URI.create(base + "..%2Fescaped")).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(409, traversal.statusCode(), "traversal keys are refused on the endpoint too");
        } finally {
            server.stop(0);
        }
    }

    @Test void directoryLinkAtKeyPathCannotRedirectWrites(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectory(temp.resolve("store"));
        Path outside = Files.createDirectory(temp.resolve("outside"));
        String original = "{\"sentinel\":\"untouched\"}";
        Path sentinel = outside.resolve("sentinel.json");
        Files.writeString(sentinel, original, StandardCharsets.UTF_8);
        Path link = root.resolve(GOLDEN_KEY + ".json");
        var store = new EvaluationToolFreezeStore(root);
        try {
            if (OS.WINDOWS.isCurrentOs()) {
                Process mklink = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                        link.toString(), outside.toString()).redirectErrorStream(true).start();
                byte[] output = mklink.getInputStream().readAllBytes();
                assertEquals(0, mklink.waitFor(), () -> "Could not create test junction: " + new String(output));
            } else {
                Files.createSymbolicLink(link, outside);
            }
            assertEquals(outside.toRealPath(), link.toRealPath(), "the link must resolve outside the store");

            var refusal = assertThrows(EvaluationToolFreezeStore.FreezeWriteRefused.class,
                    () -> store.put(GOLDEN_KEY, "redirect".getBytes(StandardCharsets.UTF_8)));
            assertEquals("frozen artifact path is not contained in the store root", refusal.getMessage());
            assertEquals(outside.toRealPath(), link.toRealPath(), "the rejected write leaves the link in place");
            assertEquals(original, Files.readString(sentinel, StandardCharsets.UTF_8));
            assertFalse(Files.exists(root.resolve(GOLDEN_KEY + ".sha256")), "no sidecar is written");
        } finally {
            Files.deleteIfExists(link);
        }
        byte[] plain = "plain".getBytes(StandardCharsets.UTF_8);
        store.put(GOLDEN_KEY, plain);
        assertArrayEquals(plain, store.get(GOLDEN_KEY));
    }

    @Test void sidecarLinkToOutsideWithCorrectDigestIsRefused() throws Exception {
        // Acceptance R4 follow-up: a sidecar symlink pointing outside the root with the CORRECT
        // digest must not make get() succeed. Pre-fix the read follows the link.
        Path root = Files.createTempDirectory("admission-freeze-sidecar-");
        var store = new EvaluationToolFreezeStore(root);
        byte[] payload = "{\"v\":1}".getBytes(StandardCharsets.UTF_8);
        store.put(GOLDEN_KEY, payload);
        String digest = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(payload));
        Path outsideDir = root.getParent().resolve("sidecar-outside");
        Files.createDirectories(outsideDir);
        Path outside = outsideDir.resolve("outside-java.sha256");
        Files.writeString(outside, digest, StandardCharsets.US_ASCII);
        Path sidecar = root.resolve(GOLDEN_KEY + ".sha256");
        Files.deleteIfExists(sidecar);
        Files.createSymbolicLink(sidecar, outside);
        try {
            assertThrows(EvaluationToolFreezeStore.ToolFreezeMiss.class, () -> store.get(GOLDEN_KEY));
            assertArrayEquals(payload, Files.readAllBytes(root.resolve(GOLDEN_KEY + ".json")),
                    "the stored original must be untouched");
            assertTrue(Files.isSymbolicLink(sidecar), "the refusal must not rewrite the link");
            assertEquals(digest, Files.readString(outside, StandardCharsets.US_ASCII),
                    "the outside target must stay unchanged");
        } finally {
            Files.deleteIfExists(sidecar);
            Files.deleteIfExists(outside);
        }
    }

    @Test void danglingSidecarLinkIsRefusedOnReadAndWrite() throws Exception {
        Path root = Files.createTempDirectory("admission-freeze-dangling-");
        var store = new EvaluationToolFreezeStore(root);
        byte[] payload = "{\"v\":1}".getBytes(StandardCharsets.UTF_8);
        store.put(GOLDEN_KEY, payload);
        Path sidecar = root.resolve(GOLDEN_KEY + ".sha256");
        Files.deleteIfExists(sidecar);
        Files.createSymbolicLink(sidecar, root.getParent().resolve("does-not-exist-java.sha256"));
        try {
            assertThrows(EvaluationToolFreezeStore.ToolFreezeMiss.class, () -> store.get(GOLDEN_KEY));
            assertThrows(RuntimeException.class, () -> store.put(GOLDEN_KEY, payload));
            assertArrayEquals(payload, Files.readAllBytes(root.resolve(GOLDEN_KEY + ".json")));
            assertTrue(Files.isSymbolicLink(sidecar));
        } finally {
            Files.deleteIfExists(sidecar);
        }
    }

    @Test void putWithOutsideSidecarLinkIsRefusedAndTargetUntouched() throws Exception {
        Path root = Files.createTempDirectory("admission-freeze-sidecar-put-");
        var store = new EvaluationToolFreezeStore(root);
        byte[] payload = "{\"v\":1}".getBytes(StandardCharsets.UTF_8);
        store.put(GOLDEN_KEY, payload);
        Path outsideDir = root.getParent().resolve("sidecar-outside");
        Files.createDirectories(outsideDir);
        Path outside = outsideDir.resolve("outside-java-put.sha256");
        Files.writeString(outside, "sentinel-digest", StandardCharsets.US_ASCII);
        Path sidecar = root.resolve(GOLDEN_KEY + ".sha256");
        Files.deleteIfExists(sidecar);
        Files.createSymbolicLink(sidecar, outside);
        try {
            assertThrows(EvaluationToolFreezeStore.FreezeWriteRefused.class, () -> store.put(GOLDEN_KEY, payload));
            assertEquals("sentinel-digest", Files.readString(outside, StandardCharsets.US_ASCII),
                    "the outside target must not be rewritten");
            assertArrayEquals(payload, Files.readAllBytes(root.resolve(GOLDEN_KEY + ".json")));
        } finally {
            Files.deleteIfExists(sidecar);
            Files.deleteIfExists(outside);
        }
    }

    @Test void repairBranchTmpLinkIsRefusedAndSentinelUntouched() throws Exception {
        // Acceptance follow-up: existing payload + corrupted sidecar + <key>.sha256.tmp link.
        // The same-bytes put enters the sidecar-repair branch, which pre-fix writes the digest
        // through the outside link and returns success.
        Path root = Files.createTempDirectory("admission-freeze-rewrite-");
        var store = new EvaluationToolFreezeStore(root);
        byte[] payload = "{\"v\":1}".getBytes(StandardCharsets.UTF_8);
        store.put(GOLDEN_KEY, payload);
        Path sidecar = root.resolve(GOLDEN_KEY + ".sha256");
        Files.writeString(sidecar, "0".repeat(64), StandardCharsets.US_ASCII);
        Path outsideDir = root.getParent().resolve("tmp-outside");
        Files.createDirectories(outsideDir);
        Path outside = outsideDir.resolve("rewrite-tmp-java.sha256");
        Files.writeString(outside, "SENTINEL", StandardCharsets.US_ASCII);
        Path tmpLink = root.resolve(GOLDEN_KEY + ".sha256.tmp");
        Files.deleteIfExists(tmpLink);
        Files.createSymbolicLink(tmpLink, outside);
        try {
            assertThrows(EvaluationToolFreezeStore.FreezeWriteRefused.class, () -> store.put(GOLDEN_KEY, payload));
            assertEquals("SENTINEL", Files.readString(outside, StandardCharsets.US_ASCII),
                    "the repair branch must not write the digest outside the root");
            assertArrayEquals(payload, Files.readAllBytes(root.resolve(GOLDEN_KEY + ".json")));
            assertEquals("0".repeat(64), Files.readString(sidecar, StandardCharsets.US_ASCII),
                    "the corrupt sidecar stays untouched after the refusal");
            assertTrue(Files.isSymbolicLink(tmpLink), "the planted link itself must stay untouched");
        } finally {
            Files.deleteIfExists(tmpLink);
            Files.deleteIfExists(outside);
        }
    }

    @Test void payloadTmpLinkIsRefusedOnFirstWrite() throws Exception {
        Path root = Files.createTempDirectory("admission-freeze-payloadtmp-");
        var store = new EvaluationToolFreezeStore(root);
        byte[] payload = "{\"v\":1}".getBytes(StandardCharsets.UTF_8);
        Path outsideDir = root.getParent().resolve("tmp-outside");
        Files.createDirectories(outsideDir);
        Path outside = outsideDir.resolve("payload-tmp-java.sha256");
        Files.writeString(outside, "SENTINEL", StandardCharsets.US_ASCII);
        Path tmpLink = root.resolve(GOLDEN_KEY + ".json.tmp");
        Files.createSymbolicLink(tmpLink, outside);
        try {
            assertThrows(EvaluationToolFreezeStore.FreezeWriteRefused.class, () -> store.put(GOLDEN_KEY, payload));
            assertEquals("SENTINEL", Files.readString(outside, StandardCharsets.US_ASCII));
            assertFalse(Files.exists(root.resolve(GOLDEN_KEY + ".json")),
                    "no payload may be published through the refused write");
            assertTrue(Files.isSymbolicLink(tmpLink));
        } finally {
            Files.deleteIfExists(tmpLink);
            Files.deleteIfExists(outside);
        }
    }

    @Test void sidecarTmpLinkIsRefusedOnFirstWrite() throws Exception {
        Path root = Files.createTempDirectory("admission-freeze-sidecartmp-");
        var store = new EvaluationToolFreezeStore(root);
        byte[] payload = "{\"v\":1}".getBytes(StandardCharsets.UTF_8);
        Path outsideDir = root.getParent().resolve("tmp-outside");
        Files.createDirectories(outsideDir);
        Path outside = outsideDir.resolve("sidecar-tmp-java.sha256");
        Files.writeString(outside, "SENTINEL", StandardCharsets.US_ASCII);
        Path tmpLink = root.resolve(GOLDEN_KEY + ".sha256.tmp");
        Files.createSymbolicLink(tmpLink, outside);
        try {
            assertThrows(EvaluationToolFreezeStore.FreezeWriteRefused.class, () -> store.put(GOLDEN_KEY, payload));
            assertEquals("SENTINEL", Files.readString(outside, StandardCharsets.US_ASCII));
            assertFalse(Files.exists(root.resolve(GOLDEN_KEY + ".json")));
            assertTrue(Files.isSymbolicLink(tmpLink));
        } finally {
            Files.deleteIfExists(tmpLink);
            Files.deleteIfExists(outside);
        }
    }

    @Test void canonFormalWindowIsExplainableWithTheIsolatedEvaluationClock() {
        var start = LocalDateTime.of(2026, 11, 3, 0, 0);
        var end = LocalDateTime.of(2026, 11, 10, 0, 0);
        Instant evaluationReference = Instant.parse("2026-11-16T00:00:00Z");
        assertDoesNotThrow(() -> new DanmakuTypes.Query(1001L, start, end).checkTime(evaluationReference));
        Instant laterClock = Instant.parse("2026-12-16T00:00:00Z");
        var refused = assertThrows(AgentException.class, () -> new DanmakuTypes.Query(1001L, start, end).checkTime(laterClock));
        assertEquals("DATE_OUT_OF_RANGE", refused.code());
    }

    @Test void realTimeoutClockIsNotFrozen() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        try {
            var client = new DeepSeekClient(new DeepSeekProperties(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "synthetic-test-key",
                    "deepseek-test", "deepseek-test", Duration.ofSeconds(1), 384, 1024), json);
            var expired = new CallControl(Instant.now().minusSeconds(1), new CancellationToken());
            var failure = assertThrows(AgentException.class, () ->
                    client.complete("deepseek-test", List.of(Map.of("role", "user", "content", "x")), 256, expired, true));
            assertEquals("MODEL_UNAVAILABLE", failure.code(), "a past deadline must fail immediately on the real clock");
        } finally {
            server.stop(0);
        }
    }
}
