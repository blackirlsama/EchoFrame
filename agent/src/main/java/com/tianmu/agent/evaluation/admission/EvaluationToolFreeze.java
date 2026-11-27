package com.tianmu.agent.evaluation.admission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Java half of the freeze-key-v2 canonicalization: the same deterministic bytes as
 * scripts/report_evaluation/admission/toolfreeze.py. Spec v2 defines the exact number domain so
 * the same logical request derives one key on both sides: integers (any magnitude JSON carries)
 * as plain digits; floats normalized to one form (integral values become integer digits, other
 * finite values become "first-digit[.tail]e±two-digit-exponent" with trailing zeros stripped;
 * -0.0 normalizes to 0; non-finite values and magnitudes outside [1e-30, 1e30] are refused).
 * Object keys are sorted; strings use each language's standard JSON escaping (identical for the
 * BMP domain this spec serves). The v1 golden vector (integer/string payloads) is byte-identical
 * under v2 and stays asserted in tests; payloads carrying floats must use v2 (none exist in
 * formal archives; freeze seeds regenerate via admission-prepare).
 */
public final class EvaluationToolFreeze {
    public static final String KEY_SPEC_VERSION = "freeze-key-v2";
    private static final ObjectMapper JSON = new ObjectMapper();

    private EvaluationToolFreeze() {}

    /** sha256 over the canonical UTF-8 bytes of the payload. */
    public static String key(JsonNode payload) {
        try {
            byte[] canonical = canonicalJson(payload).getBytes(StandardCharsets.UTF_8);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return out.toString();
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("freeze key payload is not serializable", error);
        }
    }

    /** Byte-for-byte canonical form, for diagnostics and cross-language verification. */
    public static String canonicalJson(JsonNode payload) {
        StringBuilder out = new StringBuilder();
        emit(payload, out);
        return out.toString();
    }

    private static void emit(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull()) { out.append("null"); return; }
        if (node.isBoolean()) { out.append(node.asBoolean() ? "true" : "false"); return; }
        if (node.isTextual()) { out.append(encodeString(node.asText())); return; }
        if (node.isInt() || node.isLong() || node.isBigInteger()) { out.append(node.asText()); return; }
        if (node.isDouble() || node.isFloat()) { out.append(canonicalNumber(node.asDouble())); return; }
        if (node.isBigDecimal() || node.isBinary() || node.isPojo() || node.isMissingNode()) {
            throw new IllegalArgumentException("freeze key payload contains a value outside the v2 domain");
        }
        if (node.isObject()) {
            out.append('{');
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            java.util.Collections.sort(names);
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) out.append(',');
                out.append(encodeString(names.get(i))).append(':');
                emit(node.get(names.get(i)), out);
            }
            out.append('}');
            return;
        }
        if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) out.append(',');
                emit(node.get(i), out);
            }
            out.append(']');
            return;
        }
        throw new IllegalArgumentException("freeze key payload contains an unsupported node type");
    }

    private static String encodeString(String value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalArgumentException("unencodable string in freeze key payload");
        }
    }

    /** Deterministic number form; see the class contract for the domain and the edge rules. */
    static String canonicalNumber(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("freeze key number domain: non-finite values are refused");
        }
        if (value == 0.0) return "0"; // covers -0.0 by value
        double abs = Math.abs(value);
        if (abs < 1e-30 || abs > 1e30) {
            throw new IllegalArgumentException("freeze key number domain: magnitude outside [1e-30,1e30]");
        }
        String text = Double.toString(value);
        boolean negative = text.startsWith("-");
        if (negative) text = text.substring(1);
        int eSplit = text.indexOf('E');
        int exp;
        String mantissa;
        if (eSplit < 0) {
            exp = 0;
            mantissa = text;
        } else {
            exp = Integer.parseInt(text.substring(eSplit + 1).replace("+", ""));
            mantissa = text.substring(0, eSplit);
        }
        int dot = mantissa.indexOf('.');
        String digits;
        if (dot < 0) {
            digits = mantissa;
        } else {
            digits = mantissa.substring(0, dot) + mantissa.substring(dot + 1);
            exp -= mantissa.length() - dot - 1;
        }
        int start = 0;
        while (start < digits.length() - 1 && digits.charAt(start) == '0') start++;
        digits = digits.substring(start);
        while (digits.length() > 1 && digits.endsWith("0")) {
            digits = digits.substring(0, digits.length() - 1);
            exp += 1;
        }
        StringBuilder out = new StringBuilder();
        if (negative) out.append('-');
        if (exp >= 0) {
            out.append(digits);
            for (int i = 0; i < exp; i++) out.append('0');
            return out.toString();
        }
        out.append(digits.charAt(0));
        if (digits.length() > 1) out.append('.').append(digits.substring(1));
        out.append('e').append(exp < 0 ? '-' : '+');
        int magnitude = Math.abs(exp);
        if (magnitude < 10) out.append('0');
        out.append(magnitude);
        return out.toString();
    }
}
