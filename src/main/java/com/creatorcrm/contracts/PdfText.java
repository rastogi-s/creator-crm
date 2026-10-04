package com.creatorcrm.contracts;

import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/** The text of a PDF. A scanned contract has no text layer and comes back (nearly) empty. */
final class PdfText {
    private PdfText() {}

    /** Long contracts are cut here; the terms that matter are almost always in the first pages. */
    static final int MAX_CHARS = 60_000;
    static final int MAX_PAGES = 40;

    static String extract(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setEndPage(Math.min(doc.getNumberOfPages(), MAX_PAGES));
            String text = stripper.getText(doc).replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n{3,}", "\n\n").strip();
            return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) + "\n[...the rest of the contract was cut]" : text;
        }
    }

    /** Fewer readable characters than this means a scan or an image-only PDF. */
    static boolean looksScanned(String text) {
        return text == null || text.replaceAll("\\s", "").length() < 200;
    }
}
