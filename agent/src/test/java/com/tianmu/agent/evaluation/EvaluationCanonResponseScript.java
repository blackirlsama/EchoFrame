package com.tianmu.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static com.tianmu.agent.evaluation.EvaluationVideoCanonLoopback.MARKER;
import static com.tianmu.agent.evaluation.EvaluationVideoCanonLoopback.VISION;

final class EvaluationCanonResponseScript {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern AT_MS = Pattern.compile("atMs=(\\d+)");
    private static final Pattern EVIDENCE_ID = Pattern.compile("\"evidence_id\"\\s*:\\s*\"([^\"]+)\"");

    private EvaluationCanonResponseScript() {}

    static String respond(String path, byte[] raw, Map<String, Object> entry, String analysisModel) throws Exception {
        entry.put("path", path);
        entry.put("body_bytes", raw.length);
        entry.put("body_sha256", EvaluationVideoCanonEvidence.sha256(raw));
        entry.put("pid", ProcessHandle.current().pid());
        JsonNode request = JSON.readTree(raw);
        String model = request.path("model").asText();
        entry.put("model", model);
        boolean tools = !request.path("tools").isEmpty();
        entry.put("has_tools", tools);
        entry.put("tool_catalog", request.path("tools").size());
        if (path.equals("/vision/v1/chat/completions") || path.equals("/v1/chat/completions")) {
            entry.put("role", "vision");
            return vision(request, entry);
        }
        if (!path.equals("/chat/completions")) throw new IllegalArgumentException("Unexpected fixture path");
        entry.put("role", tools ? "controller" : "analysis");
        if (!tools) entry.put("body_sha256_key_order_variants", keyOrderVariants(raw));
        return tools ? controller(request, entry, model) : analysis(request, entry, model, analysisModel);
    }

    // ---------------------------------------------------------------- controller

    private static String controller(JsonNode request, Map<String, Object> entry, String model) throws Exception {
        int results = 0;
        for (JsonNode message : request.path("messages")) {
            if (!"tool".equals(message.path("role").asText())) continue;
            // A rejected call is fed back for correction; it did not advance the workflow. This
            // covers both the single-call schema/handler refusal and a refused native batch.
            String content = message.path("content").asText("");
            if (content.contains("\"input_validation\"") || content.contains("\"batch_validation\"")) continue;
            results++;
        }
        String tool = results == 0 ? "read_skill"
                : results == 1 ? "analyze_video_content"
                : results == 2 ? "analyze_comments"
                : results == 3 ? "analyze_danmaku" : "create_feedback_report";
        String arguments = switch (tool) {
            case "read_skill" -> "{\"name\":\"audience-report\"}";
            case "analyze_video_content" -> "{\"video_id\":" + EvaluationVideoCanonEvidence.CANON_VIDEO + "}";
            case "create_feedback_report" -> reportArguments(request);
            default -> "{}";
        };
        ObjectNode payload = JSON.createObjectNode();
        payload.put("id", "ctl-" + entry.get("index"));
        payload.put("model", model);
        payload.put("object", "chat.completion");
        ObjectNode choice = payload.putArray("choices").addObject();
        choice.put("index", 0);
        choice.put("finish_reason", "tool_calls");
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        message.put("content", "");
        ObjectNode call = message.putArray("tool_calls").addObject();
        call.put("id", "call-" + entry.get("index"));
        call.put("type", "function");
        ObjectNode function = call.putObject("function");
        function.put("name", tool);
        function.put("arguments", arguments);
        ObjectNode usage = payload.putObject("usage");
        usage.put("prompt_tokens", 1200 + 10 * (Integer) entry.get("index"));
        usage.put("completion_tokens", 300);
        entry.put("tool", tool);
        entry.put("usage", Map.of("prompt_tokens", 1200 + 10 * (Integer) entry.get("index"), "completion_tokens", 300));
        return JSON.writeValueAsString(payload);
    }

    /**
     * Headroom-only controller variant: two extra legal read_skill turns before the report, with
     * response usage set to the R7 real-load magnitude. All amounts are explicitly marked as
     * scripted; the seven R7 figures come from the local ledger, not from this replay.
     */
    static String headroomController(String body, byte[] raw, Map<String, Object> entry) throws Exception {
        JsonNode request = JSON.readTree(raw);
        int results = 0;
        for (JsonNode message : request.path("messages")) {
            if (!"tool".equals(message.path("role").asText())) continue;
            String content = message.path("content").asText("");
            if (content.contains("\"input_validation\"") || content.contains("\"batch_validation\"")) continue;
            results++;
        }
        int[] r7Usage = {1725, 4065, 5246, 5203, 6172, 6516, 6735};
        int index = (Integer) entry.get("index");
        int tokens = index < r7Usage.length ? r7Usage[index] : 6500;
        ObjectNode payload = (ObjectNode) JSON.readTree(body);
        ObjectNode usage = (ObjectNode) payload.path("usage");
        usage.put("prompt_tokens", tokens);
        usage.put("completion_tokens", 0);
        entry.put("usage", Map.of("prompt_tokens", tokens, "completion_tokens", 0));
        entry.put("usage_source", "scripted-r7-magnitude");
        if (results == 4 || results == 5) {
            // Distinct declared skills: an identical successful call is rejected as NO_PROGRESS.
            // Both are real skills in the frozen catalog; no tool schema or tolerance is changed.
            String skill = results == 4 ? "comment-analysis" : "danmaku-analysis";
            ObjectNode call = (ObjectNode) payload.path("choices").path(0).path("message")
                    .path("tool_calls").path(0);
            ObjectNode function = (ObjectNode) call.path("function");
            function.put("name", "read_skill");
            function.put("arguments", "{\"name\":\"" + skill + "\"}");
            entry.put("tool", "read_skill");
            entry.put("skill", skill);
            entry.put("extra_turn", results - 3);
        }
        return JSON.writeValueAsString(payload);
    }

    /** Only the two prepared sources are passed to the report tool; the video evidence stays in the
     *  run and is frozen into the job by the first source preparation. */
    private static String reportArguments(JsonNode request) throws Exception {
        String comment = null, danmaku = null;
        for (JsonNode message : request.path("messages")) {
            if (!"tool".equals(message.path("role").asText())) continue;
            String content = message.path("content").asText("");
            JsonNode row;
            try {
                row = JSON.readTree(content);
            } catch (Exception unreadable) {
                continue;
            }
            JsonNode data = row.path("data");
            String source = data.path("summary").path("source").asText("");
            String id = data.path("evidence_id").asText(row.path("evidenceId").asText(""));
            if (id.isBlank()) {
                Matcher fallback = EVIDENCE_ID.matcher(content);
                if (fallback.find()) id = fallback.group(1);
            }
            if (id.isBlank()) continue;
            if (source.equals("COMMENT")) comment = id;
            if (source.equals("DANMAKU")) danmaku = id;
        }
        if (comment == null || danmaku == null)
            throw new IllegalStateException("both prepared source evidence ids are required");
        return "{\"comment_evidence_id\":\"" + comment + "\",\"danmaku_evidence_id\":\"" + danmaku + "\"}";
    }

    // ---------------------------------------------------------------- analysis

    private static String analysis(JsonNode request, Map<String, Object> entry, String model, String analysisModel) throws Exception {
        JsonNode material = request;
        for (JsonNode message : request.path("messages"))
            if ("user".equals(message.path("role").asText()) && message.path("content").isTextual()) {
                try {
                    material = JSON.readTree(message.path("content").asText());
                } catch (Exception ignored) {
                }
            }
        if (!model.equals(analysisModel))
            throw new IllegalStateException("the analysis role must stay pinned; saw " + model);
        entry.put("analysis_kind", material.path("kind").asText(""));
        entry.put("has_video", material.has("video"));
        entry.put("video_summary_sha256", material.has("video")
                ? EvaluationVideoCanonEvidence.sha256(material.path("video").path("summary").asText("")
                        .getBytes(StandardCharsets.UTF_8)) : "");
        List<String> ids = new ArrayList<>();
        String title = "画面与反馈";
        String observation = "观众讨论集中在机身、屏幕与按键；" + MARKER + " 显示白色相机机身与翻转屏。";
        if ("MERGE".equals(material.path("kind").asText())) {
            for (JsonNode child : material.path("children")) ids.add(child.path("id").asText());
            if (!material.path("children").isEmpty()) {
                title = material.path("children").get(0).path("title").asText(title);
                observation = material.path("children").get(0).path("observation").asText(observation);
            }
        } else {
            for (JsonNode group : material.path("groups")) ids.add(group.path("id").asText());
        }
        if (ids.isEmpty()) throw new IllegalStateException("analysis material carries no members");
        entry.put("member_count", ids.size());
        ObjectNode content = JSON.createObjectNode();
        ArrayNode topics = content.putArray("topics");
        ObjectNode topic = topics.addObject();
        topic.put("key", "T1");
        topic.put("title", title);
        topic.put("observation", observation);
        topic.put("recommendation", "");
        ObjectNode assignments = content.putObject("assignments");
        for (String id : ids) assignments.put(id, "T1");
        return completion("an-" + entry.get("index"), model, JSON.writeValueAsString(content), 900, 260, entry);
    }

    // ---------------------------------------------------------------- synthetic failures

    /** Same-shape envelopes with a deliberately invalid analysis body; only the body differs. */
    /**
     * Same-shape controller envelope carrying two native calls in one assistant message. Each
     * member is real and individually admitted; only the batch shape differs, so the refusal that
     * gets recorded is the dispatcher's own (second-member schema vs whole-group combination).
     * Once the correction feedback is in the transcript the script stops injecting and the normal
     * single-call workflow continues.
     */
    static String syntheticBatch(String scenario, String body, byte[] raw, Map<String, Object> entry) throws Exception {
        JsonNode request = JSON.readTree(raw);
        for (JsonNode message : request.path("messages"))
            if ("tool".equals(message.path("role").asText())
                    && message.path("content").asText("").contains("\"batch_validation\""))
                return body;
        ObjectNode payload = (ObjectNode) JSON.readTree(body);
        ObjectNode message = (ObjectNode) payload.path("choices").path(0).path("message");
        String base = message.path("tool_calls").path(0).path("id").asText("call");
        ArrayNode calls = message.putArray("tool_calls");
        record Member(String name, String arguments) {}
        List<Member> members = switch (scenario) {
            case "batch-member-invalid" -> List.of(
                    new Member("analyze_video_content", "{\"video_id\":" + EvaluationVideoCanonEvidence.CANON_VIDEO + "}"),
                    new Member("read_result", "{}"));
            case "batch-group-invalid" -> List.of(
                    new Member("analyze_comments", "{}"),
                    new Member("analyze_video_content", "{\"video_id\":" + EvaluationVideoCanonEvidence.CANON_VIDEO + "}"));
            default -> throw new IllegalArgumentException("Unknown synthetic batch: " + scenario);
        };
        for (int i = 0; i < members.size(); i++) {
            ObjectNode call = calls.addObject();
            call.put("id", base + "-" + i);
            call.put("type", "function");
            ObjectNode function = call.putObject("function");
            function.put("name", members.get(i).name());
            function.put("arguments", members.get(i).arguments());
        }
        entry.put("synthetic_batch", scenario);
        entry.put("synthetic_batch_tools", members.stream().map(Member::name).toList());
        return JSON.writeValueAsString(payload);
    }

    static String syntheticAnalysisFailure(String scenario, byte[] raw, Map<String, Object> entry) throws Exception {
        JsonNode request = JSON.readTree(raw);
        JsonNode material = request;
        for (JsonNode message : request.path("messages"))
            if ("user".equals(message.path("role").asText()) && message.path("content").isTextual()) {
                try {
                    material = JSON.readTree(message.path("content").asText());
                } catch (Exception ignored) {
                }
            }
        String kind = material.path("kind").asText("");
        List<String> ids = new ArrayList<>();
        if ("MERGE".equals(kind)) {
            for (JsonNode child : material.path("children")) ids.add(child.path("id").asText());
        } else {
            for (JsonNode group : material.path("groups")) ids.add(group.path("id").asText());
        }
        ObjectNode content = JSON.createObjectNode();
        switch (scenario) {
            case "analysis-invalid-schema" -> {
                content.putObject("topics");
                content.putObject("assignments");
            }
            case "analysis-membership" -> {
                ObjectNode topic = content.putArray("topics").addObject();
                topic.put("key", "T1");
                topic.put("title", "主题");
                topic.put("observation", "观察");
                topic.put("recommendation", "");
                ObjectNode assignments = content.putObject("assignments");
                for (int i = 0; i < ids.size(); i += 2) assignments.put(ids.get(i), "T1");
            }
            case "analysis-narrative", "merge-representative-missing" -> {
                ObjectNode topic = content.putArray("topics").addObject();
                topic.put("key", "T1");
                topic.put("title", "主题");
                topic.put("observation", "analysis-narrative".equals(scenario) ? "作者共有999条发言" : "观察");
                topic.put("recommendation", "");
                ObjectNode assignments = content.putObject("assignments");
                for (String id : ids) assignments.put(id, "T1");
            }
            default -> throw new IllegalArgumentException("Unknown synthetic failure: " + scenario);
        }
        entry.put("synthetic_failure", scenario);
        entry.put("synthetic_members", ids.size());
        return completion("an-fail-" + entry.get("index"), String.valueOf(entry.get("model")),
                JSON.writeValueAsString(content), 900, 260, entry);
    }

    /**
     * Same-shape controller envelope whose single tool call is deliberately unusable. Only the call
     * shape differs from the normal script: {@code MISSING_NAME} leaves out the declared required
     * field (schema layer), {@code PAGING_WITHOUT_REFERENCE} passes the schema and is refused inside
     * the handler (paging a skill reference that was never named). No argument value is echoed into
     * the evidence beyond a fixed label.
     */
    static String syntheticToolCall(String scenario, String body, Map<String, Object> entry) throws Exception {
        ObjectNode payload = (ObjectNode) JSON.readTree(body);
        JsonNode call = payload.path("choices").path(0).path("message").path("tool_calls").path(0);
        if (!(call.path("function") instanceof ObjectNode function))
            throw new IllegalArgumentException("Controller envelope carries no tool call");
        String name = "read_skill";
        String arguments = "tool-handler-stop".equals(scenario)
                ? "{\"name\":\"audience-report\",\"offset\":10}" : "{}";
        function.put("name", name);
        function.put("arguments", arguments);
        entry.put("tool", name);
        entry.put("synthetic_tool", scenario);
        entry.put("synthetic_shape", "tool-handler-stop".equals(scenario) ? "PAGING_WITHOUT_REFERENCE" : "MISSING_NAME");
        return JSON.writeValueAsString(payload);
    }

    /**
     * Four hashes of the same request with the two message maps serialized in each role/content
     * order. Java Map.of iteration order is per-JVM randomized; equal byte length with a different
     * digest can therefore be a key-order difference, not a content difference. Hashes only.
     */
    static List<String> keyOrderVariants(byte[] raw) {
        try {
            JsonNode parsed = JSON.readTree(raw);
            if (!(parsed instanceof ObjectNode request) || !(request.get("messages") instanceof ArrayNode))
                return List.of();
            ArrayNode messages = (ArrayNode) request.get("messages");
            if (messages.size() != 2) return List.of();
            List<String> hashes = new ArrayList<>();
            for (int mask = 0; mask < 4; mask++) {
                ObjectNode copy = request.deepCopy();
                ArrayNode rows = (ArrayNode) copy.get("messages");
                for (int i = 0; i < 2; i++) {
                    if (!(rows.get(i) instanceof ObjectNode row)) return List.of();
                    ObjectNode ordered = JSON.createObjectNode();
                    if (((mask >> i) & 1) == 1) {
                        ordered.set("content", row.get("content"));
                        ordered.set("role", row.get("role"));
                    } else {
                        ordered.set("role", row.get("role"));
                        ordered.set("content", row.get("content"));
                    }
                    rows.set(i, ordered);
                }
                hashes.add(EvaluationVideoCanonEvidence.sha256(JSON.writeValueAsBytes(copy)));
            }
            return List.copyOf(hashes);
        } catch (Exception invalid) {
            return List.of();
        }
    }

    // ---------------------------------------------------------------- vision

    private static String vision(JsonNode request, Map<String, Object> entry) throws Exception {
        List<Long> atMs = new ArrayList<>();
        String content = "";
        for (JsonNode message : request.path("messages"))
            for (JsonNode part : message.path("content"))
                if ("text".equals(part.path("type").asText())) {
                    content += part.path("text").asText("") + "\n";
                } else if ("image_url".equals(part.path("type").asText())) {
                    entry.put("images", ((Number) entry.getOrDefault("images", 0)).intValue() + 1);
                    String url = part.path("image_url").path("url").asText("");
                    int comma = url.indexOf(',');
                    if (comma < 0) throw new IllegalStateException("vision image is not a data URL");
                    byte[] image = java.util.Base64.getDecoder().decode(url.substring(comma + 1));
                    @SuppressWarnings("unchecked")
                    List<String> digests = (List<String>) entry.computeIfAbsent("image_sha256", key -> new ArrayList<String>());
                    digests.add(EvaluationVideoCanonEvidence.sha256(image));
                }
        Matcher matcher = AT_MS.matcher(content);
        while (matcher.find()) atMs.add(Long.parseLong(matcher.group(1)));
        if (atMs.isEmpty()) throw new IllegalStateException("vision request carries no sampled timestamps");
        ObjectNode visual = JSON.createObjectNode();
        visual.put("summary", MARKER + "：12 个采样点显示一台白色相机机身、翻转屏与按键布局，"
                + "画面中的取景框与机身细节清晰可辨。");
        ArrayNode moments = visual.putArray("moments");
        for (long at : atMs) {
            ObjectNode moment = moments.addObject();
            moment.put("atMs", at);
            moment.put("observation", MARKER + " 在 " + at + "ms 的采样画面：白色相机机身置于桌面，屏幕显示取景框。");
        }
        String body = JSON.writeValueAsString(visual);
        entry.put("images", atMs.size());
        entry.put("sampled_at_ms", atMs);
        return completion("vis-" + entry.get("index"), VISION, body, 3000, 600, entry);
    }

    // ---------------------------------------------------------------- envelope

    private static String completion(String id, String model, String content, int prompt, int completion,
                                     Map<String, Object> entry) throws Exception {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("id", id);
        payload.put("model", model);
        payload.put("object", "chat.completion");
        ObjectNode choice = payload.putArray("choices").addObject();
        choice.put("index", 0);
        choice.put("finish_reason", "stop");
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        message.put("content", content);
        ObjectNode usage = payload.putObject("usage");
        usage.put("prompt_tokens", prompt);
        usage.put("completion_tokens", completion);
        entry.put("usage", Map.of("prompt_tokens", prompt, "completion_tokens", completion));
        return JSON.writeValueAsString(payload);
    }

}
