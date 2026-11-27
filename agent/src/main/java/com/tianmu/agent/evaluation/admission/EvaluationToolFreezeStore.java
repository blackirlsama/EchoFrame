package com.tianmu.agent.evaluation.admission;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

/**
 * Immutable frozen-result store for the 4B-1 evaluation freeze (Java mirror of
 * scripts/report_evaluation/admission/toolfreeze.py FrozenStore). Keys are strict 64-hex digests;
 * reads and writes are anchored to the real store root (existing reparse points that resolve
 * outside the root are refused); the same key with the same bytes is an idempotent success while
 * the same key with different bytes is refused with the original payload and sidecar untouched;
 * a missing or mismatched sidecar makes the stored artifact unreadable until repaired. The
 * loopback replay endpoint serves exactly the stored bytes. Coverage level: this is the
 * evaluation-wiring freeze primitive; wiring it into the product tool runtime stays a later slice.
 */
public final class EvaluationToolFreezeStore {
    /** Readable-equivalent refusal for missing/corrupt artifacts; never a live fallback. */
    public static final class ToolFreezeMiss extends RuntimeException {
        public ToolFreezeMiss(String message) { super(message); }
    }

    /** Write refusals: invalid key, path escape, or same-key-different-bytes conflict. */
    public static final class FreezeWriteRefused extends RuntimeException {
        public FreezeWriteRefused(String message) { super(message); }
    }

    private static final Pattern KEY = Pattern.compile("[0-9a-f]{64}");
    private final Path root;

    public EvaluationToolFreezeStore(Path root) {
        try {
            Files.createDirectories(root);
            this.root = root.toRealPath();
        } catch (IOException error) {
            throw new FreezeWriteRefused("freeze store root is unavailable: " + root);
        }
    }

    public Path root() { return root; }

    private Path payloadPath(String key, boolean writing) {
        return containedPath(key, ".json", writing);
    }

    /** Both the payload AND the digest sidecar go through this: every artifact path (symlinks,
     * junctions, reparse points included) must resolve inside the real store root. */
    private Path containedPath(String key, String suffix, boolean writing) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw writing ? new FreezeWriteRefused("freeze keys must be 64 lowercase hex characters")
                : new ToolFreezeMiss("invalid freeze key");
        }
        Path file = root.resolve(key + suffix);
        assertContained(file, writing);
        return file;
    }

    private Path sidecarPath(String key, boolean writing) {
        return containedPath(key, ".sha256", writing);
    }

    private void assertContained(Path file, boolean writing) {
        RuntimeException refused = writing
                ? new FreezeWriteRefused("frozen artifact path is not contained in the store root")
                : new ToolFreezeMiss("frozen artifact path is not contained in the store root");
        // A dangling or outside-pointing link must be refused before any read/write follows it.
        if (Files.isSymbolicLink(file)) {
            throw refused;
        }
        try {
            if (Files.exists(file)) {
                Path real = file.toRealPath();
                if (!real.startsWith(root)) {
                    throw refused;
                }
            }
        } catch (IOException error) {
            throw refused;
        }
    }

    public byte[] get(String key) {
        Path file = payloadPath(key, false);
        if (!Files.isRegularFile(file)) throw new ToolFreezeMiss("no frozen result for key " + key);
        byte[] data;
        try {
            data = Files.readAllBytes(file);
            Path sidecar = sidecarPath(key, false);
            if (!Files.isRegularFile(sidecar)) {
                throw new ToolFreezeMiss("frozen result for " + key + " has no integrity sidecar");
            }
            String expected = Files.readString(sidecar, StandardCharsets.US_ASCII).strip();
            if (!expected.contentEquals(java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(data)))) {
                throw new ToolFreezeMiss("frozen result for " + key + " fails its integrity sidecar");
            }
            return data;
        } catch (ToolFreezeMiss error) {
            throw error;
        } catch (Exception error) {
            throw new ToolFreezeMiss("frozen result for " + key + " cannot be read");
        }
    }

    public void put(String key, byte[] data) {
        if (data == null || data.length == 0) {
            throw new FreezeWriteRefused("frozen artifacts are stored as non-empty bytes");
        }
        Path file = payloadPath(key, true);
        try {
            if (Files.isRegularFile(file)) {
                byte[] existing = Files.readAllBytes(file);
                if (!java.util.Arrays.equals(existing, data)) {
                    throw new FreezeWriteRefused("freeze key " + key + " already holds different bytes; "
                        + "the stored original was left untouched");
                }
                rewriteSidecar(key, data); // idempotent: same bytes, ensure the sidecar is correct
                return;
            }
            Path payloadTmp = root.resolve(key + ".json.tmp");
            assertContained(payloadTmp, true);
            Path sidecarTmp = root.resolve(key + ".sha256.tmp");
            assertContained(sidecarTmp, true);
            Path sidecar = sidecarPath(key, true);
            Files.write(payloadTmp, data);
            Files.writeString(sidecarTmp, java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(data)), StandardCharsets.US_ASCII);
            Files.move(payloadTmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.move(sidecarTmp, sidecar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (FreezeWriteRefused error) {
            throw error;
        } catch (Exception error) {
            throw new FreezeWriteRefused("frozen artifact could not be published atomically for " + key);
        }
    }

    private void rewriteSidecar(String key, byte[] data) {
        try {
            Path sidecar = sidecarPath(key, true);
            String expected = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(data));
            if (!Files.isRegularFile(sidecar) || !Files.readString(sidecar, StandardCharsets.US_ASCII).strip().equals(expected)) {
                // The repair branch stages through .sha256.tmp: that path needs the same
                // containment proof as the payload and the sidecar itself.
                Path tmp = containedPath(key, ".sha256.tmp", true);
                Files.writeString(tmp, expected, StandardCharsets.US_ASCII);
                Files.move(tmp, sidecar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (Exception error) {
            throw new FreezeWriteRefused("frozen sidecar could not be repaired for " + key);
        }
    }

    /** Loopback replay endpoint: GET /freeze/<key> serves the stored bytes verbatim, 409 on any refusal. */
    public HttpServer serveLoopback() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/freeze", exchange -> handleFreeze(exchange));
        server.start();
        return server;
    }

    private void handleFreeze(HttpExchange exchange) throws IOException {
        String key = exchange.getRequestURI().getPath().substring("/freeze/".length());
        byte[] body;
        try {
            body = get(key);
        } catch (RuntimeException refused) {
            exchange.sendResponseHeaders(409, -1);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
