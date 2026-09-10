package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.loan.dto.LeadDtos.ImportIssue;
import com.navix.loan.dto.LeadDtos.ImportRow;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.dhatim.fastexcel.reader.Cell;
import org.dhatim.fastexcel.reader.CellType;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;

/**
 * Reads an uploaded lead list — {@code .csv} or {@code .xlsx} — and hands rows to a {@link Sink}
 * ONE AT A TIME.
 *
 * <p>Streaming is the whole point. A 200k-row list is ~12 MB of CSV, but the old browser-side path
 * turned it into four full in-memory copies (raw text, {@code string[][]}, {@code ImportRow[]}, then
 * the JSON body) and the server then held every row again. Nothing here retains more than the row it
 * is on, so the import's memory ceiling is set by the caller's chunk size, not by the file.
 *
 * <p>This class does STRUCTURAL work only: find the columns, check the column count, pull the cells.
 * Every field rule (name required, mobile shape, PAN/pincode/email validity) stays in
 * {@link LeadImportService#normalizeRow} so there is exactly one definition of "is this row valid",
 * shared by both formats.
 */
public final class LeadFileParser {

    /** Receives the parsed file. A row and an issue are mutually exclusive per source line. */
    public interface Sink {
        /** @param rowNumber 1-based DATA row (the header is row 0), matching {@link ImportIssue#row()}. */
        void row(int rowNumber, ImportRow row);

        void issue(ImportIssue issue);
    }

    private enum Field {
        NAME, MOBILE, PAN, PINCODE, EMAIL
    }

    /**
     * Accepted header spellings, kept identical to the frontend contract this replaces so a file that
     * worked before still works. Matching is case-insensitive, underscore- and whitespace-insensitive,
     * and order-independent.
     */
    private static final Map<Field, List<String>> HEADER_ALIASES = new EnumMap<>(Field.class);

    static {
        HEADER_ALIASES.put(Field.NAME, List.of("name"));
        HEADER_ALIASES.put(Field.MOBILE, List.of("contact number", "contact", "mobile", "phone", "mobile number"));
        HEADER_ALIASES.put(Field.PAN, List.of("pan card", "pan", "pan number"));
        HEADER_ALIASES.put(Field.PINCODE, List.of("pincode", "pin code", "pin", "postal code"));
        HEADER_ALIASES.put(Field.EMAIL, List.of("emailid", "email id", "email", "e-mail"));
    }

    public static final String EXPECTED_HEADER = "name, contact number, pan card, pincode, emailid";

    private LeadFileParser() {
    }

    /** True when the filename looks like a spreadsheet rather than a CSV. */
    public static boolean isExcel(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".xlsx") || lower.endsWith(".xlsm");
    }

    /**
     * Parse {@code in} according to {@code fileName}'s extension. Structural failures (empty file,
     * missing required column, unreadable workbook) throw {@link BusinessException}; per-row failures
     * go to {@link Sink#issue}, so one bad line no longer condemns the whole file.
     */
    public static void parse(InputStream in, String fileName, Sink sink) {
        if (isExcel(fileName)) {
            parseExcel(in, sink);
        } else {
            parseCsv(in, sink);
        }
    }

    // ---- CSV ------------------------------------------------------------------------------

    private static void parseCsv(InputStream in, Sink sink) {
        try (Reader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16)) {
            CsvReader csv = new CsvReader(reader);
            List<String> header = csv.next();
            if (header == null) {
                throw new BusinessException("IMPORT_FILE_EMPTY", "The file is empty.");
            }
            Map<Field, Integer> columns = mapColumns(header);
            int headerLen = header.size();

            int rowNumber = 0;
            List<String> cells;
            while ((cells = csv.next()) != null) {
                if (isBlankRecord(cells)) {
                    continue;
                }
                rowNumber++;
                if (cells.size() != headerLen) {
                    sink.issue(new ImportIssue(rowNumber, "row",
                            "Expected " + headerLen + " columns, found " + cells.size() + "."));
                    continue;
                }
                sink.row(rowNumber, toImportRow(columns, cells::get));
            }
            if (rowNumber == 0) {
                throw new BusinessException("IMPORT_FILE_EMPTY", "No data rows found.");
            }
        } catch (IOException e) {
            throw new BusinessException("IMPORT_FILE_UNREADABLE", "Could not read the file: " + e.getMessage());
        }
    }

    /**
     * RFC-4180 over a {@link Reader}: quoted fields, {@code ""} escapes, CRLF/LF, a leading BOM
     * stripped. Reads one record at a time — it never holds the document.
     */
    private static final class CsvReader {

        private final Reader reader;
        private int pushback = -2;
        private boolean atStart = true;

        CsvReader(Reader reader) {
            this.reader = reader;
        }

        /** The next record, or null at end of input. */
        List<String> next() throws IOException {
            int c = read();
            if (c == -1) {
                return null;
            }
            if (atStart) {
                atStart = false;
                if (c == 0xFEFF) {          // BOM
                    c = read();
                    if (c == -1) {
                        return null;
                    }
                }
            }

            List<String> record = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean inQuotes = false;

            while (c != -1) {
                char ch = (char) c;
                if (inQuotes) {
                    if (ch == '"') {
                        int peek = read();
                        if (peek == '"') {
                            field.append('"');
                        } else {
                            inQuotes = false;
                            pushback = peek;
                        }
                    } else {
                        field.append(ch);
                    }
                } else if (ch == '"') {
                    inQuotes = true;
                } else if (ch == ',') {
                    record.add(field.toString());
                    field.setLength(0);
                } else if (ch == '\n') {
                    record.add(field.toString());
                    return record;
                } else if (ch == '\r') {
                    int peek = read();
                    if (peek != '\n') {
                        pushback = peek;
                    }
                    record.add(field.toString());
                    return record;
                } else {
                    field.append(ch);
                }
                c = read();
            }
            record.add(field.toString());
            return record;
        }

        private int read() throws IOException {
            if (pushback != -2) {
                int c = pushback;
                pushback = -2;
                return c;
            }
            return reader.read();
        }
    }

    // ---- XLSX -----------------------------------------------------------------------------

    private static void parseExcel(InputStream in, Sink sink) {
        try (ReadableWorkbook workbook = new ReadableWorkbook(in)) {
            Sheet sheet = workbook.getFirstSheet();
            if (sheet == null) {
                throw new BusinessException("IMPORT_FILE_EMPTY", "The workbook has no sheets.");
            }
            try (Stream<Row> rows = sheet.openStream()) {
                ExcelState state = new ExcelState();
                rows.forEach(row -> handleExcelRow(row, state, sink));
                if (state.columns == null) {
                    throw new BusinessException("IMPORT_FILE_EMPTY", "The file is empty.");
                }
                if (state.dataRows == 0) {
                    throw new BusinessException("IMPORT_FILE_EMPTY", "No data rows found.");
                }
            }
        } catch (IOException e) {
            throw new BusinessException("IMPORT_FILE_UNREADABLE",
                    "Could not read the spreadsheet: " + e.getMessage());
        } catch (UncheckedIOException e) {
            throw new BusinessException("IMPORT_FILE_UNREADABLE",
                    "Could not read the spreadsheet: " + e.getCause().getMessage());
        }
    }

    private static final class ExcelState {
        private Map<Field, Integer> columns;
        private int headerLen;
        private int dataRows;
    }

    private static void handleExcelRow(Row row, ExcelState state, Sink sink) {
        if (state.columns == null) {
            List<String> header = new ArrayList<>(row.getCellCount());
            for (int i = 0; i < row.getCellCount(); i++) {
                header.add(cellText(row, i));
            }
            if (isBlankRecord(header)) {
                return;             // leading blank rows above the header are common in hand-made sheets
            }
            state.columns = mapColumns(header);
            state.headerLen = header.size();
            return;
        }

        List<String> cells = new ArrayList<>(state.headerLen);
        for (int i = 0; i < state.headerLen; i++) {
            cells.add(cellText(row, i));
        }
        if (isBlankRecord(cells)) {
            return;
        }
        state.dataRows++;
        sink.row(state.dataRows, toImportRow(state.columns, cells::get));
    }

    /**
     * Excel stores a phone number typed without a leading apostrophe as a NUMBER, and the formatted
     * text of one is routinely {@code 9.87654321E9}. Reading numeric cells as a plain decimal is what
     * keeps a spreadsheet of mobiles from arriving as scientific notation and failing every row.
     */
    private static String cellText(Row row, int index) {
        if (index >= row.getCellCount()) {
            return "";
        }
        Cell cell = row.getCell(index);
        if (cell == null || cell.getType() == CellType.EMPTY) {
            return "";
        }
        if (cell.getType() == CellType.NUMBER) {
            BigDecimal value = cell.asNumber();
            return value == null ? "" : value.stripTrailingZeros().toPlainString();
        }
        String text = cell.getText();
        return text == null ? "" : text.trim();
    }

    // ---- shared ---------------------------------------------------------------------------

    private interface Cells {
        String at(int index);
    }

    private static ImportRow toImportRow(Map<Field, Integer> columns, Cells cells) {
        return new ImportRow(
                value(columns, cells, Field.NAME),
                value(columns, cells, Field.MOBILE),
                value(columns, cells, Field.PAN),
                value(columns, cells, Field.PINCODE),
                value(columns, cells, Field.EMAIL));
    }

    /** Raw trimmed cell — every field RULE lives in {@code LeadImportService.normalizeRow}. */
    private static String value(Map<Field, Integer> columns, Cells cells, Field field) {
        Integer index = columns.get(field);
        if (index == null) {
            return null;
        }
        String raw = cells.at(index);
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Map<Field, Integer> mapColumns(List<String> header) {
        List<String> normalized = new ArrayList<>(header.size());
        for (String cell : header) {
            normalized.add(normalizeHeaderCell(cell));
        }

        Map<Field, Integer> columns = new EnumMap<>(Field.class);
        for (Map.Entry<Field, List<String>> entry : HEADER_ALIASES.entrySet()) {
            for (int i = 0; i < normalized.size(); i++) {
                if (entry.getValue().contains(normalized.get(i))) {
                    columns.put(entry.getKey(), i);
                    break;
                }
            }
        }

        List<String> missing = new ArrayList<>();
        if (!columns.containsKey(Field.NAME)) {
            missing.add("name");
        }
        if (!columns.containsKey(Field.MOBILE)) {
            missing.add("contact number");
        }
        if (!missing.isEmpty()) {
            throw new BusinessException("IMPORT_HEADER_INVALID",
                    "Missing required column(s): " + String.join(", ", missing)
                            + ". Expected header: " + EXPECTED_HEADER);
        }
        return columns;
    }

    private static String normalizeHeaderCell(String header) {
        if (header == null) {
            return "";
        }
        return header.trim().toLowerCase(Locale.ROOT).replace('_', ' ').replaceAll("\\s+", " ");
    }

    private static boolean isBlankRecord(List<String> cells) {
        for (String cell : cells) {
            if (cell != null && !cell.isBlank()) {
                return false;
            }
        }
        return true;
    }
}
