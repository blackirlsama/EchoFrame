package com.tianmu.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Repair-07 counterexamples: the Java rebuild must verify the ORIGINALS it indexes — recomputing
 * every artifact digest against the manifest's declared values, refusing cross-run traces,
 * orphan directories and identity-less refusals — refusal entries must be FLAT records carrying
 * the full refusal identity, and the batch comparison must be strict about counts, repeat
 * numbers, manifest digests and refusal identity fields. Every assertion here fails on the
 * pre-fix implementation and must pass after the fix (recorded under first-fail/).
 */
class EvaluationBatchLedgerRepair07Test {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String PAIR = "9".repeat(64);
    static final String VIDEO_SHA = "1e9f5d4b28ff89c863a0c0e034a6a39c230dce318dbc2fe770df5be2b20ff53a";
    static final String COMMENT_SHA = "2ae4338d24c3af7cdbc22474dd19f4811e8f19a2e1fb46b508d83a6c2c4af0e1";
    static final String DANMAKU_SHA = "7bb28506501be822a4dc84e59b6f3d5e9f635a57bf4f633d6f70a064c59f610f";
    static final String RUN_A = "a1b2c3d4e5f60718293a4b5c6d7e8f90";
    static final String RUN_B = "b2c3d4e5f60718293a4b5c6d7e8f90a1";
    static final String REFUSAL_RUN = "c3d4e5f60718293a4b5c6d7e8f90a1b2";

    static String trialId(String runId, String pairId) {
        return "trial-" + EvaluationRunManifest.sha256((runId + "|" + pairId)
                .getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }

    /** Flattens an exception chain into one string for message assertions. */
    static String messageOf(Throwable error) {
        StringBuilder out = new StringBuilder(error.getMessage() == null ? "" : error.getMessage());
        Throwable cause = error.getCause();
        while (cause != null) {
            out.append(" | ").append(cause.getMessage());
            cause = cause.getCause();
        }
        return out.toString();
    }

    Path seedRun(Path root, String runId, String controller) throws Exception {
        return seedRun(root, runId, controller, null);
    }

    Path seedRun(Path root, String runId, String controller, String foreignTraceSpan) throws Exception {
        Path dir = root.resolve(runId);
        Files.createDirectories(dir);
        byte[] report = ("{\"run_id\":\"" + runId + "\",\"kind\":\"report\"}").getBytes(StandardCharsets.UTF_8);
        byte[] html = ("<html><body>" + runId + "</body></html>").getBytes(StandardCharsets.UTF_8);
        StringBuilder trace = new StringBuilder();
        trace.append("{\"name\":\"span-1\",\"attributes\":{\"run.id\":\"").append(runId).append("\"}}\n");
        if (foreignTraceSpan != null) {
            trace.append("{\"name\":\"foreign\",\"attributes\":{\"run.id\":\"").append(foreignTraceSpan).append("\"}}\n");
        }
        Files.write(dir.resolve("report.json"), report);
        Files.write(dir.resolve("report.html"), html);
        Files.writeString(dir.resolve("trace-spans.jsonl"), trace.toString(), StandardCharsets.UTF_8);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("run_id", runId);
        manifest.put("trial_id", trialId(runId, PAIR));
        manifest.put("pair_id", PAIR);
        manifest.put("controller", controller);
        manifest.put("controller_invoked", controller);
        manifest.put("repeat_no", 1);
        manifest.put("conditions", Map.of("prompt", "题面"));
        manifest.put("video_id", 730013);
        manifest.put("video_grounding", "VIDEO_GROUNDED");
        manifest.put("final_status", "SUCCEEDED");
        manifest.put("error_code", "");
        manifest.put("receipt_artifact_id", "artifact-" + runId.substring(0, 8));
        manifest.put("permission_principal", "demo/alice");
        manifest.put("permission_read_status", 200);
        manifest.put("permission_cross_account_status", 403);
        manifest.put("material_video_sha256", VIDEO_SHA);
        manifest.put("material_comment_sha256", COMMENT_SHA);
        manifest.put("material_danmaku_sha256", DANMAKU_SHA);
        manifest.put("video_source_version", VIDEO_SHA);
        manifest.put("task_clock_zone", "Asia/Shanghai");
        manifest.put("task_clock_reference", "2026-11-16 00:00:00");
        manifest.put("provider_dispatches", 0);
        manifest.put("fee_note", "loopback fixture");
        manifest.put("purpose", "pair_member");
        manifest.put("report_json_sha256", EvaluationRunManifest.sha256(report));
        manifest.put("report_html_sha256", EvaluationRunManifest.sha256(html));
        manifest.put("trace_sha256", EvaluationRunManifest.sha256(
                trace.toString().getBytes(StandardCharsets.UTF_8)));
        Files.write(dir.resolve("manifest.json"), JSON.writeValueAsBytes(manifest));
        return dir;
    }

    Path seedRefusal(Path root, String dirName, Map<String, Object> overrides) throws Exception {
        Path dir = root.resolve(dirName);
        Files.createDirectories(dir);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("run_id", REFUSAL_RUN);
        record.put("trial_id", trialId(REFUSAL_RUN, PAIR));
        record.put("pair_id", PAIR);
        record.put("controller", "controller-b");
        record.put("purpose", "clock_rule_refusal_control");
        record.put("final_status", "FAILED");
        record.put("error_code", "DATE_OUT_OF_RANGE");
        if (overrides != null) record.putAll(overrides);
        Files.write(dir.resolve("refusal.json"), JSON.writeValueAsBytes(record));
        return dir;
    }

    Path generation(Path root) throws Exception {
        seedRun(root, RUN_A, "controller-a");
        seedRun(root, RUN_B, "controller-b");
        seedRefusal(root, REFUSAL_RUN, null);
        return root;
    }

    @Test
    void rebuildRefusesTamperedReport(@TempDir Path tmp) throws Exception {
        generation(tmp);
        Files.writeString(tmp.resolve(RUN_A).resolve("report.json"),
                "{\"run_id\":\"" + RUN_A + "\",\"kind\":\"FORGED\"}", StandardCharsets.UTF_8);
        Exception error = assertThrows(Exception.class,
                () -> EvaluationRunManifest.rebuildIndex(tmp));
        String message = messageOf(error);
        assertTrue(message.contains(RUN_A), "the error must name the run: " + message);
        assertTrue(message.contains("report_json_sha256"), "the error must name the field: " + message);
    }

    @Test
    void rebuildRefusesMissingArtifact(@TempDir Path tmp) throws Exception {
        generation(tmp);
        Files.delete(tmp.resolve(RUN_A).resolve("report.html"));
        Exception error = assertThrows(Exception.class,
                () -> EvaluationRunManifest.rebuildIndex(tmp));
        String message = messageOf(error);
        assertTrue(message.contains(RUN_A), "the error must name the run: " + message);
        assertTrue(message.contains("report.html"), "the error must name the missing file: " + message);
    }

    @Test
    void rebuildRefusesCrossRunTrace(@TempDir Path tmp) throws Exception {
        seedRun(tmp, RUN_A, "controller-a", "some-other-run");
        Exception error = assertThrows(Exception.class,
                () -> EvaluationRunManifest.rebuildIndex(tmp));
        String message = messageOf(error);
        assertTrue(message.contains(RUN_A), "the error must name the owning run: " + message);
        assertTrue(message.contains("some-other-run"), "the error must name the foreign run: " + message);
    }

    @Test
    void rebuildRefusesOrphanDirectory(@TempDir Path tmp) throws Exception {
        generation(tmp);
        Path orphan = tmp.resolve("unattributed-leftover");
        Files.createDirectories(orphan);
        Files.writeString(orphan.resolve("notes.txt"), "stray bytes", StandardCharsets.UTF_8);
        Exception error = assertThrows(Exception.class,
                () -> EvaluationRunManifest.rebuildIndex(tmp));
        assertTrue(messageOf(error).contains("unattributed-leftover"),
                "the error must name the orphan directory");
    }

    @Test
    void rebuildRefusalsAreFlatWithFullIdentity(@TempDir Path tmp) throws Exception {
        generation(tmp);
        JsonNode index = EvaluationRunManifest.rebuildIndex(tmp);
        assertEquals(1, index.path("refusal_count").asInt());
        JsonNode entry = index.path("refusals").path(0);
        assertEquals(REFUSAL_RUN, entry.path("run_id").asText(),
                "refusal entries must be flat: run_id at the entry level, not nested");
        for (String field : new String[]{"trial_id", "pair_id", "controller", "final_status", "error_code"}) {
            assertFalse(entry.path(field).asText("").isEmpty(),
                    "refusal entries must carry identity field " + field);
        }
        assertEquals(trialId(REFUSAL_RUN, PAIR), entry.path("trial_id").asText(),
                "the refusal trial id must follow the run/pair derivation");
    }

    @Test
    void rebuildRefusesIdentityLessRefusal(@TempDir Path tmp) throws Exception {
        generation(tmp);
        seedRefusal(tmp, "d4e5f60718293a4b5c6d7e8f90a1b2c3",
                Map.of("run_id", "", "trial_id", "trial-forged"));
        assertThrows(Exception.class, () -> EvaluationRunManifest.rebuildIndex(tmp),
                "a refusal without identity must never enter the ledger");
    }

    @Test
    void rebuiltEntriesCarryDigestFields(@TempDir Path tmp) throws Exception {
        generation(tmp);
        JsonNode index = EvaluationRunManifest.rebuildIndex(tmp);
        JsonNode entry = index.path("runs").path(0);
        Path dir = tmp.resolve(entry.path("run_id").asText());
        assertEquals(EvaluationRunManifest.sha256(Files.readAllBytes(dir.resolve("report.json"))),
                entry.path("report_json_sha256").asText());
        assertEquals(EvaluationRunManifest.sha256(Files.readAllBytes(dir.resolve("report.html"))),
                entry.path("report_html_sha256").asText());
        assertEquals(EvaluationRunManifest.sha256(Files.readAllBytes(dir.resolve("trace-spans.jsonl"))),
                entry.path("trace_sha256").asText());
        assertEquals(EvaluationRunManifest.sha256(Files.readAllBytes(dir.resolve("manifest.json"))),
                entry.path("manifest_sha256").asText());
        assertEquals("SUCCEEDED", entry.path("final_status").asText(),
                "run entries must carry the terminal status");
    }

    @Test
    void indexComparisonIsStrictOnCountsRepeatManifestAndRefusals(@TempDir Path tmp) throws Exception {
        generation(tmp);
        JsonNode rebuilt = EvaluationRunManifest.rebuildIndex(tmp);

        JsonNode counts = rebuilt.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) counts).put("run_count", 99);
        assertFalse(EvaluationRunManifest.indexMatchesRebuild(tmp, counts),
                "run count drift must fail the comparison");

        JsonNode refusals = rebuilt.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) refusals).put("refusal_count", 7);
        assertFalse(EvaluationRunManifest.indexMatchesRebuild(tmp, refusals),
                "refusal count drift must fail the comparison");

        JsonNode repeat = rebuilt.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) repeat.path("runs").path(0)).put("repeat_no", 2);
        assertFalse(EvaluationRunManifest.indexMatchesRebuild(tmp, repeat),
                "repeat number drift must fail the comparison");

        JsonNode manifestDigest = rebuilt.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) manifestDigest.path("runs").path(0))
                .put("manifest_sha256", "0".repeat(64));
        assertFalse(EvaluationRunManifest.indexMatchesRebuild(tmp, manifestDigest),
                "manifest digest drift must fail the comparison");

        JsonNode refusalIdentity = rebuilt.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) refusalIdentity.path("refusals").path(0))
                .put("controller", "controller-zzz");
        assertFalse(EvaluationRunManifest.indexMatchesRebuild(tmp, refusalIdentity),
                "refusal controller drift must fail the comparison");

        JsonNode refusalTrial = rebuilt.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) refusalTrial.path("refusals").path(0))
                .put("trial_id", "trial-forged");
        assertFalse(EvaluationRunManifest.indexMatchesRebuild(tmp, refusalTrial),
                "refusal trial drift must fail the comparison");

        JsonNode duplicateRefusal = rebuilt.deepCopy();
        com.fasterxml.jackson.databind.node.ArrayNode refusalArray =
                ((com.fasterxml.jackson.databind.node.ObjectNode) duplicateRefusal).putArray("refusals");
        refusalArray.add(rebuilt.path("refusals").path(0).deepCopy());
        refusalArray.add(rebuilt.path("refusals").path(0).deepCopy());
        assertFalse(EvaluationRunManifest.indexMatchesRebuild(tmp, duplicateRefusal),
                "duplicate refusal run ids must fail the comparison");
    }

    // ---- Repair-08 A: total count fields are REQUIRED strict integers ----------------

    @Test
    void missingCountFieldsAreMismatches(@TempDir Path tmp) throws Exception {
        generation(tmp);
        com.fasterxml.jackson.databind.node.ObjectNode declared =
                EvaluationRunManifest.rebuildIndex(tmp).deepCopy();
        declared.remove("run_count");
        assertFalse(EvaluationRunManifest.indexMatchesRebuild(tmp, declared),
                "a missing run_count must be a mismatch, not silently skipped");
        declared = EvaluationRunManifest.rebuildIndex(tmp).deepCopy();
        declared.remove("refusal_count");
        assertFalse(EvaluationRunManifest.indexMatchesRebuild(tmp, declared),
                "a missing refusal_count must be a mismatch");
    }

    @Test
    void textualAndBooleanCountsAreMismatches(@TempDir Path tmp) throws Exception {
        generation(tmp);
        com.fasterxml.jackson.databind.node.ObjectNode declared =
                EvaluationRunManifest.rebuildIndex(tmp).deepCopy();
        declared.put("run_count", String.valueOf(declared.path("run_count").asInt()));
        assertFalse(EvaluationRunManifest.indexMatchesRebuild(tmp, declared),
                "a textual run_count must be a mismatch even with the right value");
        declared = EvaluationRunManifest.rebuildIndex(tmp).deepCopy();
        declared.put("run_count", true);
        assertFalse(EvaluationRunManifest.indexMatchesRebuild(tmp, declared),
                "a boolean run_count must be a mismatch");
        declared = EvaluationRunManifest.rebuildIndex(tmp).deepCopy();
        declared.put("refusal_count", "1");
        assertFalse(EvaluationRunManifest.indexMatchesRebuild(tmp, declared),
                "a textual refusal_count must be a mismatch");
        declared = EvaluationRunManifest.rebuildIndex(tmp).deepCopy();
        declared.put("refusal_count", true);
        assertFalse(EvaluationRunManifest.indexMatchesRebuild(tmp, declared),
                "a boolean refusal_count must be a mismatch");
    }

    @Test
    void wrongCountAndArrayEntryDriftAreMismatches(@TempDir Path tmp) throws Exception {
        generation(tmp);
        com.fasterxml.jackson.databind.node.ObjectNode declared =
                EvaluationRunManifest.rebuildIndex(tmp).deepCopy();
        declared.put("run_count", 99);
        assertFalse(EvaluationRunManifest.indexMatchesRebuild(tmp, declared),
                "a wrong count value must be a mismatch");
        declared = EvaluationRunManifest.rebuildIndex(tmp).deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) declared.path("runs")).remove(0);
        assertFalse(EvaluationRunManifest.indexMatchesRebuild(tmp, declared),
                "a deleted run entry must be a mismatch");
        declared = EvaluationRunManifest.rebuildIndex(tmp).deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) declared.path("runs"))
                .add(declared.path("runs").path(0).deepCopy());
        assertFalse(EvaluationRunManifest.indexMatchesRebuild(tmp, declared),
                "an added run entry must be a mismatch");
    }

    @Test
    void wrappedAndDirectIndexFormsBothVerify(@TempDir Path tmp) throws Exception {
        generation(tmp);
        JsonNode rebuilt = EvaluationRunManifest.rebuildIndex(tmp);
        com.fasterxml.jackson.databind.node.ObjectNode wrapper = JSON.createObjectNode();
        wrapper.put("generation", "gen-forms");
        wrapper.set("index", rebuilt.deepCopy());
        assertTrue(EvaluationRunManifest.indexMatchesRebuild(tmp, wrapper),
                "the wrapped {" + "\"generation\", \"index\"} form must keep verifying");
        assertTrue(EvaluationRunManifest.indexMatchesRebuild(tmp, rebuilt.deepCopy()),
                "the direct index form must keep verifying");
    }

    // ---- Repair-08 B: exactly one terminal-state original per run directory ----------

    @Test
    void directoryWithBothTerminalFilesIsRefused(@TempDir Path tmp) throws Exception {
        generation(tmp);
        Files.write(tmp.resolve(RUN_A).resolve("refusal.json"),
                Files.readAllBytes(tmp.resolve(REFUSAL_RUN).resolve("refusal.json")));
        Exception error = assertThrows(Exception.class,
                () -> EvaluationRunManifest.rebuildIndex(tmp));
        String message = messageOf(error);
        assertTrue(message.contains(RUN_A), "the error must name the directory: " + message);
        assertTrue(message.contains("refusal.json"), "the error must name the duplicate original: " + message);
    }

    @Test
    void singleTerminalOriginalsStillBuild(@TempDir Path tmp) throws Exception {
        generation(tmp);
        JsonNode index = EvaluationRunManifest.rebuildIndex(tmp);
        assertEquals(2, index.path("run_count").asInt());
        assertEquals(1, index.path("refusal_count").asInt());
    }

    // ---- Repair-08 C: the directory name must equal the recorded run_id --------------

    @Test
    void manifestInWronglyNamedDirectoryIsRefused(@TempDir Path tmp) throws Exception {
        generation(tmp);
        Files.move(tmp.resolve(RUN_A), tmp.resolve("misnamed-dir"));
        Exception error = assertThrows(Exception.class,
                () -> EvaluationRunManifest.rebuildIndex(tmp));
        String message = messageOf(error);
        assertTrue(message.contains("misnamed-dir"), "the error must name the directory: " + message);
        assertTrue(message.contains("run_id"), "the error must name the conflicting field: " + message);
    }

    @Test
    void refusalInWronglyNamedDirectoryIsRefused(@TempDir Path tmp) throws Exception {
        generation(tmp);
        Files.move(tmp.resolve(REFUSAL_RUN), tmp.resolve("misnamed-refusal"));
        Exception error = assertThrows(Exception.class,
                () -> EvaluationRunManifest.rebuildIndex(tmp));
        String message = messageOf(error);
        assertTrue(message.contains("misnamed-refusal"), "the error must name the directory: " + message);
        assertTrue(message.contains("run_id"), "the error must name the conflicting field: " + message);
    }

    @Test
    void emptyRecordedIdIsRefused(@TempDir Path tmp) throws Exception {
        seedRun(tmp, RUN_A, "controller-a");
        Files.delete(tmp.resolve(RUN_A).resolve("manifest.json"));
        Map<String, Object> manifest = new LinkedHashMap<>();
        for (String field : new String[]{"trial_id", "pair_id", "controller", "controller_invoked"}) {
            manifest.put(field, field.equals("trial_id") ? trialId(RUN_A, PAIR) : "controller-a");
        }
        manifest.put("run_id", "");
        manifest.put("repeat_no", 1);
        manifest.put("conditions", Map.of("prompt", "题面"));
        manifest.put("video_id", 730013);
        manifest.put("video_grounding", "VIDEO_GROUNDED");
        manifest.put("final_status", "SUCCEEDED");
        manifest.put("error_code", "");
        manifest.put("receipt_artifact_id", "artifact-x");
        manifest.put("permission_principal", "demo/alice");
        manifest.put("permission_read_status", 200);
        manifest.put("permission_cross_account_status", 403);
        manifest.put("material_video_sha256", VIDEO_SHA);
        manifest.put("material_comment_sha256", COMMENT_SHA);
        manifest.put("material_danmaku_sha256", DANMAKU_SHA);
        manifest.put("video_source_version", VIDEO_SHA);
        manifest.put("task_clock_zone", "Asia/Shanghai");
        manifest.put("task_clock_reference", "2026-11-16 00:00:00");
        manifest.put("provider_dispatches", 0);
        manifest.put("fee_note", "loopback fixture");
        manifest.put("purpose", "pair_member");
        byte[] report = Files.readAllBytes(tmp.resolve(RUN_A).resolve("report.json"));
        byte[] html = Files.readAllBytes(tmp.resolve(RUN_A).resolve("report.html"));
        byte[] trace = Files.readAllBytes(tmp.resolve(RUN_A).resolve("trace-spans.jsonl"));
        manifest.put("report_json_sha256", EvaluationRunManifest.sha256(report));
        manifest.put("report_html_sha256", EvaluationRunManifest.sha256(html));
        manifest.put("trace_sha256", EvaluationRunManifest.sha256(trace));
        Files.write(tmp.resolve(RUN_A).resolve("manifest.json"), JSON.writeValueAsBytes(manifest));
        assertThrows(Exception.class, () -> EvaluationRunManifest.rebuildIndex(tmp),
                "an empty recorded run_id must never enter the ledger");
    }

    @Test
    void correctlyNamedDirectoriesStillBuild(@TempDir Path tmp) throws Exception {
        generation(tmp);
        JsonNode index = EvaluationRunManifest.rebuildIndex(tmp);
        assertEquals(2, index.path("run_count").asInt());
        assertEquals(1, index.path("refusal_count").asInt());
    }
}
