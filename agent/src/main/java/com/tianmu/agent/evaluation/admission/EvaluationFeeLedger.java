package com.tianmu.agent.evaluation.admission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Java mirror of the Python fee admission ledger, format fee-ledger-v2 (integer fen; 1 CNY = 100
 * fen). Single writer per ledger file: construction takes an OS-level exclusive lock on the
 * adjacent .lock file before reading; a second instance or process is refused with LedgerBusy until
 * the owner closes. Every reservation freezes its pricing identity (exit key, model, per-million
 * price snapshot and its SHA-256) at admit time, so a later reopen with a different price table can
 * never silently re-price old requests. Semantics: reservation precedes dispatch; the dispatch's
 * own response settles it; missing/malformed usage is USAGE_UNKNOWN (full reservation retained,
 * tokens null, all further paid dispatch blocked); timeouts/cancellations/failures keep the
 * reservation (FAILED_RETAINED); a retry is a fresh admission; the pause line (20 CNY = 2000 fen)
 * refuses any admission whose conservative total would reach it; the hard limit (50 CNY = 5000 fen)
 * refuses anything past it; both providers share one envelope; arithmetic is overflow-exact
 * (multiplyExact) and overflow is a controlled refusal. Corrupted ledger files are refused in full
 * with their bytes untouched. Image exits: grounded-budget semantics (v3, 2026-11-19) — the
 * registered MiniMax identity is validated first, then the pre-pay reservation prices the
 * GROUNDED estimate (measured 202-token request floor + 1000-char prompt envelope + the
 * documented 600/image estimate, anchored by the two authorized real metering requests whose
 * derived frame cost is 338 tokens); the dedicated coding-plan VLM transport and audio stay
 * refused; settlement re-prices from the real response usage. 4B-2A content repair (2026-11-19):
 * each reservation can carry the serialized request's byte count and SHA-256, bound at the
 * client's send boundary before dispatch (bindRequestDigest), and an envelope can carry prior
 * rounds' verified spend as a top-level carried_in block that reopens survive (a restart cannot
 * clear it). Evaluation wiring only; never armed in production.
 */
public final class EvaluationFeeLedger implements EvaluationFeeGate.Admission, AutoCloseable {
    public static class Blocked extends com.tianmu.agent.core.AgentException {
        public Blocked(String message) { super("FEE_ADMISSION_BLOCKED", message); }
    }

    public static final class Corrupted extends RuntimeException {
        public Corrupted(String message) { super(message); }
    }

    public static final class LedgerBusy extends Blocked {
        public LedgerBusy(String message) { super(message); }
    }

    /** Fen per million tokens, input and output. */
    public record Price(long inputFenPerMillion, long outputFenPerMillion) {}

    private final class Handle implements EvaluationFeeGate.Reservation {
        private final String id;

        Handle(String id) { this.id = id; }

        @Override public String reservationId() { return id; }

        @Override public void settle(JsonNode providerResponse) {
            EvaluationFeeLedger.this.settle(id, providerResponse);
        }

        @Override public void fail(String reason) { EvaluationFeeLedger.this.fail(id, reason); }

        @Override public void bindRequestDigest(long bodyBytes, String bodySha256) {
            EvaluationFeeLedger.this.bindRequestDigest(id, bodyBytes, bodySha256);
        }
    }

    private static final String SCHEMA = "fee-ledger-v2";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OPEN = "OPEN";
    private static final String SETTLED = "SETTLED";
    private static final String USAGE_UNKNOWN = "USAGE_UNKNOWN";
    private static final String FAILED_RETAINED = "FAILED_RETAINED";
    /** Refuse absurd estimates before any arithmetic: tool-scale arguments never reach this. */
    private static final long TOKEN_ESTIMATE_CEILING = 1_000_000_000_000L;
    /** Registered image contract identity (mirrors the Python contract registry). Grounded-budget
     * constants (v3, 2026-11-19): the request floor and prompt envelope come from the two
     * authorized real metering requests (one formal frame + text = 540 prompt tokens; identical
     * text alone = 202), and the per-image figure is the provider's documented detail=low
     * estimate ("最高约 600") anchored by the derived 338 tokens for the measured frame. */
    static final String VISION_PROVIDER = "minimax";
    static final String VISION_TRANSPORT = "openai_chat_vision";
    static final String VISION_MODEL = "MiniMax-M3";
    static final int VISION_REQUEST_TEXT_FLOOR_MEASURED = 202;
    static final int VISION_PROMPT_CHARS_MAX = 1000;
    static final int VISION_PER_IMAGE_TOKENS = 600;
    static final int VISION_MAX_IMAGES = 16;
    static final int VISION_MAX_OUTPUT_TOKENS = 2048;

    private final Path file;
    private final long totalFen;
    private final long pauseFen;
    private final Map<String, Price> prices = new LinkedHashMap<>();
    private final List<JsonNode> reservations = new ArrayList<>();
    /** 4B-2A content-repair: prior-round spend carried INTO this batch envelope. Recorded as a
     *  top-level block (never a fake reservation, so the strict dispatch binding stays untouched),
     *  counted by conservativeUsedFen, and reloaded on every reopen — a restart cannot clear it. */
    private CarriedIn carriedIn;
    private BatchCap batchCap;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private boolean closed;

    /** Prior-round fees a new batch envelope starts with. Amounts are the file-verified figures
     *  (still pending provider reconciliation); the source names the evidence root they came from.
     *  {@code rowsSha256} is an optional canonical digest over the historical reservation rows:
     *  when present, a reopen refuses any rewrite of those rows, even one that keeps their sum. */
    public record CarriedIn(String source, long settledFen, long retainedFen, String rowsSha256, int rowsCount) {
        public CarriedIn(String source, long settledFen, long retainedFen) {
            this(source, settledFen, retainedFen, null, 0);
        }
        public CarriedIn(String source, long settledFen, long retainedFen, String rowsSha256) {
            this(source, settledFen, retainedFen, rowsSha256, 0);
        }
        public CarriedIn {
            if (source == null || source.isBlank()) throw new IllegalArgumentException("carried-in source required");
            if (settledFen < 0 || retainedFen < 0) throw new IllegalArgumentException("carried-in amounts must be non-negative");
            if (rowsSha256 != null && !rowsSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("carried-in row digest must be 64 lowercase hex");
            if (rowsSha256 == null && rowsCount != 0 || rowsSha256 != null && rowsCount < 1)
                throw new IllegalArgumentException("carried-in row count must match its digest");
        }
    }

    /** 4B-2A live-readiness repair: an INDEPENDENT cap on THIS batch's NEW spend (conservative
     *  used minus the carried-in total), enforced separately from the cumulative pause line —
     *  a batch can hit its own ceiling while the cumulative total is still under the pause
     *  line. Persisted as a top-level block like carried_in, so a restart cannot lift it. */
    public record BatchCap(String source, long newSpendCapFen) {
        public BatchCap {
            if (source == null || source.isBlank()) throw new IllegalArgumentException("batch-cap source required");
            if (newSpendCapFen < 1) throw new IllegalArgumentException("batch new-spend cap must be positive");
        }
    }

    public EvaluationFeeLedger(Path file, long totalFen, long pauseFen, Map<String, Price> prices) {
        this(file, totalFen, pauseFen, prices, null, null);
    }

    public EvaluationFeeLedger(Path file, long totalFen, long pauseFen, Map<String, Price> prices,
            CarriedIn carried) {
        this(file, totalFen, pauseFen, prices, carried, null);
    }

    public EvaluationFeeLedger(Path file, long totalFen, long pauseFen, Map<String, Price> prices,
            CarriedIn carried, BatchCap batchCap) {
        if (totalFen <= 0) throw new IllegalArgumentException("totalFen must be positive");
        if (pauseFen <= 0 || pauseFen > totalFen) throw new IllegalArgumentException("pauseFen out of range");
        this.carriedIn = carried;
        this.batchCap = batchCap;
        this.file = file;
        this.totalFen = totalFen;
        this.pauseFen = pauseFen;
        prices.forEach((key, price) -> {
            if (price.inputFenPerMillion() < 0 || price.outputFenPerMillion() < 0) {
                throw new IllegalArgumentException("prices must be non-negative: " + key);
            }
            this.prices.put(key, price);
        });
        // Single writer per ledger file: the OS lock is taken before the first byte is read, so the
        // loaded state and every later judgment come from the one writer that owns the file.
        Path lockPath = file.resolveSibling(file.getFileName() + ".lock");
        try {
            lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE);
            lock = lockChannel.tryLock();
        } catch (java.nio.channels.OverlappingFileLockException | IOException error) {
            // OverlappingFileLockException covers a second instance inside this JVM; a null lock
            // covers a writer in another process. Both mean: this file already has an owner.
            throw new LedgerBusy("another writer owns the fee ledger; a second instance is refused: " + file);
        }
        if (lock == null) {
            try { lockChannel.close(); } catch (IOException ignored) { }
            throw new LedgerBusy("another writer owns the fee ledger; a second instance is refused: " + file);
        }
        if (Files.isRegularFile(file)) load();
    }

    @Override public synchronized void close() {
        closed = true;
        try {
            if (lock != null) lock.release();
        } catch (IOException ignored) { }
        try { lockChannel.close(); } catch (IOException ignored) { }
    }

    private synchronized void ensureOpen() {
        if (closed) throw new Blocked("FEE_ADMISSION_REFUSED: the ledger owner has closed this file");
    }

    private void load() {
        String raw;
        try {
            raw = Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new Corrupted("fee ledger file is unreadable; dispatch stays blocked: " + file);
        }
        JsonNode doc;
        try {
            doc = JSON.readTree(raw);
        } catch (Exception e) {
            throw new Corrupted("fee ledger file is not valid JSON; a human must repair it: " + file);
        }
        if (!doc.isObject() || !SCHEMA.equals(doc.path("schema").asText(""))
                || !isNonNegativeInt(doc.path("total_fen")) || doc.path("total_fen").asLong() != totalFen
                || !isNonNegativeInt(doc.path("pause_fen")) || doc.path("pause_fen").asLong() != pauseFen
                || !doc.path("reservations").isArray()) {
            throw new Corrupted("fee ledger file does not match " + SCHEMA + " identity: " + file);
        }
        // The carried-in and batch-cap blocks, once written, are file state: a reopen without the
        // same identity keeps the persisted blocks, a mismatched re-declaration is refused in full.
        if (doc.hasNonNull("carried_in")) {
            JsonNode block = doc.path("carried_in");
            CarriedIn persisted = new CarriedIn(block.path("source").asText(""),
                    block.path("settled_fen").asLong(-1), block.path("retained_fen").asLong(-1),
                    block.hasNonNull("rows_sha256") ? block.path("rows_sha256").asText("") : null,
                    block.path("rows_count").asInt(0));
            if (persisted.settledFen() < 0 || persisted.retainedFen() < 0
                    || persisted.source().isBlank()) {
                throw new Corrupted("fee ledger carried_in block is malformed: " + file);
            }
            if (carriedIn != null && (!persisted.source().equals(carriedIn.source())
                    || persisted.settledFen() != carriedIn.settledFen()
                    || persisted.retainedFen() != carriedIn.retainedFen()
                    || !Objects.equals(persisted.rowsSha256(), carriedIn.rowsSha256())
                    || persisted.rowsCount() != carriedIn.rowsCount())) {
                throw new Corrupted("fee ledger carried_in does not match this opener's declaration: " + file);
            }
            carriedIn = persisted;
        }
        if (doc.hasNonNull("batch_cap")) {
            JsonNode block = doc.path("batch_cap");
            BatchCap persisted = new BatchCap(block.path("source").asText(""),
                    block.path("new_spend_cap_fen").asLong(-1));
            if (persisted.newSpendCapFen() < 1 || persisted.source().isBlank()) {
                throw new Corrupted("fee ledger batch_cap block is malformed: " + file);
            }
            if (batchCap != null && (!persisted.source().equals(batchCap.source())
                    || persisted.newSpendCapFen() != batchCap.newSpendCapFen())) {
                throw new Corrupted("fee ledger batch_cap does not match this opener's declaration: " + file);
            }
            batchCap = persisted;
        }
        Set<String> seenIds = new HashSet<>();
        for (JsonNode node : doc.path("reservations")) {
            validateReservation(node, seenIds);
            reservations.add(node);
        }
        if (carriedIn != null && carriedIn.rowsSha256() != null) {
            if (carriedIn.rowsCount() > reservations.size())
                throw new Corrupted("fee ledger carried_in row count exceeds the file: " + file);
            if (!carriedIn.rowsSha256().equals(rowsDigest(reservations.subList(0, carriedIn.rowsCount()))))
                throw new Corrupted("fee ledger historical rows were rewritten; dispatch stays blocked: " + file);
        }
    }

    /**
     * Canonical digest over the historical reservation rows. Field order is fixed and shared with
     * the Python verifier; null/absent is {@code -}, rows are separated by LF and fields by US.
     * Any value change (including one that keeps the batch sum) changes this digest.
     */
    public static String rowsDigest(List<JsonNode> rows) {
        String[] fields = {"reservation_id", "exit", "model", "status", "reserved_fen",
                "usage_tokens", "actual_fen", "price_identity", "price_input_fen_per_million",
                "price_output_fen_per_million", "attribution", "admitted_at",
                "request_body_bytes", "request_body_sha256", "fail_reason"};
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (JsonNode row : rows) {
                for (String field : fields) {
                    JsonNode value = row == null ? null : row.get(field);
                    String text = value == null || value.isNull() ? "-" : value.asText();
                    digest.update(text.getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0x1f);
                }
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Limited complete field table for one persisted reservation; any violation is corruption. */
    private static void validateReservation(JsonNode node, Set<String> seenIds) {
        if (!node.isObject()) throw new Corrupted("reservation: must be an object");
        String id = node.path("reservation_id").asText("");
        if (id.isEmpty()) throw new Corrupted("reservation.reservation_id: must be a non-empty string");
        if (!seenIds.add(id)) throw new Corrupted("reservation.reservation_id: duplicate reservation id");
        if (node.path("exit").asText("").isEmpty()) throw new Corrupted("reservation.exit: must be a non-empty string");
        String status = node.path("status").asText("");
        if (!Set.of(OPEN, SETTLED, USAGE_UNKNOWN, FAILED_RETAINED).contains(status)) {
            throw new Corrupted("reservation.status: unknown status");
        }
        if (!isNonNegativeInt(node.path("reserved_fen"))) {
            throw new Corrupted("reservation.reserved_fen: must be a non-negative integer");
        }
        JsonNode usage = node.path("usage_tokens");
        JsonNode actual = node.path("actual_fen");
        boolean settled = SETTLED.equals(status);
        if (settled) {
            if (!isNonNegativeInt(usage) || !isNonNegativeInt(actual)) {
                throw new Corrupted("reservation SETTLED: usage_tokens and actual_fen must be non-negative integers");
            }
        } else {
            if (!usage.isNull() || !actual.isNull()) {
                throw new Corrupted("reservation " + status + ": usage_tokens and actual_fen must be null");
            }
            if (FAILED_RETAINED.equals(status) && node.path("fail_reason").asText("").isEmpty()) {
                throw new Corrupted("reservation FAILED_RETAINED: fail_reason must be a non-empty string");
            }
        }
        if (node.path("price_identity").asText("").length() != 64) {
            throw new Corrupted("reservation.price_identity: must be a 64-hex string");
        }
        if (!isNonNegativeInt(node.path("price_input_fen_per_million"))
                || !isNonNegativeInt(node.path("price_output_fen_per_million"))) {
            throw new Corrupted("reservation price snapshot: must be non-negative integers");
        }
        if (node.path("attribution").asText("").isEmpty() || node.path("admitted_at").asText("").isEmpty()) {
            throw new Corrupted("reservation.attribution/admitted_at: must be non-empty strings");
        }
        // 4B-2A request digest binding (optional on legacy records): when present, both halves
        // must be valid — a half-bound digest is corruption, not partial evidence.
        boolean hasBytes = node.has("request_body_bytes");
        boolean hasDigest = node.has("request_body_sha256");
        if (hasBytes != hasDigest) {
            throw new Corrupted("reservation request digest: bytes and sha256 must be bound together");
        }
        if (hasBytes && (!isNonNegativeInt(node.path("request_body_bytes"))
                || node.path("request_body_bytes").asLong() < 1
                || !node.path("request_body_sha256").asText("").matches("[0-9a-f]{64}"))) {
            throw new Corrupted("reservation request digest: byte count must be positive and sha256 64-hex");
        }
    }

    private static boolean isNonNegativeInt(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= 0;
    }

    private synchronized void save() {
        try {
            ObjectNode doc = JSON.createObjectNode();
            doc.put("schema", SCHEMA);
            doc.put("total_fen", totalFen);
            doc.put("pause_fen", pauseFen);
            if (carriedIn != null) {
                ObjectNode carried = doc.putObject("carried_in");
                carried.put("source", carriedIn.source());
                carried.put("settled_fen", carriedIn.settledFen());
                carried.put("retained_fen", carriedIn.retainedFen());
                if (carriedIn.rowsSha256() != null) {
                    carried.put("rows_sha256", carriedIn.rowsSha256());
                    carried.put("rows_count", carriedIn.rowsCount());
                }
            }
            if (batchCap != null) {
                ObjectNode cap = doc.putObject("batch_cap");
                cap.put("source", batchCap.source());
                cap.put("new_spend_cap_fen", batchCap.newSpendCapFen());
            }
            ArrayNode nodes = doc.putArray("reservations");
            reservations.forEach(nodes::add);
            byte[] bytes = JSON.writeValueAsBytes(doc);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            throw new Corrupted("fee ledger could not be persisted: " + file);
        }
    }

    private synchronized Price priceFor(String exitKey) {
        Price price = prices.get(exitKey);
        if (price == null) {
            throw new Blocked("FEE_ADMISSION_NEEDS_PRICING: no verified price table entry for exit "
                + exitKey + "; unknown pricing is never treated as zero");
        }
        return price;
    }

    private static long ceilDiv(long value, long divisor) { return -Math.floorDiv(-value, divisor); }

    private long reservedFen(Price price, long estIn, long estOut) {
        try {
            long fen = Math.addExact(ceilDiv(Math.multiplyExact(estIn, price.inputFenPerMillion()), 1_000_000L),
                ceilDiv(Math.multiplyExact(estOut, price.outputFenPerMillion()), 1_000_000L));
            return Math.max(1, fen);
        } catch (ArithmeticException error) {
            throw new Blocked("FEE_ADMISSION_REFUSED: estimate arithmetic overflows the fee domain");
        }
    }

    public synchronized long conservativeUsedFen() {
        long total = 0;
        for (JsonNode r : reservations) {
            if (SETTLED.equals(r.path("status").asText())) {
                total += r.path("actual_fen").asLong(0);
            } else {
                total += r.path("reserved_fen").asLong(0);
            }
        }
        if (carriedIn != null) total = Math.addExact(total, carriedIn.settledFen() + carriedIn.retainedFen());
        return total;
    }

    public synchronized long knownSettledFen() {
        long total = 0;
        for (JsonNode r : reservations) {
            if (SETTLED.equals(r.path("status").asText())) {
                total += r.path("actual_fen").asLong(0);
            }
        }
        if (carriedIn != null) total = Math.addExact(total, carriedIn.settledFen());
        return total;
    }

    /** Carried-in block as loaded/persisted; null when this envelope carries nothing. */
    public synchronized CarriedIn carriedIn() { return carriedIn == null ? null : new CarriedIn(carriedIn.source(), carriedIn.settledFen(), carriedIn.retainedFen(), carriedIn.rowsSha256(), carriedIn.rowsCount()); }

    /** Batch new-spend cap as loaded/persisted; null when this envelope declares none. */
    public synchronized BatchCap batchCap() { return batchCap == null ? null : new BatchCap(batchCap.source(), batchCap.newSpendCapFen()); }

    /** THIS batch's new spend so far (conservative used minus the carried-in total). */
    public synchronized long batchNewSpendFen() {
        long carriedTotal = carriedIn == null ? 0 : carriedIn.settledFen() + carriedIn.retainedFen();
        return conservativeUsedFen() - carriedTotal;
    }

    /** 4B-2A content-repair: persist the serialized request digest onto the reservation record.
     *  One bind only; a differing re-bind is corruption and refuses the whole ledger. The digest
     *  must be 64 lowercase hex (a SHA-256 as the clients compute it) and the byte count positive. */
    public synchronized void bindRequestDigest(String reservationId, long bodyBytes, String bodySha256) {
        ensureOpen();
        if (bodyBytes < 1) throw new Blocked("FEE_ADMISSION_REFUSED: request digest needs a positive byte count");
        if (bodySha256 == null || !bodySha256.matches("[0-9a-f]{64}")) {
            throw new Blocked("FEE_ADMISSION_REFUSED: request digest must be 64 hex characters");
        }
        ObjectNode record = record(reservationId);
        if (record.has("request_body_sha256") || record.has("request_body_bytes")) {
            boolean same = record.path("request_body_sha256").asText("").equals(bodySha256)
                    && record.path("request_body_bytes").asLong(-1) == bodyBytes;
            if (!same) throw new Blocked("FEE_ADMISSION_REFUSED: reservation " + reservationId
                    + " is already bound to a different request digest");
            return;
        }
        record.put("request_body_bytes", bodyBytes);
        record.put("request_body_sha256", bodySha256);
        save();
    }

    private synchronized ObjectNode admit(String exitKey, String model, long estIn, long estOut, String attribution) {
        ensureOpen();
        if (estIn < 1 || estOut < 1 || estIn > TOKEN_ESTIMATE_CEILING || estOut > TOKEN_ESTIMATE_CEILING) {
            throw new Blocked("FEE_ADMISSION_REFUSED: token estimates must be positive and within the fee domain");
        }
        if (hasUnknownPending()) {
            throw new Blocked("FEE_ADMISSION_BLOCKED: an unknown-usage reservation is pending; "
                + "dispatch stays blocked until a human resolves it");
        }
        Price price = priceFor(exitKey);
        long reserved = reservedFen(price, estIn, estOut);
        long used = conservativeUsedFen();
        if (used + reserved > totalFen) {
            throw new Blocked("FEE_ADMISSION_BUDGET_EXHAUSTED: admission would push the conservative "
                + "total to " + (used + reserved) + " fen beyond the " + totalFen + "-fen limit");
        }
        if (used + reserved >= pauseFen) {
            throw new Blocked("FEE_ADMISSION_PAUSED: admission would reach the " + pauseFen
                + "-fen pause line (conservative total " + (used + reserved) + " fen); paused for review");
        }
        // The batch's OWN new-spend ceiling, independent of the cumulative pause line: even with
        // the cumulative total still under the pause line, this batch's new spend may not pass
        // its own declared cap.
        if (batchCap != null) {
            long carriedTotal = carriedIn == null ? 0 : carriedIn.settledFen() + carriedIn.retainedFen();
            long newSpend = used - carriedTotal + reserved;
            if (newSpend > batchCap.newSpendCapFen()) {
                throw new Blocked("FEE_ADMISSION_BATCH_CAPPED: admission would push this batch's new "
                    + "spend to " + newSpend + " fen beyond its " + batchCap.newSpendCapFen()
                    + "-fen batch cap (cumulative total " + (used + reserved)
                    + " fen is still under the pause line); paused for review");
            }
        }
        ObjectNode record = JSON.createObjectNode();
        String id = "fee-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        record.put("reservation_id", id);
        record.put("exit", exitKey);
        record.put("model", model == null ? "" : model);
        record.put("status", OPEN);
        record.put("reserved_fen", reserved);
        record.putNull("usage_tokens");
        record.putNull("actual_fen");
        record.put("price_identity", priceIdentity(exitKey, price));
        record.put("price_input_fen_per_million", price.inputFenPerMillion());
        record.put("price_output_fen_per_million", price.outputFenPerMillion());
        record.put("attribution", attribution);
        record.put("admitted_at", java.time.Instant.now().toString());
        reservations.add(record);
        save();
        return record;
    }

    private static String priceIdentity(String exitKey, Price price) {
        String basis = exitKey + "|" + price.inputFenPerMillion() + "|" + price.outputFenPerMillion();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(basis.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) out.append(Character.forDigit((b >> 4) & 0xF, 16))
                .append(Character.forDigit(b & 0xF, 16));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Override public EvaluationFeeGate.Reservation admitChat(String model, String host, long payloadBytes, int maxTokens) {
        // Exit key carries the concrete request model: two controllers on one provider may differ in price.
        String exitKey = "chat:" + host + ":" + model;
        ObjectNode record = admit(exitKey, model,
            Math.max(1, payloadBytes), Math.max(1, maxTokens), "direct");
        return new Handle(record.path("reservation_id").asText());
    }

    @Override public EvaluationFeeGate.Reservation admitVideo(String provider, String path) {
        // The dedicated coding-plan VLM contract and the audio transcription exit expose no verified
        // billing unit or per-call ceiling, so an armed gate refuses them outright.
        throw new Blocked("FEE_ADMISSION_NEEDS_PRICING: the vision/audio billing unit is unverified for "
            + provider + " " + path + "; dispatch refused until a provider-confirmed unit exists");
    }

    /**
     * Stage 4B-2A image exit, grounded-budget semantics (v3, 2026-11-19). Registration is still
     * by identity AND protocol: the supplier must be MiniMax, the transport the generic
     * OpenAI-compatible image input, and the request name the one whose behaviour is documented;
     * anything else keeps its specific refusal. The reservation basis is the GROUNDED estimate —
     * measured request floor 202 + declared prompt envelope 1000 chars (chars>=tokens) + the
     * documented 600/image estimate — never the transport bytes (that was the withdrawn
     * price-refresh bug). Settlement always re-prices from the real response usage, so the
     * reservation stays a ceiling, not a bill.
     */
    @Override public EvaluationFeeGate.Reservation admitVision(String provider, String transport, String path,
            String model, int imageCount, long payloadBytes, int maxOutputTokens) {
        String refusal = null;
        if (!VISION_PROVIDER.equals(provider)) {
            refusal = "provider " + provider + " is not the registered vision provider";
        } else if (!VISION_TRANSPORT.equals(transport)) {
            refusal = "transport " + transport + " is not the registered image contract";
        } else if (!VISION_MODEL.equals(model)) {
            refusal = "request name " + model + " has no reproducible token bound";
        } else if (imageCount < 1 || imageCount > VISION_MAX_IMAGES) {
            refusal = "image count " + imageCount + " is outside 1.." + VISION_MAX_IMAGES;
        } else if (payloadBytes < 1) {
            refusal = "payload bytes must be positive";
        } else if (maxOutputTokens < 1 || maxOutputTokens > VISION_MAX_OUTPUT_TOKENS) {
            refusal = "output cap " + maxOutputTokens + " is outside 1.." + VISION_MAX_OUTPUT_TOKENS;
        }
        if (refusal != null) {
            throw new Blocked("FEE_ADMISSION_NEEDS_PRICING: " + refusal + " (" + provider + " " + transport
                + " " + path + "); dispatch refused");
        }
        long input;
        try {
            input = Math.addExact(VISION_REQUEST_TEXT_FLOOR_MEASURED + (long) VISION_PROMPT_CHARS_MAX,
                Math.multiplyExact((long) imageCount, VISION_PER_IMAGE_TOKENS));
        } catch (ArithmeticException overflow) {
            throw new Blocked("FEE_ADMISSION_REFUSED: vision estimate overflows the fee domain");
        }
        String exitKey = "vision:" + provider + ":" + model + ":" + transport;
        ObjectNode record = admit(exitKey, model, input, maxOutputTokens, "vision");
        return new Handle(record.path("reservation_id").asText());
    }

    public synchronized void settle(String reservationId, JsonNode response) {
        ensureOpen();
        ObjectNode record = record(reservationId);
        if (!OPEN.equals(record.path("status").asText())) {
            throw new Blocked("FEE_ADMISSION_DUPLICATE_SETTLEMENT: reservation " + reservationId
                + " is " + record.path("status").asText());
        }
        JsonNode usage = response == null ? JSON.createObjectNode().path("usage") : response.path("usage");
        JsonNode prompt = usage.path("prompt_tokens");
        JsonNode completion = usage.path("completion_tokens");
        if (!isTokenCount(prompt) || !isTokenCount(completion)) {
            record.put("status", USAGE_UNKNOWN);
            record.putNull("usage_tokens");
            save();
            throw new Blocked("FEE_ADMISSION_USAGE_UNKNOWN: usage missing or malformed for "
                + reservationId + "; the full reservation is retained and paid dispatch is blocked");
        }
        long promptTokens = prompt.asLong();
        long completionTokens = completion.asLong();
        long actual;
        try {
            actual = Math.max(1, Math.addExact(
                ceilDiv(Math.multiplyExact(promptTokens, record.path("price_input_fen_per_million").asLong()), 1_000_000L),
                ceilDiv(Math.multiplyExact(completionTokens, record.path("price_output_fen_per_million").asLong()), 1_000_000L)));
        } catch (ArithmeticException error) {
            record.put("status", USAGE_UNKNOWN);
            record.putNull("usage_tokens");
            save();
            throw new Blocked("FEE_ADMISSION_USAGE_UNKNOWN: settlement arithmetic overflows for " + reservationId
                + "; the reservation is retained and paid dispatch is blocked");
        }
        record.put("status", SETTLED);
        record.put("usage_tokens", Math.addExact(promptTokens, completionTokens));
        record.put("actual_fen", actual);
        save();
    }

    public synchronized void fail(String reservationId, String reason) {
        ensureOpen();
        ObjectNode record = record(reservationId);
        if (!OPEN.equals(record.path("status").asText())) {
            throw new Blocked("FEE_ADMISSION_REFUSED: reservation " + reservationId + " is not open");
        }
        record.put("status", FAILED_RETAINED);
        record.put("fail_reason", reason == null || reason.isBlank() ? "unspecified" : reason);
        save();
    }

    public synchronized JsonNode reservation(String reservationId) { return record(reservationId).deepCopy(); }

    public synchronized List<JsonNode> openReservations() {
        List<JsonNode> open = new ArrayList<>();
        for (JsonNode r : reservations) if (OPEN.equals(r.path("status").asText())) open.add(r.deepCopy());
        return open;
    }

    public synchronized boolean canDispatch() {
        ensureOpen();
        return !hasUnknownPending();
    }

    private synchronized boolean hasUnknownPending() {
        return reservations.stream().anyMatch(r -> USAGE_UNKNOWN.equals(r.path("status").asText()));
    }

    private ObjectNode record(String reservationId) {
        for (JsonNode r : reservations) {
            if (r.path("reservation_id").asText("").equals(reservationId) && r.isObject()) {
                return (ObjectNode) r;
            }
        }
        throw new Blocked("FEE_ADMISSION_REFUSED: unknown reservation " + reservationId);
    }

    private static boolean isTokenCount(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= 0;
    }
}
