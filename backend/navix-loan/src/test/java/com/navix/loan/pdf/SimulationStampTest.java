package com.navix.loan.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import com.lowagie.text.Document;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

class SimulationStampTest {

    private static String firstPageText(String env) throws Exception {
        Document doc = new Document(PageSize.A4);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SimulationStamp.apply(PdfWriter.getInstance(doc, out), env);
        doc.open();
        doc.add(new Paragraph("Body"));
        doc.close();
        PdfReader reader = new PdfReader(out.toByteArray());
        String text = new PdfTextExtractor(reader).getTextFromPage(1);
        reader.close();
        return text;
    }

    @Test
    void stampsPagesInTheSimulationEnvironment() throws Exception {
        assertThat(firstPageText("sim")).contains(SimulationStamp.LABEL);
    }

    @Test
    void leavesOtherEnvironmentsUnchanged() throws Exception {
        assertThat(firstPageText("dev")).doesNotContain(SimulationStamp.LABEL);
        assertThat(firstPageText("prod")).doesNotContain(SimulationStamp.LABEL);
        assertThat(firstPageText(null)).doesNotContain(SimulationStamp.LABEL);
    }
}
