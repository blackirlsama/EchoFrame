package com.tianmu.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.tianmu.agent.evaluation.EvaluationVideoCanonEntry.ANALYSIS_MODEL;
import static com.tianmu.agent.evaluation.EvaluationVideoCanonEntry.MODEL_A;
import static com.tianmu.agent.evaluation.EvaluationVideoCanonEntry.MODEL_B;
import static com.tianmu.agent.evaluation.EvaluationVideoCanonEntry.PROMPT;
import static com.tianmu.agent.evaluation.EvaluationVideoCanonEntry.VISION_MODEL;

/**
 * The Canon opt-in contract. It binds the approval to the actual run inputs and conditions, not
 * to a placeholder string: baseline, pair group, material config and bindings, frozen frame
 * manifest, historical ledger digest, roles, prompt, time window and clock, budget profile and
 * tool/sampling versions. The receipt carries this file's SHA-256; the entry re-reads the file and
 * re-derives every field from the inputs before any ledger row is copied.
 */
final class EvaluationVideoCanonContract {
    static final String KIND = "offline-evaluation-contract-v1";
    static final String WINDOW_START = "2026-11-03 00:00:00";
    static final String WINDOW_END = "2026-11-10 00:00:00";
    static final String TASK_CLOCK = "2026-11-16T00:00:00Z";

    private static final ObjectMapper JSON = new ObjectMapper();

    private EvaluationVideoCanonContract() {}

    static ObjectNode build(Path materialConfig, Path framesSource, Path ledger, String ledgerSha256,
                            String baseline, String pairGroup) throws IOException {
        JsonNode config = JSON.readTree(Files.readString(materialConfig, StandardCharsets.UTF_8));
        Path manifest = framesSource.resolve("frames-manifest.json");
        JsonNode frames = JSON.readTree(Files.readString(manifest, StandardCharsets.UTF_8));

        ObjectNode contract = JSON.createObjectNode();
        contract.put("kind", KIND);
        contract.put("baseline", baseline);
        contract.put("pair_group", pairGroup);
        contract.put("prompt_sha256", sha256(PROMPT.getBytes(StandardCharsets.UTF_8)));
        contract.put("window_start", WINDOW_START);
        contract.put("window_end", WINDOW_END);
        contract.put("task_clock", TASK_CLOCK);
        contract.put("budget_profile", budgetProfile());
        contract.put("tool_config_version", toolConfigVersion());
        contract.put("sampling_strategy", samplingStrategy());
        contract.put("material_config_sha256", sha256(materialConfig));
        contract.set("material_bindings", bindings(config));
        contract.put("frames_manifest_sha256", sha256(manifest));
        contract.put("frames_count", frames.path("frames").size());
        contract.put("source_video_sha256", frames.path("source").path("video_sha256").asText());
        contract.put("ledger_sha256", ledgerSha256);
        ObjectNode roles = contract.putObject("roles");
        roles.put("controller_a", MODEL_A);
        roles.put("controller_b", MODEL_B);
        roles.put("analysis", ANALYSIS_MODEL);
        roles.put("vision", VISION_MODEL);
        return contract;
    }

    static String sha256(Path file) throws IOException {
        return sha256(Files.readAllBytes(file));
    }

    static String sha256(byte[] bytes) {
        try {
            StringBuilder out = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes))
                out.append(String.format("%02x", value));
            return out.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    static void verify(Path contractFile, String expectedSha256, String baseline, String pairGroup,
                       Path materialConfig, Path framesSource, Path ledger, String ledgerSha256) throws IOException {
        byte[] bytes = Files.readAllBytes(contractFile);
        String actualSha256 = sha256(bytes);
        if (!actualSha256.equals(expectedSha256))
            throw new IllegalStateException("APPROVAL_MISMATCH: contract file SHA-256 is " + actualSha256
                    + ", receipt declares " + expectedSha256);
        JsonNode contract = JSON.readTree(bytes);
        JsonNode expected = build(materialConfig, framesSource, ledger, ledgerSha256, baseline, pairGroup);
        if (!contract.equals(expected))
            throw new IllegalStateException("APPROVAL_MISMATCH: contract does not bind this run's "
                    + "baseline/material/frames/ledger/roles/conditions");
    }

    private static ObjectNode bindings(JsonNode config) {
        ObjectNode out = JSON.createObjectNode();
        for (JsonNode material : config.path("materials")) {
            ObjectNode row = out.putObject(material.path("key").asText());
            JsonNode binding = material.path("binding");
            row.put("video_sha256", binding.path("video_sha256").asText());
            row.put("comment_sha256", binding.path("comment_sha256").asText());
            row.put("danmaku_sha256", binding.path("danmaku_sha256").asText());
            row.put("dataset_version", binding.path("dataset_version").asText("baseline"));
        }
        return out;
    }

    static String budgetProfile() {
        return System.getProperty("eval.approval.budget", "headroom-770");
    }

    static String toolConfigVersion() {
        return System.getProperty("eval.tool.config.version", "evaluation-entry-v1");
    }

    static String samplingStrategy() {
        return System.getProperty("eval.sampling.strategy", "frame-12-window-full");
    }

    static Map<String, Object> facts(JsonNode contract) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", contract.path("kind").asText());
        out.put("baseline", contract.path("baseline").asText());
        out.put("pair_group", contract.path("pair_group").asText());
        out.put("material_config_sha256", contract.path("material_config_sha256").asText());
        out.put("frames_manifest_sha256", contract.path("frames_manifest_sha256").asText());
        out.put("frames_count", contract.path("frames_count").asInt());
        out.put("source_video_sha256", contract.path("source_video_sha256").asText());
        out.put("ledger_sha256", contract.path("ledger_sha256").asText());
        out.put("roles", contract.path("roles"));
        return out;
    }
}
