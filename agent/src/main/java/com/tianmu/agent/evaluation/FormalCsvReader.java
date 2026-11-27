package com.tianmu.agent.evaluation;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal read-only RFC4180 CSV reader (BOM, quoted fields, embedded commas/newlines) for the
 * formal material files. No writes; malformed structure fails explicitly instead of skipping. */
final class FormalCsvReader {
    private FormalCsvReader() {}

    static List<Map<String, String>> read(Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<String> header = parseRecord(skipBom(reader));
            if (header.isEmpty()) throw new IllegalStateException("empty CSV: " + file);
            List<Map<String, String>> rows = new ArrayList<>();
            List<String> record;
            while (!(record = parseRecord(reader)).isEmpty()) {
                if (record.size() != header.size()) {
                    throw new IllegalStateException("CSV field count mismatch at " + file
                            + " line " + rows.size() + 2 + ": " + record.size() + " vs " + header.size());
                }
                var row = new LinkedHashMap<String, String>();
                for (int i = 0; i < header.size(); i++) row.put(header.get(i), record.get(i));
                rows.add(row);
            }
            return rows;
        } catch (IOException error) {
            throw new IllegalStateException("formal material file unreadable: " + file, error);
        }
    }

    private static BufferedReader skipBom(BufferedReader reader) throws IOException {
        reader.mark(1);
        int first = reader.read();
        if (first != 0xFEFF) reader.reset();
        return reader;
    }

    private static List<String> parseRecord(BufferedReader reader) throws IOException {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false, anyField = false;
        int c;
        while ((c = reader.read()) != -1) {
            if (inQuotes) {
                if (c == '"') {
                    reader.mark(1);
                    int next = reader.read();
                    if (next == '"') { field.append('"'); anyField = true; }
                    else { if (next != -1 && next != '\r' && next != '\n') reader.reset(); inQuotes = false; anyField = true; }
                } else { field.append((char) c); anyField = true; }
                continue;
            }
            if (c == '"') { inQuotes = true; anyField = true; continue; }
            if (c == ',') { fields.add(field.toString()); field.setLength(0); anyField = true; continue; }
            if (c == '\r') continue;
            if (c == '\n') {
                if (!anyField && field.length() == 0 && fields.isEmpty()) continue; // blank line
                fields.add(field.toString());
                return fields;
            }
            field.append((char) c);
            anyField = true;
        }
        if (anyField || field.length() > 0 || !fields.isEmpty()) {
            fields.add(field.toString());
            return fields;
        }
        return List.of(); // end of file
    }
}
