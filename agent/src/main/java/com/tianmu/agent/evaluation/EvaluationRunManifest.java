package com.tianmu.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-run manifest build/verify/rebuild for the 4B-1 evaluation entry. Every run directory under
 * the integration evidence root carries manifest.json binding that specific run to its trial /
 * pair / controller identity and to the ORIGINAL bytes on disk (report json/html, run-scoped
 * trace). Verification RECOMPUTES every digest from the files and refuses forged, incomplete,
 * cross-contaminated (trace from another run) or tampered records — the index is never trusted
 * as its own source of proof. Batch indexes reference manifests by sha-256; older generations
 * are never rewritten.
 */
public final class EvaluationRunManifest {
    private static final ObjectMapper JSON = new ObjectMapper();
    /** The generation label the test execution is currently writing; set by the test, read by
     * helpers so helpers and tests agree on one generation. */
    public static String CURRENT_GENERATION = "";
    /** Run purpose categories: formal pair members vs the three control kinds. */
    public static final Set<String> PURPOSES = Set.of(
            "pair_member", "frozen_replay_control", "clock_rule_normal_control",
            "clock_rule_refusal_control");
    private static final Set<String> REQUIRED = Set.of(
            "trial_id", "run_id", "session_id", "pair_id", "controller", "controller_invoked",
            "repeat_no", "conditions", "conditions_sha256",
            "receipt_artifact_id", "receipt_json_url", "receipt_html_url",
            "permission_principal", "permission_read_status", "permission_cross_account_status",
            "report_json_sha256", "report_html_sha256", "report_json_bytes", "report_html_bytes",
            "trace_sha256", "trace_span_lines", "trace_run_ids",
            "freeze_key", "freeze_units_sha256", "freeze_served_from", "freeze_audit_rows",
            "material_video_sha256", "material_comment_sha256", "material_danmaku_sha256",
            "video_source_version", "task_clock_zone", "task_clock_reference",
            "provider_dispatches", "fee_note", "purpose");

    private EvaluationRunManifest() {}

    public static String hex(byte[] digest) {
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte b : digest) out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return out.toString();
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    /** Builds the manifest for one run; the caller supplies measured file digests and audit rows. */
    public static ObjectNode build(Map<String, Object> fields) {
        ObjectNode node = JSON.createObjectNode();
        for (var entry : fields.entrySet()) {
            node.set(entry.getKey(), JSON.valueToTree(entry.getValue()));
        }
        return node;
    }

    /** Recomputes every file digest and enforces the required field table for one run directory. */
    public static void validate(Path runDir) {
        ObjectNode report = JSON.createObjectNode();
        Path manifestFile = runDir.resolve("manifest.json");
        if (!Files.isRegularFile(manifestFile)) {
            throw new IllegalArgumentException("run directory has no manifest.json: " + runDir);
        }
        JsonNode manifest;
        try {
            manifest = JSON.readTree(Files.readString(manifestFile, StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalArgumentException("manifest.json unreadable: " + runDir, error);
        }
        for (String field : REQUIRED) {
            if (!manifest.has(field) || manifest.path(field).isNull()) {
                throw new IllegalArgumentException("manifest " + field + " missing or null: " + runDir);
            }
        }
        verifyFileDigest(runDir.resolve("report.json"), manifest.path("report_json_sha256").asText(), report);
        verifyFileDigest(runDir.resolve("report.html"), manifest.path("report_html_sha256").asText(), report);
        verifyFileDigest(runDir.resolve("trace-spans.jsonl"), manifest.path("trace_sha256").asText(), report);
        // trace contamination check: every span run.id must be this directory's run
        String runId = manifest.path("run_id").asText();
        Path traceFile = runDir.resolve("trace-spans.jsonl");
        try {
            for (String line : Files.readAllLines(traceFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                JsonNode span = JSON.readTree(line);
                if (!runId.equals(span.path("attributes").path("run.id").asText())) {
                    throw new IllegalArgumentException("trace contains spans of another run: " + runDir);
                }
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("trace unreadable: " + runDir, error);
        }
        // trial id is deterministically derived from the run and pair identities
        String expectedTrial = "trial-" + sha256((runId + "|" + manifest.path("pair_id").asText())
                .getBytes(StandardCharsets.UTF_8)).substring(0, 16);
        if (!expectedTrial.equals(manifest.path("trial_id").asText())) {
            throw new IllegalArgumentException("trial_id does not match the run/pair derivation: " + runDir);
        }
    }

    private static void verifyFileDigest(Path file, String expected, ObjectNode report) {
        try {
            byte[] data = Files.readAllBytes(file);
            String actual = sha256(data);
            if (!actual.equals(expected)) {
                throw new IllegalArgumentException("digest mismatch for " + file + ": manifest says "
                        + expected + ", file hashes to " + actual);
            }
            report.put(file.getFileName().toString() + "_sha256_verified", true);
        } catch (IOException error) {
            throw new IllegalArgumentException("artifact missing: " + file, error);
        }
    }

    /** The unified ledger field tables. Runs carry identity + repeat number + mode + purpose +
     * terminal status + the four recomputed digests; refusals carry the full flat identity plus
     * the outcome and reason. The Python admission rebuild emits exactly the same tables, so
     * one generation compares field-by-field across languages. */
    private static final String[] RUN_FIELDS = {"trial_id", "pair_id", "controller", "grounding",
            "purpose", "final_status", "report_json_sha256", "report_html_sha256", "trace_sha256",
            "manifest_sha256"};
    private static final String[] REFUSAL_FIELDS = {"trial_id", "pair_id", "controller",
            "purpose", "final_status", "error_code"};

    /** Derives the trial id exactly as the entry and the Python admission rebuild do. */
    private static String derivedTrialId(String runId, String pairId) {
        return "trial-" + sha256((runId + "|" + pairId).getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }

    /** Recomputes one artifact digest from the ORIGINAL file, refuses a missing file or a
     * manifest/file drift naming the run and the digest field, and returns the digest. */
    private static String verifyRebuildDigest(Path dir, JsonNode manifest, String runId,
                                              String field, String fileName) throws IOException {
        Path file = dir.resolve(fileName);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("run " + runId + ": delivered runs must archive "
                    + fileName + " (missing file for " + field + ")");
        }
        String actual = sha256(Files.readAllBytes(file));
        String declared = manifest.path(field).asText("");
        if (!actual.equals(declared)) {
            throw new IllegalArgumentException("run " + runId + ": " + field + " does not match "
                    + fileName + " on disk (manifest declares " + declared
                    + ", file hashes to " + actual + ")");
        }
        return actual;
    }

    /** Trace containment: every span in the archived trace must belong to this run. */
    private static void verifyTraceContainment(Path dir, String runId) throws IOException {
        for (String line : Files.readAllLines(dir.resolve("trace-spans.jsonl"), StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode span = JSON.readTree(line);
            String spanRun = span.path("attributes").path("run.id").asText("");
            if (!spanRun.isEmpty() && !spanRun.equals(runId)) {
                throw new IllegalArgumentException("run " + runId + ": trace contains spans of "
                        + "another run (" + spanRun + ") — cross-run contamination is refused");
            }
        }
    }

    /** Rebuilds an index purely from the run directories' ORIGINALS: every manifest is
     * re-verified (identity, trial derivation, purpose), every declared artifact digest is
     * RECOMPUTED from the files on disk, traces are checked for cross-run contamination,
     * refused control runs enter the ledger with their FULL FLAT identity, orphan directories
     * (neither manifest.json nor refusal.json) are diagnosed by name, and duplicate run or
     * trial ids across the generation are refused. */
    public static ObjectNode rebuildIndex(Path generationDir) {
        ObjectNode index = JSON.createObjectNode();
        ArrayNode runs = index.putArray("runs");
        ArrayNode refusals = index.putArray("refusals");
        if (!Files.isDirectory(generationDir)) {
            index.put("run_count", 0);
            index.put("refusal_count", 0);
            return index;
        }
        Set<String> runIds = new java.util.LinkedHashSet<>();
        Set<String> trialIds = new java.util.LinkedHashSet<>();
        try (var stream = Files.list(generationDir)) {
            List<Path> dirs = new ArrayList<>(stream.filter(Files::isDirectory).sorted().toList());
            for (Path dir : dirs) {
                Path manifestFile = dir.resolve("manifest.json");
                Path refusalFile = dir.resolve("refusal.json");
                boolean hasManifest = Files.isRegularFile(manifestFile);
                boolean hasRefusal = Files.isRegularFile(refusalFile);
                if (hasManifest && hasRefusal) {
                    // exactly ONE terminal-state original per directory: a directory carrying
                    // both could otherwise contribute a success AND a refusal ledger entry
                    throw new IllegalArgumentException("run directory " + dir.getFileName()
                            + ": both manifest.json and refusal.json exist — each run directory "
                            + "must hold exactly one terminal-state original");
                }
                if (hasManifest) {
                    JsonNode manifest = JSON.readTree(Files.readString(manifestFile, StandardCharsets.UTF_8));
                    String runId = manifest.path("run_id").asText("");
                    String trialId = manifest.path("trial_id").asText("");
                    String pairId = manifest.path("pair_id").asText("");
                    String controller = manifest.path("controller").asText("");
                    String purpose = manifest.path("purpose").asText("");
                    String finalStatus = manifest.path("final_status").asText("");
                    if (runId.isEmpty() || trialId.isEmpty() || pairId.isEmpty() || controller.isEmpty()) {
                        throw new IllegalArgumentException("run manifest in " + dir.getFileName()
                                + ": run_id/trial_id/pair_id/controller must be non-empty");
                    }
                    if (!dir.getFileName().toString().equals(runId)) {
                        // Repair-08: the ledger attributes originals by directory; a directory
                        // name disagreeing with the recorded run_id is a misattribution risk
                        throw new IllegalArgumentException("run directory " + dir.getFileName()
                                + ": manifest.json records run_id " + runId
                                + " — the directory name must equal the recorded run_id");
                    }
                    if (!PURPOSES.contains(purpose)) {
                        throw new IllegalArgumentException("run " + runId + ": unknown purpose " + purpose);
                    }
                    if (!trialIds.add(trialId) || !runIds.add(runId)) {
                        throw new IllegalArgumentException("duplicate run or trial id across the "
                                + "generation: " + runId + " / " + trialId);
                    }
                    if (!derivedTrialId(runId, pairId).equals(trialId)) {
                        throw new IllegalArgumentException("run " + runId
                                + ": trial_id does not match the run/pair derivation");
                    }
                    ObjectNode entry = runs.addObject();
                    entry.put("run_id", runId);
                    entry.put("trial_id", trialId);
                    entry.put("pair_id", pairId);
                    entry.put("controller", controller);
                    entry.put("repeat_no", manifest.path("repeat_no").asInt());
                    // the entry writes video_grounding; read THAT field for the report mode
                    entry.put("grounding", manifest.path("video_grounding").asText());
                    entry.put("purpose", purpose);
                    entry.put("final_status", finalStatus);
                    if ("SUCCEEDED".equals(finalStatus)) {
                        entry.put("report_json_sha256",
                                verifyRebuildDigest(dir, manifest, runId, "report_json_sha256", "report.json"));
                        entry.put("report_html_sha256",
                                verifyRebuildDigest(dir, manifest, runId, "report_html_sha256", "report.html"));
                        entry.put("trace_sha256",
                                verifyRebuildDigest(dir, manifest, runId, "trace_sha256", "trace-spans.jsonl"));
                        entry.put("manifest_sha256", sha256(Files.readAllBytes(manifestFile)));
                        verifyTraceContainment(dir, runId);
                    } else if (manifest.path("error_code").asText("").isEmpty()) {
                        throw new IllegalArgumentException("run " + runId
                                + ": non-SUCCEEDED manifests must carry an error_code");
                    }
                } else if (hasRefusal) {
                    JsonNode refusal = JSON.readTree(Files.readString(refusalFile, StandardCharsets.UTF_8));
                    String runId = refusal.path("run_id").asText("");
                    String trialId = refusal.path("trial_id").asText("");
                    String pairId = refusal.path("pair_id").asText("");
                    String controller = refusal.path("controller").asText("");
                    if (runId.isEmpty() || trialId.isEmpty() || pairId.isEmpty() || controller.isEmpty()) {
                        throw new IllegalArgumentException("refusal record in " + dir.getFileName()
                                + ": run_id/trial_id/pair_id/controller must be non-empty");
                    }
                    if (!dir.getFileName().toString().equals(runId)) {
                        throw new IllegalArgumentException("run directory " + dir.getFileName()
                                + ": refusal.json records run_id " + runId
                                + " — the directory name must equal the recorded run_id");
                    }
                    if (!PURPOSES.contains(refusal.path("purpose").asText(""))) {
                        throw new IllegalArgumentException("refusal " + runId + ": unknown purpose");
                    }
                    if (refusal.path("final_status").asText("").isEmpty()
                            || "SUCCEEDED".equals(refusal.path("final_status").asText())) {
                        throw new IllegalArgumentException("refusal " + runId
                                + ": final_status must name a non-SUCCEEDED outcome");
                    }
                    if (refusal.path("error_code").asText("").isEmpty()) {
                        throw new IllegalArgumentException("refusal " + runId
                                + ": error_code must be a non-empty string");
                    }
                    if (!derivedTrialId(runId, pairId).equals(trialId)) {
                        throw new IllegalArgumentException("refusal " + runId
                                + ": trial_id does not match the run/pair derivation");
                    }
                    if (!trialIds.add(trialId) || !runIds.add(runId)) {
                        throw new IllegalArgumentException("duplicate run or trial id across the "
                                + "generation: " + runId + " / " + trialId);
                    }
                    // refusal entries are FLAT records with their full identity (the pre-fix
                    // nested {"refusal": ...} shape made every comparator read empty ids)
                    ObjectNode entry = refusals.addObject();
                    entry.put("run_id", runId);
                    entry.put("trial_id", trialId);
                    entry.put("pair_id", pairId);
                    entry.put("controller", controller);
                    entry.put("purpose", refusal.path("purpose").asText());
                    entry.put("final_status", refusal.path("final_status").asText());
                    entry.put("error_code", refusal.path("error_code").asText());
                } else {
                    throw new IllegalArgumentException("orphan directory " + dir.getFileName()
                            + ": neither manifest.json nor refusal.json — the run cannot be "
                            + "attributed to this ledger");
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException("index rebuild failed for " + generationDir, error);
        }
        index.put("run_count", runs.size());
        index.put("refusal_count", refusals.size());
        return index;
    }

    /** One side's total must be a present strictly-integer count equal to its own array length
     * (Repair-08: missing, textual, boolean or fractional totals are mismatches, never
     * silently skipped — Jackson booleans and textuals are not ints, so isInt() excludes
     * them). */
    private static boolean totalSelfConsistent(JsonNode node, String countField, String arrayField) {
        JsonNode count = node.path(countField);
        return count.isInt() && count.asInt() == node.path(arrayField).size();
    }

    /** Field-by-field comparison: the top-level run/refusal totals are REQUIRED strict
     * integers equal to their own array lengths and to each other, every run entry matches the
     * declared batch entry on the full unified table (identity, repeat number, grounding,
     * purpose, terminal status, all four digests) and every refusal entry matches on its full
     * flat identity; duplicates, omissions, extras and empty ids fail in either direction. */
    public static boolean indexMatchesRebuild(Path generationDir, JsonNode batchIndex) {
        JsonNode declaredNode = batchIndex.path("index").isObject() ? batchIndex.path("index") : batchIndex;
        JsonNode rebuilt = rebuildIndex(generationDir);
        if (!totalSelfConsistent(declaredNode, "run_count", "runs")
                || !totalSelfConsistent(rebuilt, "run_count", "runs")
                || declaredNode.path("run_count").asInt() != rebuilt.path("run_count").asInt()) {
            return false;
        }
        if (!totalSelfConsistent(declaredNode, "refusal_count", "refusals")
                || !totalSelfConsistent(rebuilt, "refusal_count", "refusals")
                || declaredNode.path("refusal_count").asInt() != rebuilt.path("refusal_count").asInt()) {
            return false;
        }
        if (!runsMatch(declaredNode.path("runs"), rebuilt.path("runs"))) return false;
        return refusalsMatch(declaredNode.path("refusals"), rebuilt.path("refusals"));
    }

    private static boolean runsMatch(JsonNode declaredRuns, JsonNode rebuiltRuns) {
        if (declaredRuns.size() != rebuiltRuns.size()) return false;
        Map<String, JsonNode> declaredByRun = new LinkedHashMap<>();
        for (JsonNode run : declaredRuns) {
            String runId = run.path("run_id").asText("");
            if (runId.isEmpty() || declaredByRun.containsKey(runId)) return false;
            declaredByRun.put(runId, run);
        }
        Set<String> rebuiltIds = new java.util.LinkedHashSet<>();
        for (JsonNode run : rebuiltRuns) {
            String runId = run.path("run_id").asText("");
            if (runId.isEmpty() || !rebuiltIds.add(runId)) return false;
            JsonNode declared = declaredByRun.get(runId);
            if (declared == null) return false;
            for (String field : RUN_FIELDS) {
                if (!declared.path(field).asText("").equals(run.path(field).asText())) return false;
            }
            if (declared.path("repeat_no").asInt(-1) != run.path("repeat_no").asInt(-1)) return false;
        }
        return true;
    }

    private static boolean refusalsMatch(JsonNode declaredRefusals, JsonNode rebuiltRefusals) {
        if (declaredRefusals.size() != rebuiltRefusals.size()) return false;
        Map<String, JsonNode> declaredByRun = new LinkedHashMap<>();
        for (JsonNode refusal : declaredRefusals) {
            String runId = refusal.path("run_id").asText("");
            if (runId.isEmpty() || declaredByRun.containsKey(runId)) return false;
            declaredByRun.put(runId, refusal);
        }
        Set<String> rebuiltIds = new java.util.LinkedHashSet<>();
        for (JsonNode refusal : rebuiltRefusals) {
            String runId = refusal.path("run_id").asText("");
            if (runId.isEmpty() || !rebuiltIds.add(runId)) return false;
            JsonNode declared = declaredByRun.get(runId);
            if (declared == null) return false;
            for (String field : REFUSAL_FIELDS) {
                if (!declared.path(field).asText("").equals(refusal.path(field).asText())) return false;
            }
        }
        return true;
    }
}
