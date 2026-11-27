package com.tianmu.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.VideoContent;
import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.runtime.control.CancellationToken;
import com.tianmu.agent.video.VideoModelClient;
import com.tianmu.agent.video.VideoModelProperties;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 4B-1 vision/transcription provider contract checks against localhost doubles.
 *
 * Pins the two incompatible vision contracts so they cannot be silently mixed: the MiniMax
 * dedicated /v1/coding_plan/vlm body carries prompt + image_url and NO model field, while the
 * generic chat-vision body carries model + messages with image_url parts. Also pins the audio
 * transcription multipart contract and credential non-surfacing. Synthetic credential only.
 */
class VisionProviderContractTest {
    private static final String KEY = "synthetic-test-key";
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private int port;
    final List<JsonNode> received = new CopyOnWriteArrayList<>();
    final List<String> rawBodies = new CopyOnWriteArrayList<>();
    final List<String> paths = new CopyOnWriteArrayList<>();
    final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();
    final AtomicReference<String> mode = new AtomicReference<>("minimax_ok");
    VideoContent.Media media;
    CallControl control;

    /** A real 8x8 JPEG so the contact-sheet composer accepts the sampled frames. */
    static String jpegBase64(int rgb) throws Exception {
        var image = new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new java.awt.Color(rgb));
        graphics.fillRect(0, 0, 8, 8);
        graphics.dispose();
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "jpeg", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            paths.add(path);
            authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] raw = exchange.getRequestBody().readAllBytes();
            if (path.equals("/audio/transcriptions")) {
                rawBodies.add(new String(raw, StandardCharsets.UTF_8)); // multipart, not JSON
            } else {
                received.add(json.readTree(new String(raw, StandardCharsets.UTF_8)));
            }
            byte[] body;
            switch (mode.get()) {
                case "minimax_bad_status" -> body = "{\"base_resp\":{\"status_code\":1004},\"content\":\"\"}".getBytes(StandardCharsets.UTF_8);
                case "minimax_fenced" -> body = ("{\"base_resp\":{\"status_code\":0},\"content\":\"```json\\n{\\\"summary\\\":\\\"拼图\\\",\\\"moments\\\":[{\\\"atMs\\\":100,\\\"observation\\\":\\\"画面\\\"}]}\\n```\"}").getBytes(StandardCharsets.UTF_8);
                case "transcribe" -> body = "{\"text\":\"你好世界\"}".getBytes(StandardCharsets.UTF_8);
                case "generic_ok" -> body = ("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"summary\\\":\\\"通用\\\",\\\"moments\\\":[]}\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":3}}").getBytes(StandardCharsets.UTF_8);
                default -> body = "{\"base_resp\":{\"status_code\":0},\"content\":\"{\\\"summary\\\":\\\"专用\\\",\\\"moments\\\":[]}\"}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        port = server.getAddress().getPort();
        media = new VideoContent.Media(new VideoContent.Source(1001L, "v1", 10_000L), 0, 5_000,
                List.of(new VideoContent.Frame(100, jpegBase64(0xFF0000)),
                        new VideoContent.Frame(400, jpegBase64(0x00FF00))), "");
        control = new CallControl(Instant.now().plusSeconds(10), new CancellationToken());
    }

    @AfterEach void stop() {
        server.stop(0);
        mode.set("minimax_ok");
        received.clear();
        rawBodies.clear();
        paths.clear();
        authorizationHeaders.clear();
    }

    VideoModelClient minimaxClient() {
        // enabled=false skips the HTTPS-only production gate so tests can point at a loopback double;
        // VideoModelClient itself never reads the flag, transport behavior is what we pin here.
        return new VideoModelClient(new VideoModelProperties(false, "minimax",
                URI.create("http://127.0.0.1:" + port), KEY, "placeholder-model", "placeholder-audio"), json);
    }

    VideoModelClient compatibleClient() {
        return new VideoModelClient(new VideoModelProperties(false, "compatible",
                URI.create("http://127.0.0.1:" + port), KEY, "placeholder-model", ""), json);
    }

    @Test void minimaxDedicatedBodyCarriesPromptAndSheetWithoutGenericModelField() {
        var result = minimaxClient().understand(media, "外观", "", control);
        assertEquals("专用", result.path("summary").asText());
        JsonNode body = received.get(0);
        assertTrue(body.has("prompt"), "dedicated contract sends a prompt");
        assertTrue(body.has("image_url"), "dedicated contract sends the composed sheet");
        assertFalse(body.has("model"), "dedicated /v1/coding_plan/vlm must not carry a generic model field");
        assertFalse(body.has("messages"), "dedicated contract must not use chat messages");
        assertEquals("/v1/coding_plan/vlm", paths.get(0));
    }

    @Test void minimaxDedicatedStripsMarkdownFenceFromContent() {
        mode.set("minimax_fenced");
        var result = minimaxClient().understand(media, "外观", "", control);
        assertEquals("拼图", result.path("summary").asText());
        assertEquals(100, result.path("moments").path(0).path("atMs").asLong());
    }

    @Test void minimaxNonZeroStatusCodeIsControlledFailure() {
        mode.set("minimax_bad_status");
        var failure = assertThrows(AgentException.class, () -> minimaxClient().understand(media, "外观", "", control));
        assertEquals("VIDEO_MODEL_UNAVAILABLE", failure.code());
    }

    @Test void genericVisionBodyCarriesModelFieldAndRealImageParts() throws Exception {
        mode.set("generic_ok");
        var result = compatibleClient().understand(media, "外观", "", control);
        assertEquals("通用", result.path("summary").asText());
        JsonNode body = received.get(0);
        assertEquals("placeholder-model", body.path("model").asText(), "generic contract must carry the model field");
        var parts = body.path("messages").path(0).path("content");
        assertTrue(parts.isArray());
        int imageParts = 0;
        for (JsonNode part : parts) if (part.path("type").asText().equals("image_url")) imageParts++;
        assertEquals(media.frames().size(), imageParts, "every sampled frame must be sent as an image part");
        assertEquals("/chat/completions", paths.get(0));
    }

    @Test void transcriptionSendsMultipartWithModelAndFileParts() {
        mode.set("transcribe");
        String wav = Base64.getEncoder().encodeToString(new byte[]{0x52, 0x49, 0x46, 0x46});
        assertEquals("你好世界", minimaxClient().transcribe(wav, control));
        assertEquals("/audio/transcriptions", paths.get(0));
        String multipart = rawBodies.get(0);
        assertTrue(multipart.contains("name=\"model\""), "transcription multipart must carry the model part");
        assertTrue(multipart.contains("placeholder-audio"));
        assertTrue(multipart.contains("filename=\"audio.wav\""), "transcription multipart must carry the file part");
    }

    @Test void credentialSentOnWireNeverSurfacesInResultOrError() {
        mode.set("minimax_ok");
        var first = minimaxClient().understand(media, "外观", "", control);
        assertEquals("Bearer " + KEY, authorizationHeaders.get(0));
        assertFalse(first.toString().contains(KEY));
        mode.set("minimax_bad_status");
        var failure = assertThrows(AgentException.class, () -> minimaxClient().understand(media, "外观", "", control));
        assertFalse(failure.getMessage().contains(KEY));
    }
}
