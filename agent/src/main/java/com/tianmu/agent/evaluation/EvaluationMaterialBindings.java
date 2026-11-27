package com.tianmu.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Formal material binding for the evaluation wiring: the service video IDs used by the evaluation
 * entry tests are bound to the SAME content-hash bindings the Python admission check verifies
 * (materials[].binding in agent/eval/report-trial-admission.example.json). The binding is
 * read-only; a service video id outside the table (demo1001 included) is never a formal material.
 */
public final class EvaluationMaterialBindings {
    public record Material(String key, String datasetRoot, String videoPath,
                           String videoSha256, String commentSha256, String danmakuSha256,
                           String datasetVersion) {}

    public record MaterialOverride(String key, Path datasetRoot, String datasetVersion,
                                   String danmakuSha256) {}

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /** Evaluation-side service video ids, one per formal calibration material; documented and
     * cross-checked against the shared config hashes by the tests. */
    private static final Map<Long, String> VIDEO_KEYS = Map.of(
            730011L, "hxc-doufu",
            730012L, "my-dragon",
            730013L, "ysjf-canon");

    private final Map<String, Material> byKey = new LinkedHashMap<>();
    private final Map<Long, Material> byVideo = new LinkedHashMap<>();

    private EvaluationMaterialBindings(Map<String, Material> materials) {
        materials.forEach(byKey::put);
        VIDEO_KEYS.forEach((video, key) -> {
            var material = materials.get(key);
            if (material == null) {
                throw new IllegalArgumentException("config does not bind formal material " + key);
            }
            byVideo.put(video, material);
        });
    }

    public static EvaluationMaterialBindings load(Path configJson) {
        return load(configJson, null);
    }

    /** Creates an immutable run-specific overlay without changing the shared material config. */
    public static EvaluationMaterialBindings load(Path configJson, MaterialOverride override) {
        try {
            JsonNode config = new ObjectMapper().readTree(configJson.toFile());
            var materials = new LinkedHashMap<String, Material>();
            for (JsonNode node : config.path("materials")) {
                var binding = node.path("binding");
                String key = node.path("key").asText();
                materials.put(key, new Material(
                        key, node.path("dataset_root").asText(), node.path("video_path").asText(),
                        binding.path("video_sha256").asText(), binding.path("comment_sha256").asText(),
                        binding.path("danmaku_sha256").asText(), binding.path("dataset_version").asText("baseline")));
            }
            if (override != null) {
                if (override.key() == null || override.datasetRoot() == null
                        || override.datasetVersion() == null || !SHA256.matcher(override.danmakuSha256()).matches())
                    throw new IllegalArgumentException("material override is incomplete");
                Material original = materials.get(override.key());
                if (original == null) throw new IllegalArgumentException("material override key is unknown");
                materials.put(override.key(), new Material(original.key(), override.datasetRoot().toString(),
                        original.videoPath(), original.videoSha256(), original.commentSha256(),
                        override.danmakuSha256(), override.datasetVersion()));
            }
            return new EvaluationMaterialBindings(materials);
        } catch (Exception error) {
            throw new IllegalStateException("formal material bindings unreadable: " + configJson, error);
        }
    }

    /** Unknown ids (demo1001 included) have no formal binding and are refused by the caller. */
    public Material forVideo(long videoId) { return byVideo.get(videoId); }

    public boolean knowsVideo(long videoId) { return byVideo.containsKey(videoId); }

    public Material forKey(String key) { return byKey.get(key); }
}
