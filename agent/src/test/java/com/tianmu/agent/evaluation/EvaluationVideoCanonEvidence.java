package com.tianmu.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Batch 4 frozen Canon frames: the source manifest and JPEGs are a read-only copy of the formal
 * extraction, loaded once and verified byte-for-byte. The vision wire carries a deterministic
 * derivative (scale + JPEG quality) because the provider contract rejects a frame whose base64
 * exceeds 140,000 characters; both the source digest and the sent digest are recorded so the
 * derivative can never be confused with the frozen original.
 *
 * <p>Test-tree only. The evidence root defaults to {@code target/evaluation/offline-canon} and
 * may be relocated with {@code -Deval.evidence.root=...}; a path outside the selected root is
 * refused, so an earlier run's reports and ledger cannot be overwritten.
 */
final class EvaluationVideoCanonEvidence {
    private EvaluationVideoCanonEvidence() {}

    private static final String DEFAULT_ROOT = Path.of("target", "evaluation", "offline-canon").toString();

    static final Path ROOT = Path.of(System.getProperty("eval.evidence.root", DEFAULT_ROOT))
            .toAbsolutePath().normalize();
    static final Path FRAME_ROOT = ROOT.resolve("frames");
    static final Path MANIFEST = FRAME_ROOT.resolve("frames-manifest.json");
    static final Path AUDIT = ROOT.resolve("frames-audit.json");
    static final Path LEDGER = ROOT.resolve("batch-cumulative.json");
    static final Path COMPLETED = ROOT.resolve("completed");
    static final Path STOP = ROOT.resolve("stop");
    static final Path B_ATTEMPT = ROOT.resolve("b-attempt.json");
    static final Path A_STOP_PROOF = ROOT.resolve("a-stop-proof.json");
    static final long CANON_VIDEO = 730013L;
    static final int MAX_BASE64 = 139_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    record Frame(int index, long atMs, byte[] jpeg, String sourceSha, String sentSha,
                 int sentBytes, int sentBase64, double scale, float quality) {}

    private static List<Frame> loaded;

    /** Copies the frozen frame source into this batch's evidence root exactly once and verifies
     *  every byte. The source is read-only; an existing target is verified, never rewritten. */
    static synchronized void prepareFrames() {
        Path source = frameSource();
        JsonNode manifest = readManifest(source.resolve("frames-manifest.json"), source);
        if (Files.exists(FRAME_ROOT)) {
            verifyTarget(manifest, source);
        } else {
            try {
                Files.createDirectories(FRAME_ROOT);
                Files.copy(source.resolve("frames-manifest.json"), MANIFEST,
                        java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                for (JsonNode row : manifest.path("frames")) {
                    Path from = source.resolve(row.path("path").asText()).normalize();
                    Path to = FRAME_ROOT.resolve(row.path("path").asText()).normalize();
                    if (!to.startsWith(FRAME_ROOT))
                        throw new IllegalStateException("frame outside the target root: " + to);
                    Files.createDirectories(to.getParent());
                    Files.copy(from, to, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                }
            } catch (IOException error) {
                throw new IllegalStateException("cannot copy the frozen frames into " + FRAME_ROOT, error);
            }
            verifyTarget(manifest, source);
        }
        loaded = null;
    }

    /** Verifies the batch's existing frames against its manifest; writes nothing. */
    static synchronized void verifyFrames() {
        JsonNode manifest = readManifest(MANIFEST, FRAME_ROOT);
        if (Files.exists(FRAME_ROOT)) verifyTarget(manifest, FRAME_ROOT);
        else if (manifest.path("frames").size() != 12)
            throw new IllegalStateException("the formal manifest must carry exactly 12 frames");
    }

    private static Path frameSource() {
        String raw = System.getProperty("eval.frames.source", "").strip();
        if (raw.isEmpty())
            throw new IllegalStateException("EVAL_FRAMES_SOURCE_REQUIRED: pass -Deval.frames.source=<frozen frames dir>");
        Path source = Path.of(raw).toAbsolutePath().normalize();
        if (!Files.isDirectory(source))
            throw new IllegalStateException("the frozen frame source does not exist: " + source);
        if (source.startsWith(ROOT) || ROOT.startsWith(source))
            throw new IllegalStateException("the frame source and evidence root must not overlap: " + source);
        rejectReparse(source);
        return source;
    }

    private static JsonNode readManifest(Path file, Path root) {
        try {
            JsonNode manifest = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (manifest.path("frames").size() != 12)
                throw new IllegalStateException("the formal manifest must carry exactly 12 frames");
            for (JsonNode row : manifest.path("frames")) {
                Path frame = root.resolve(row.path("path").asText()).normalize();
                if (!frame.startsWith(root))
                    throw new IllegalStateException("frame outside the frozen root: " + frame);
                if (!Files.isRegularFile(frame))
                    throw new IllegalStateException("frozen frame missing: " + frame);
                byte[] bytes = Files.readAllBytes(frame);
                if (bytes.length != row.path("bytes").asInt())
                    throw new IllegalStateException("frozen frame size changed: " + frame);
                if (!sha256(bytes).equals(row.path("sha256").asText()))
                    throw new IllegalStateException("frozen frame digest changed: " + frame);
            }
            return manifest;
        } catch (IOException error) {
            throw new IllegalStateException("frozen frame manifest unreadable: " + file, error);
        }
    }

    private static void verifyTarget(JsonNode sourceManifest, Path sourceRoot) {
        JsonNode targetManifest = readManifest(MANIFEST, FRAME_ROOT);
        if (!targetManifest.equals(sourceManifest))
            throw new IllegalStateException("the batch frame manifest differs from the frozen source");
        // The source path is still verified, so a caller cannot point at a different directory
        // after the batch was prepared.
        if (!sourceRoot.equals(FRAME_ROOT)) verifyFramesAtSource(sourceManifest, sourceRoot);
    }

    private static void verifyFramesAtSource(JsonNode manifest, Path sourceRoot) {
        for (JsonNode row : manifest.path("frames")) {
            Path frame = sourceRoot.resolve(row.path("path").asText()).normalize();
            if (!frame.startsWith(sourceRoot) || !Files.isRegularFile(frame))
                throw new IllegalStateException("frozen frame missing: " + frame);
            try {
                byte[] bytes = Files.readAllBytes(frame);
                if (bytes.length != row.path("bytes").asInt()
                        || !sha256(bytes).equals(row.path("sha256").asText()))
                    throw new IllegalStateException("frozen frame changed: " + frame);
            } catch (IOException error) {
                throw new IllegalStateException("frozen frame unreadable: " + frame, error);
            }
        }
    }

    private static void rejectReparse(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        for (Path cursor = absolute; cursor != null; cursor = cursor.getParent()) {
            if (Files.isSymbolicLink(cursor))
                throw new IllegalStateException("a symbolic link is not allowed in the evidence path: " + cursor);
        }
    }


    static synchronized List<Frame> frames() {
        if (loaded != null) return loaded;
        try {
            JsonNode manifest = JSON.readTree(Files.readString(MANIFEST, StandardCharsets.UTF_8));
            var list = new ArrayList<Frame>();
            for (JsonNode row : manifest.path("frames")) {
                int index = row.path("index").asInt();
                long atMs = row.path("at_ms").asLong();
                Path file = FRAME_ROOT.resolve(row.path("path").asText()).normalize();
                if (!file.startsWith(FRAME_ROOT)) throw new IllegalStateException("frame outside the frozen root: " + file);
                byte[] source = Files.readAllBytes(file);
                String sourceSha = sha256(source);
                if (!sourceSha.equals(row.path("sha256").asText()))
                    throw new IllegalStateException("frozen frame digest changed: " + file);
                if (source.length != row.path("bytes").asInt())
                    throw new IllegalStateException("frozen frame size changed: " + file);
                list.add(derive(index, atMs, source, sourceSha));
            }
            if (list.size() != manifest.path("frames").size() || list.size() != 12)
                throw new IllegalStateException("the formal manifest must carry exactly 12 frames");
            loaded = List.copyOf(list);
            writeAudit(manifest, loaded);
            return loaded;
        } catch (IOException error) {
            throw new IllegalStateException("frozen Canon frames unreadable: " + FRAME_ROOT, error);
        }
    }

    static String sourceVersion() {
        try {
            return JSON.readTree(Files.readString(MANIFEST, StandardCharsets.UTF_8))
                    .path("source").path("video_sha256").asText();
        } catch (IOException error) {
            throw new IllegalStateException("frozen manifest unreadable", error);
        }
    }

    static long durationMs() {
        try {
            return JSON.readTree(Files.readString(MANIFEST, StandardCharsets.UTF_8))
                    .path("source").path("duration_ms").asLong();
        } catch (IOException error) {
            throw new IllegalStateException("frozen manifest unreadable", error);
        }
    }

    static Frame derive(int index, long atMs, byte[] source, String sourceSha) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(source));
        if (image == null) throw new IllegalStateException("frozen frame is not a decodable JPEG: index " + index);
        double scale = Math.min(1.0, 720.0 / Math.max(image.getWidth(), image.getHeight()));
        float quality = 0.75f;
        byte[] sent = encode(image, scale, quality);
        for (int attempt = 0; attempt < 10 && base64Length(sent) > MAX_BASE64; attempt++) {
            if (attempt % 2 == 0) quality = Math.max(0.4f, quality - 0.1f);
            else scale = scale * 0.8;
            sent = encode(image, scale, quality);
        }
        if (base64Length(sent) > MAX_BASE64)
            throw new IllegalStateException("cannot bound the sent frame below the provider limit: index " + index);
        return new Frame(index, atMs, sent, sourceSha, sha256(sent), sent.length, base64Length(sent), scale, quality);
    }

    private static byte[] encode(BufferedImage source, double scale, float quality) throws IOException {
        int width = Math.max(2, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(2, (int) Math.round(source.getHeight() * scale));
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam params = writer.getDefaultWriteParam();
        params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        params.setCompressionQuality(quality);
        var buffer = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(buffer)) {
            writer.setOutput(output);
            writer.write(null, new IIOImage(target, null, null), params);
        } finally {
            writer.dispose();
        }
        return buffer.toByteArray();
    }

    private static int base64Length(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes).length();
    }

    private static void writeAudit(JsonNode manifest, List<Frame> frames) throws IOException {
        ObjectNode audit = JSON.createObjectNode();
        audit.put("kind", "offline-frame-audit");
        audit.put("manifest", FRAME_ROOT.relativize(MANIFEST).toString().replace('\\', '/'));
        audit.put("video_id", CANON_VIDEO);
        audit.put("source_version", manifest.path("source").path("video_sha256").asText());
        audit.put("duration_ms", manifest.path("source").path("duration_ms").asLong());
        audit.put("derivation", "scale<=720px + JPEG quality, until base64<=140000 (provider limit)");
        ArrayNode rows = audit.putArray("frames");
        for (Frame frame : frames) {
            ObjectNode row = rows.addObject();
            row.put("index", frame.index());
            row.put("at_ms", frame.atMs());
            row.put("source_sha256", frame.sourceSha());
            row.put("sent_sha256", frame.sentSha());
            row.put("sent_bytes", frame.sentBytes());
            row.put("sent_base64_chars", frame.sentBase64());
            row.put("scale", frame.scale());
            row.put("quality", frame.quality());
        }
        if (Files.exists(AUDIT)) {
            // The first process owns the audit; later processes verify the frames against the
            // manifest and never rewrite or delete this file.
            return;
        }
        Files.writeString(AUDIT, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(audit),
                StandardCharsets.UTF_8);
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            var out = new StringBuilder();
            for (byte value : digest) out.append(String.format("%02x", value));
            return out.toString();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    /** Refuses any evidence path that is not inside this batch's own root. */
    static Path requireOwned(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(ROOT))
            throw new IllegalStateException("the evidence must stay inside " + ROOT + ": " + normalized);
        rejectReparse(normalized);
        return normalized;
    }

    /** A new batch must name an explicit root. An absent or empty root is created exactly once;
     *  any existing content is refused instead of being deleted or overwritten. */
    static void requireFreshBatchRoot(Path root) throws IOException {
        String raw = System.getProperty("eval.evidence.root", "").strip();
        if (raw.isEmpty())
            throw new IllegalStateException("EVAL_EVIDENCE_ROOT_REQUIRED: pass -Deval.evidence.root=<new batch>");
        Path normalized = root.toAbsolutePath().normalize();
        rejectReparse(normalized);
        if (Files.exists(normalized)) {
            if (!Files.isDirectory(normalized))
                throw new IllegalStateException("EVIDENCE_ROOT_OCCUPIED: not a directory: " + normalized);
            try (var files = Files.list(normalized)) {
                for (Path entry : files.toList()) {
                    if (entry.getFileName().toString().equals("freeze") && Files.isDirectory(entry)) {
                        try (var freeze = Files.list(entry)) {
                            if (freeze.findAny().isEmpty()) continue;
                        }
                    }
                    throw new IllegalStateException("EVIDENCE_ROOT_OCCUPIED: " + normalized
                            + " already contains " + entry.getFileName() + "; choose a new root");
                }
            }
        }
    }

    static Map<String, Object> summary() {
        return Map.of("frames", frames().size(),
                "video_id", CANON_VIDEO,
                "source_version", sourceVersion(),
                "duration_ms", durationMs());
    }
}
