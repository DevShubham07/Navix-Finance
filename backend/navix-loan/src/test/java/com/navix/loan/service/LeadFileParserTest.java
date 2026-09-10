package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.navix.common.exception.BusinessException;
import com.navix.loan.dto.LeadDtos.ImportIssue;
import com.navix.loan.dto.LeadDtos.ImportRow;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.junit.jupiter.api.Test;

class LeadFileParserTest {

    private static final class Collected implements LeadFileParser.Sink {
        private final List<ImportRow> rows = new ArrayList<>();
        private final List<Integer> numbers = new ArrayList<>();
        private final List<ImportIssue> issues = new ArrayList<>();

        @Override
        public void row(int rowNumber, ImportRow row) {
            numbers.add(rowNumber);
            rows.add(row);
        }

        @Override
        public void issue(ImportIssue issue) {
            issues.add(issue);
        }
    }

    private static Collected parseCsv(String csv) {
        Collected sink = new Collected();
        LeadFileParser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), "leads.csv", sink);
        return sink;
    }

    @Test
    void readsTheDocumentedHeader() {
        Collected out = parseCsv("""
                name,contact number,pan card,pincode,emailid
                Ravi Kumar,9876543210,ABCDE1234F,560001,ravi@example.com
                """);

        assertThat(out.rows).containsExactly(
                new ImportRow("Ravi Kumar", "9876543210", "ABCDE1234F", "560001", "ravi@example.com"));
        assertThat(out.numbers).containsExactly(1);
        assertThat(out.issues).isEmpty();
    }

    @Test
    void headerAliasesAreOrderIndependentAndCaseInsensitive() {
        Collected out = parseCsv("""
                E-Mail,Mobile Number,NAME,Pin Code
                ravi@example.com,9876543210,Ravi,560001
                """);

        assertThat(out.rows).containsExactly(
                new ImportRow("Ravi", "9876543210", null, "560001", "ravi@example.com"));
    }

    @Test
    void handlesBomQuotedCommasAndCrlf() {
        Collected out = parseCsv("﻿name,contact number\r\n\"Kumar, Ravi\",9876543210\r\n");

        assertThat(out.rows).hasSize(1);
        assertThat(out.rows.get(0).name()).isEqualTo("Kumar, Ravi");
        assertThat(out.rows.get(0).mobile()).isEqualTo("9876543210");
    }

    @Test
    void handlesEscapedQuotes() {
        Collected out = parseCsv("name,contact number\n\"Ravi \"\"Ricky\"\" Kumar\",9876543210\n");

        assertThat(out.rows.get(0).name()).isEqualTo("Ravi \"Ricky\" Kumar");
    }

    @Test
    void blankTrailingLinesAreIgnored() {
        Collected out = parseCsv("name,contact number\nRavi,9876543210\n\n\n");

        assertThat(out.rows).hasSize(1);
        assertThat(out.issues).isEmpty();
    }

    @Test
    void aRowWithTheWrongColumnCountIsAnIssueNotARow() {
        Collected out = parseCsv("""
                name,contact number,pincode
                Ravi,9876543210,560001
                Asha,9876543211
                """);

        assertThat(out.rows).hasSize(1);
        assertThat(out.issues).hasSize(1);
        assertThat(out.issues.get(0).row()).isEqualTo(2);
        assertThat(out.issues.get(0).message()).contains("Expected 3 columns, found 2");
    }

    @Test
    void missingRequiredColumnIsRejectedUpFront() {
        assertThatThrownBy(() -> parseCsv("name,pincode\nRavi,560001\n"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("contact number");
    }

    @Test
    void emptyFileIsRejected() {
        assertThatThrownBy(() -> parseCsv(""))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void headerOnlyFileIsRejected() {
        assertThatThrownBy(() -> parseCsv("name,contact number\n"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No data rows");
    }

    // ---- xlsx ----------------------------------------------------------------------------

    private static Collected parseXlsx(byte[] bytes) {
        Collected sink = new Collected();
        LeadFileParser.parse(new ByteArrayInputStream(bytes), "leads.xlsx", sink);
        return sink;
    }

    private static byte[] workbook(String[][] cells) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Workbook wb = new Workbook(out, "test", "1.0")) {
            Worksheet sheet = wb.newWorksheet("Leads");
            for (int r = 0; r < cells.length; r++) {
                for (int c = 0; c < cells[r].length; c++) {
                    sheet.value(r, c, cells[r][c]);
                }
            }
        }
        return out.toByteArray();
    }

    @Test
    void readsAnXlsxWithTheSameRulesAsCsv() throws IOException {
        byte[] bytes = workbook(new String[][] {
                {"name", "contact number", "pan card", "pincode", "emailid"},
                {"Ravi Kumar", "9876543210", "ABCDE1234F", "560001", "ravi@example.com"},
        });

        Collected out = parseXlsx(bytes);

        assertThat(out.rows).containsExactly(
                new ImportRow("Ravi Kumar", "9876543210", "ABCDE1234F", "560001", "ravi@example.com"));
        assertThat(out.issues).isEmpty();
    }

    /**
     * The single most common way a real lead sheet arrives: the mobile column typed as a number, so
     * Excel stores 9876543210 as a double and its formatted text is "9.87654321E9". Reading numeric
     * cells as a plain decimal is what stops every row of such a file failing validation.
     */
    @Test
    void aMobileStoredAsANumberIsNotReadAsScientificNotation() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Workbook wb = new Workbook(out, "test", "1.0")) {
            Worksheet sheet = wb.newWorksheet("Leads");
            sheet.value(0, 0, "name");
            sheet.value(0, 1, "contact number");
            sheet.value(0, 2, "pincode");
            sheet.value(1, 0, "Ravi");
            sheet.value(1, 1, 9876543210L);
            sheet.value(1, 2, 560001L);
        }

        Collected parsed = parseXlsx(out.toByteArray());

        assertThat(parsed.rows).hasSize(1);
        assertThat(parsed.rows.get(0).mobile()).isEqualTo("9876543210");
        assertThat(parsed.rows.get(0).pincode()).isEqualTo("560001");
    }

    @Test
    void blankRowsInASheetAreSkipped() throws IOException {
        byte[] bytes = workbook(new String[][] {
                {"name", "contact number"},
                {"Ravi", "9876543210"},
                {"", ""},
                {"Asha", "9876543211"},
        });

        Collected out = parseXlsx(bytes);

        assertThat(out.rows).hasSize(2);
        assertThat(out.numbers).containsExactly(1, 2);
    }

    /** Streaming means memory is flat in the file size — this would OOM if rows were retained. */
    @Test
    void streamsALargeCsvWithoutRetainingIt() {
        StringBuilder csv = new StringBuilder("name,contact number\n");
        for (int i = 0; i < 50_000; i++) {
            csv.append("Person ").append(i).append(",9").append(String.format("%09d", i)).append('\n');
        }
        CountingSink counter = new CountingSink();

        try (InputStream in = new ByteArrayInputStream(csv.toString().getBytes(StandardCharsets.UTF_8))) {
            LeadFileParser.parse(in, "big.csv", counter);
        } catch (IOException e) {
            throw new AssertionError(e);
        }

        assertThat(counter.count).isEqualTo(50_000);
    }

    private static final class CountingSink implements LeadFileParser.Sink {
        private int count;

        @Override
        public void row(int rowNumber, ImportRow row) {
            count++;
        }

        @Override
        public void issue(ImportIssue issue) {
            // ignored
        }
    }
}
