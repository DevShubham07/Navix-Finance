package com.navix.loan.pdf;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;

/** Marks generated PDFs in the training environment. A no-op unless {@code NAVIX_ENV=sim}. */
final class SimulationStamp {

    static final String LABEL = "SIMULATION - NOT A REAL DOCUMENT";
    private static final Font FONT = new Font(Font.HELVETICA, 11, Font.BOLD, new Color(178, 34, 34));

    private SimulationStamp() {
    }

    static void apply(PdfWriter writer) {
        apply(writer, System.getenv("NAVIX_ENV"));
    }

    static void apply(PdfWriter writer, String env) {
        if (!"sim".equalsIgnoreCase(env)) {
            return;
        }
        writer.setPageEvent(new PdfPageEventHelper() {
            @Override
            public void onEndPage(PdfWriter w, Document d) {
                ColumnText.showTextAligned(w.getDirectContent(), Element.ALIGN_CENTER, new Phrase(LABEL, FONT),
                        d.getPageSize().getWidth() / 2, d.getPageSize().getHeight() - 14, 0);
            }
        });
    }
}
