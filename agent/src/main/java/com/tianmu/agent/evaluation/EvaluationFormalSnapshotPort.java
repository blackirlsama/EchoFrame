package com.tianmu.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.DanmakuTypes;
import com.tianmu.agent.core.FeedbackAnalysis.*;
import com.tianmu.agent.evaluation.admission.EvaluationToolFreeze;
import com.tianmu.agent.evaluation.admission.EvaluationToolFreezeStore;
import com.tianmu.agent.feedback.FeedbackSnapshotPort;
import com.tianmu.agent.runtime.control.CallControl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.Clock;
import java.util.*;

/**
 * Evaluation wiring of the REAL product material boundary: implements the product's
 * FeedbackSnapshotPort so the report entry actually queries the formal calibration materials
 * (read-only CSVs under the declared dataset roots) instead of demo data, under the evaluation
 * task clock, with frozen tool replay at this boundary (freeze-key-v2, shared with the Python
 * side). The same canonical request serves byte-identical frozen originals; a changed window /
 * clock / material identity derives a different key (REPLAY misses are explicit, never replaced
 * by a nearest match); corrupt artifacts fail explicitly; unknown service video ids (demo1001
 * included) are refused as formal materials. This is evaluation wiring only — it never mutates
 * formal materials and never connects to a real provider.
 */
public final class EvaluationFormalSnapshotPort implements FeedbackSnapshotPort {
    public enum FreezeMode { RECORD_VERIFY, REPLAY }

    /** One audit row per export: identity, counts, served source, digest. Written when a sink is set. */
    public record ExportAudit(long videoId, String materialKey, String windowStart, String windowEnd,
                              Counts counts, String freezeKey, String servedFrom, byte[] unitsDigest,
                              String materialHashes, String datasetRoot, String datasetVersion,
                              int danmakuBefore17Minutes) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOOL = "prepare_feedback_materials";
    private static final String SAMPLING = "window-full-effective-columns";
    private static final String TOOL_VERSION = "evaluation-entry-v1";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final EvaluationMaterialBindings bindings;
    private final FreezeMode mode;
    private final Clock taskClock;
    private final EvaluationToolFreezeStore store;
    private final Path auditSink;
    private volatile ExportAudit lastAudit;

    public EvaluationFormalSnapshotPort(Path configPath, FreezeMode mode, Clock taskClock,
                                        Path freezeStoreRoot, Path auditSink) {
        this(EvaluationMaterialBindings.load(configPath), mode, taskClock, freezeStoreRoot, auditSink);
    }

    public EvaluationFormalSnapshotPort(EvaluationMaterialBindings bindings, FreezeMode mode, Clock taskClock,
                                        Path freezeStoreRoot, Path auditSink) {
        this.bindings = bindings;
        this.mode = mode;
        this.taskClock = taskClock;
        this.store = new EvaluationToolFreezeStore(freezeStoreRoot);
        this.auditSink = auditSink;
    }

    @Override public void authorize(AccessScope owner, long videoId) {
        if (!bindings.knowsVideo(videoId)) {
            throw new AgentException("FORBIDDEN", "service video " + videoId
                    + " has no formal material binding; synthetic demo ids (e.g. demo1001) are never formal materials");
        }
        if (!owner.videoIds().contains(videoId)) {
            throw new AgentException("FORBIDDEN", "video outside current authorization");
        }
    }

    @Override public Receipt export(Request request, AccessScope owner, CallControl control,
                                    java.util.function.Consumer<List<Unit>> consumer) {
        authorize(owner, request.query().videoId());
        var material = bindings.forVideo(request.query().videoId());
        if (material == null) {
            throw new AgentException("FORBIDDEN", "service video " + request.query().videoId()
                    + " has no formal material binding; synthetic demo ids (e.g. demo1001) are never formal materials");
        }
        String freezeKey = freezeKey(request, material);
        List<Unit> units;
        String servedFrom;
        if (mode == FreezeMode.REPLAY) {
            // Replay serves ONLY the frozen original: a miss is an explicit refusal, never a
            // recompute or a nearest match.
            byte[] frozen;
            try {
                frozen = store.get(freezeKey);
            } catch (EvaluationToolFreezeStore.ToolFreezeMiss error) {
                throw new AgentException("FROZEN_RESULT_MISS", "no frozen original for request " + freezeKey
                        + "; replay never recomputes or substitutes (" + error.getMessage() + ")");
            }
            units = parseUnits(frozen);
            servedFrom = "FROZEN";
        } else {
            units = readFormalUnits(request, material);
            byte[] canonical = canonicalUnits(units);
            try {
                byte[] existing = store.get(freezeKey);
                if (!Arrays.equals(existing, canonical)) {
                    throw new AgentException("FROZEN_RESULT_CONFLICT",
                            "the stored frozen original for " + freezeKey + " differs from the formal "
                                    + "material content; refusing instead of overwriting");
                }
                units = parseUnits(existing); // serve the frozen original bytes
                servedFrom = "FROZEN";
            } catch (EvaluationToolFreezeStore.ToolFreezeMiss absent) {
                store.put(freezeKey, canonical);
                servedFrom = "COMPUTED";
            }
        }
        var counts = countsOf(units);
        int danmakuBefore17Minutes = (int) units.stream()
                .filter(unit -> unit.source() == Source.DANMAKU && unit.playbackMs() != null
                        && unit.playbackMs() < 17 * 60 * 1000L)
                .count();
        consumer.accept(units);
        var audit = new ExportAudit(request.query().videoId(), material.key(),
                request.query().start().toString(), request.query().end().toString(), counts,
                freezeKey, servedFrom, unitsDigest(units), materialHashes(material), material.datasetRoot(),
                material.datasetVersion(), danmakuBefore17Minutes);
        lastAudit = audit;
        writeAudit(audit);
        return new Receipt(taskClock.instant(), counts);
    }

    private List<Unit> readFormalUnits(Request request, EvaluationMaterialBindings.Material material) {
        var start = request.query().start().atZone(ZONE).toInstant();
        var end = request.query().end().atZone(ZONE).toInstant();
        List<Unit> units = new ArrayList<>();
        var commentRows = FormalCsvReader.read(Path.of(material.datasetRoot(), "评论", material.key() + "_评论.csv"));
        for (var row : commentRows) {
            var created = parse(row.get("评论时间"));
            if (created.isBefore(start) || !created.isBefore(end)) continue;
            String replyContext = row.getOrDefault("上级评论ID", "");
            Long likes = numeric(row.get("点赞数"));
            units.add(new Unit("C:" + row.get("评论ID"), Source.COMMENT, row.get("评论ID"),
                    row.get("评论内容"), replyContext, false, null, row.get("用户ID"), created, likes));
        }
        var danmakuRows = FormalCsvReader.read(Path.of(material.datasetRoot(), "弹幕", material.key() + "_弹幕.csv"));
        for (var row : danmakuRows) {
            var created = parse(row.get("仿真发送时间"));
            if (created.isBefore(start) || !created.isBefore(end)) continue;
            long playback = Math.round(Double.parseDouble(row.get("播放位置秒")) * 1000);
            units.add(new Unit("D:" + row.get("序号"), Source.DANMAKU, row.get("序号"),
                    row.get("内容"), "", false, playback, row.get("发送者ID"), created, null));
        }
        return units;
    }

    /** The pre-shift column, for the audit assertion that old collection dates hit 0 in-window. */
    public int originalDateHits(long videoId, String start, String end) {
        var material = bindings.forVideo(videoId);
        if (material == null) throw new AgentException("FORBIDDEN", "no formal binding for video " + videoId);
        var startInstant = LocalDateTime.parse(start + "T00:00:00").atZone(ZONE).toInstant();
        var endInstant = LocalDateTime.parse(end + "T00:00:00").atZone(ZONE).toInstant();
        int hits = 0;
        for (var row : FormalCsvReader.read(Path.of(material.datasetRoot(), "评论", material.key() + "_评论.csv"))) {
            var created = parse(row.get("原始评论时间"));
            if (!created.isBefore(startInstant) && created.isBefore(endInstant)) hits++;
        }
        return hits;
    }

    private static Instant parse(String value) {
        // Formal CSV time columns use a space separator (2026-11-09 09:00:37).
        return LocalDateTime.parse(value.trim().replace(' ', 'T')).atZone(ZONE).toInstant();
    }

    private static Long numeric(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Long.parseLong(value.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static Counts countsOf(List<Unit> units) {
        long comments = 0, danmaku = 0;
        var audience = new HashSet<String>();
        for (var unit : units) {
            if (unit.source() == Source.COMMENT) comments++; else danmaku++;
            if (!unit.author() && unit.userId() != null) audience.add(unit.userId());
        }
        return new Counts(comments, danmaku, 0, 0, audience.size());
    }

    private String freezeKey(Request request, EvaluationMaterialBindings.Material material) {
        var arguments = JSON.createObjectNode()
                .put("video_id", request.query().videoId())
                .put("start", request.query().start().toString())
                .put("end", request.query().end().toString());
        var sources = JSON.createArrayNode();
        request.sources().stream().sorted().forEach(s -> sources.add(s.name()));
        arguments.set("sources", sources);
        var materialNode = JSON.createObjectNode()
                .put("video_sha256", material.videoSha256())
                .put("comment_sha256", material.commentSha256())
                .put("danmaku_sha256", material.danmakuSha256());
        var clockNode = JSON.createObjectNode()
                .put("timezone", "Asia/Shanghai")
                .put("reference_time", taskClock.instant().toString());
        var windowNode = JSON.createObjectNode()
                .put("kind", "absolute")
                .put("start", request.query().start().toString())
                .put("end", request.query().end().toString());
        var payload = JSON.createObjectNode();
        payload.put("tool", TOOL);
        payload.set("arguments", arguments);
        payload.set("material", materialNode);
        payload.set("task_clock", clockNode);
        payload.set("task_window", windowNode);
        payload.put("sampling_strategy", SAMPLING);
        payload.put("tool_config_version", TOOL_VERSION);
        return EvaluationToolFreeze.key(payload);
    }

    private static byte[] canonicalUnits(List<Unit> units) {
        var array = JSON.createArrayNode();
        for (var unit : units) {
            var node = JSON.createObjectNode();
            node.put("id", unit.id());
            node.put("source", unit.source().name());
            node.put("originalId", unit.originalId());
            node.put("text", unit.text());
            node.put("replyContext", unit.replyContext());
            node.put("author", unit.author());
            if (unit.playbackMs() != null) node.put("playbackMs", unit.playbackMs()); else node.putNull("playbackMs");
            node.put("userId", unit.userId());
            node.put("createdAt", unit.createdAt().toString());
            if (unit.likeCount() != null) node.put("likeCount", unit.likeCount()); else node.putNull("likeCount");
            array.add(node);
        }
        var payload = JSON.createObjectNode();
        payload.set("units", array);
        return EvaluationToolFreeze.canonicalJson(payload).getBytes(StandardCharsets.UTF_8);
    }

    private static List<Unit> parseUnits(byte[] frozen) {
        try {
            var payload = JSON.readTree(frozen);
            List<Unit> units = new ArrayList<>();
            for (var node : payload.path("units")) {
                units.add(new Unit(node.path("id").asText(),
                        Source.valueOf(node.path("source").asText()),
                        node.path("originalId").asText(), node.path("text").asText(),
                        node.path("replyContext").asText(), node.path("author").asBoolean(),
                        node.path("playbackMs").isIntegralNumber() ? node.path("playbackMs").asLong() : null,
                        node.path("userId").asText(),
                        java.time.Instant.parse(node.path("createdAt").asText()),
                        node.path("likeCount").isIntegralNumber() ? node.path("likeCount").asLong() : null));
            }
            return units;
        } catch (Exception error) {
            throw new AgentException("FROZEN_RESULT_CORRUPT", "the frozen original cannot be parsed back into units");
        }
    }

    private static byte[] unitsDigest(List<Unit> units) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonicalUnits(units));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static String materialHashes(EvaluationMaterialBindings.Material material) {
        return material.videoSha256() + "|" + material.commentSha256() + "|" + material.danmakuSha256();
    }

    private void writeAudit(ExportAudit audit) {
        if (auditSink == null) return;
        try {
            Files.createDirectories(auditSink.getParent());
            var line = JSON.createObjectNode()
                    .put("video_id", audit.videoId()).put("material_key", audit.materialKey())
                    .put("window_start", audit.windowStart()).put("window_end", audit.windowEnd())
                    .put("comments", audit.counts().comments()).put("danmaku", audit.counts().danmaku())
                    .put("unique_audience", audit.counts().uniqueAudience())
                    .put("freeze_key", audit.freezeKey()).put("served_from", audit.servedFrom())
                    .put("units_sha256", hex(audit.unitsDigest()))
                    .put("material_hashes", audit.materialHashes())
                    .put("dataset_root", audit.datasetRoot()).put("dataset_version", audit.datasetVersion())
                    .put("danmaku_before_17_minutes", audit.danmakuBefore17Minutes())
                    .put("task_clock", taskClock.instant().toString());
            Files.writeString(auditSink, JSON.writeValueAsString(line) + "\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception error) {
            throw new IllegalStateException("freeze audit sink unwritable", error);
        }
    }

    private static String hex(byte[] digest) {
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte b : digest) out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return out.toString();
    }

    public ExportAudit lastAudit() {
        var current = lastAudit;
        if (current == null) throw new IllegalStateException("no export has run yet");
        return current;
    }

    /** Number of audit rows currently on disk (per-run delta bookkeeping). */
    public long auditRows() {
        if (auditSink == null || !Files.isRegularFile(auditSink)) return 0;
        try {
            return Files.readAllLines(auditSink, StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank()).count();
        } catch (IOException error) {
            throw new IllegalStateException("audit sink unreadable", error);
        }
    }

    /** Audit rows added after the given row count (for per-run binding of exports). */
    public java.util.List<String> auditSince(long beforeRows) {
        if (auditSink == null || !Files.isRegularFile(auditSink)) return List.of();
        try {
            var lines = Files.readAllLines(auditSink, StandardCharsets.UTF_8)
                    .stream().filter(l -> !l.isBlank()).toList();
            return lines.size() <= beforeRows ? List.of() : lines.subList((int) beforeRows, lines.size());
        } catch (IOException error) {
            throw new IllegalStateException("audit sink unreadable", error);
        }
    }
}
