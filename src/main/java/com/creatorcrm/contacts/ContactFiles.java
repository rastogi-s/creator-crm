package com.creatorcrm.contacts;

import com.creatorcrm.llm.ContactCards.Card;
import com.creatorcrm.llm.LlmClient;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Turns any file she has into a grid of contacts for the import preview: spreadsheets (CSV, Excel), contact cards
 * (.vcf), PDFs and pictures (business cards, screenshots). Everything is read with plain code on this computer;
 * Claude reads a picture only when she presses the button for it, after seeing the price.
 */
@Service
public class ContactFiles {
    private static final Logger log = LoggerFactory.getLogger(ContactFiles.class);

    public static final int MAX_BYTES = 8_000_000;
    /** Long edge for the computer's own reader: big enough for small print on a card. */
    static final int OCR_EDGE = 2600;
    /** Claude shrinks pictures to this long edge and about 1.15 megapixels anyway; doing it first saves upload and money. */
    static final int CLAUDE_EDGE = 1568;
    static final long CLAUDE_PIXELS = 1_150_000;
    static final int TEXT_SHOWN = 4000;

    static final List<String> CARD_HEADERS = List.of("Email", "Name", "Job title", "Brand", "Website", "Phone", "Instagram", "LinkedIn");

    /**
     * A file read as a grid. {@code method}: how it was read, shown under the preview. {@code text}: what the computer
     * read off a picture or PDF, so she can check it. {@code note}: why nothing was found. {@code claudeCost}: set when
     * Claude could read this picture instead, with its price ("$0.004").
     */
    public record Read(List<String> headers, List<List<String>> rows, int firstLine, Map<String, Integer> mapping,
                       String method, Double costUsd, String text, String note, String claudeCost) {}

    private final ImageOcr ocr;
    private final LlmClient llm;

    public ContactFiles(ImageOcr ocr, LlmClient llm) {
        this.ocr = ocr;
        this.llm = llm;
    }

    /** Reads a file. {@code useClaude}: read a picture with Claude (she has seen the price) instead of on this computer. */
    public Read read(String fileName, byte[] data, boolean useClaude) {
        if (data == null || data.length == 0) throw new IllegalArgumentException("That file is empty");
        if (data.length > MAX_BYTES) throw new IllegalArgumentException("That file is bigger than 8 MB. Split it into smaller files.");
        String ext = extension(fileName);
        switch (ext) {
            case "csv", "tsv" -> {
                return table(ContactCsv.parse(decode(data)), "Read as a spreadsheet");
            }
            case "txt", "text" -> {
                String text = decode(data);
                List<List<String>> grid = ContactCsv.parse(text);
                if (grid.size() > 1 && grid.get(0).size() > 1 && ContactCsv.guessMapping(ContactCsv.table(grid)).size() > 1
                        && grid.get(0).stream().noneMatch(c -> Emails.IN_TEXT.matcher(c).find())) {
                    return table(grid, "Read as a spreadsheet");
                }
                return cards(TextContacts.read(text), "Read on this computer", null, clip(text), null);
            }
            case "xlsx", "xlsm" -> {
                try {
                    return table(Xlsx.read(data), "Read as an Excel sheet (the first sheet)");
                } catch (IOException e) {
                    throw new IllegalArgumentException(e.getMessage() + ". Try saving it as CSV in Excel and importing that.");
                }
            }
            case "xls" -> throw new IllegalArgumentException("This is an older Excel file (.xls). In Excel choose File, Save As, "
                    + "Excel Workbook (.xlsx), then import that.");
            case "numbers", "ods" -> throw new IllegalArgumentException("Export this sheet as CSV or Excel (.xlsx) first, then import that.");
            case "vcf", "vcard" -> {
                List<Card> cards = VCards.read(decode(data));
                if (cards.isEmpty()) throw new IllegalArgumentException("No contacts with an email address in this file.");
                return cards(cards, "Read as contact cards", null, null, null);
            }
            case "pdf" -> {
                String text = pdfText(data);
                if (text.replaceAll("\\s", "").length() < 20) {
                    throw new IllegalArgumentException("This PDF is a scanned picture. Take a screenshot of the page and import that instead.");
                }
                return cards(TextContacts.read(text), "Read the text in this PDF", null, clip(text), null);
            }
            case "png", "jpg", "jpeg", "gif", "webp", "bmp" -> {
                return picture(data, ext, useClaude);
            }
            case "heic", "heif" -> throw new IllegalArgumentException("iPhone photos (.heic) can't be read here. Take a screenshot "
                    + "of the photo, or share it as a JPEG, and import that.");
            default -> throw new IllegalArgumentException("The app can import spreadsheets (CSV, Excel .xlsx), contact cards (.vcf), "
                    + "PDFs and pictures (PNG, JPEG).");
        }
    }

    private Read picture(byte[] data, String ext, boolean useClaude) {
        Picture forClaude = Picture.of(data, ext, CLAUDE_EDGE, CLAUDE_PIXELS);
        String cost = llm.isConfigured() ? "$" + String.format(Locale.ROOT, "%.3f", forClaude.estimateUsd()) : null;
        if (useClaude) {
            if (!llm.isConfigured()) throw new IllegalArgumentException("Add your Claude key in Settings first, or type the details in by hand.");
            if (forClaude.bytes().length > 5_000_000) throw new IllegalArgumentException("That picture is too big for Claude. Crop it or take a screenshot.");
            LlmClient.PictureContacts r = llm.readContactPicture(forClaude.bytes(), forClaude.mediaType());
            String method = "Read by Claude for $" + String.format(Locale.ROOT, "%.3f", r.costUsd());
            return cards(r.contacts(), method, r.costUsd(), null, r.contacts().isEmpty() ? "Claude found no email addresses in this picture." : null);
        }
        String why = ocr.unavailable();
        if (why == null) {
            Picture forOcr = Picture.of(data, ext, OCR_EDGE, Long.MAX_VALUE);
            try {
                String text = ocr.read(forOcr.bytes(), forOcr.ext());
                List<Card> found = TextContacts.read(text);
                String note = found.isEmpty() ? "Couldn't find an email address in this picture." : null;
                return cards(found, "Read on this computer, free", null, clip(text), note, cost);
            } catch (IOException e) {
                log.info("Couldn't read a picture on this computer: {}", e.getMessage());
                why = e.getMessage();
            }
        }
        return new Read(CARD_HEADERS, List.of(), 1, Map.of("email", 0), null, null, null,
                why + (cost == null ? " Add your Claude key in Settings to have Claude read pictures (about a cent each), "
                        + "or type the details in by hand." : ""), cost);
    }

    private static Read table(List<List<String>> grid, String method) {
        ContactCsv.Table t = ContactCsv.table(grid);
        return new Read(t.headers(), t.rows(), t.firstLine(), ContactCsv.guessMapping(t), method, null, null, null, null);
    }

    private static Read cards(List<Card> cards, String method, Double cost, String text, String note) {
        return cards(cards, method, cost, text, note, null);
    }

    private static Read cards(List<Card> cards, String method, Double cost, String text, String note, String claudeCost) {
        List<List<String>> rows = new ArrayList<>();
        for (Card c : cards) {
            rows.add(List.of(n(c.email()), n(c.name()), n(c.title()), n(c.brand()), n(c.website()), n(c.phone()),
                    n(c.instagram()), n(c.linkedin())));
        }
        if (rows.isEmpty() && note == null) note = "No email addresses found in this file.";
        ContactCsv.Table t = new ContactCsv.Table(CARD_HEADERS, rows, 1);
        return new Read(CARD_HEADERS, rows, 1, ContactCsv.guessMapping(t), method, cost, text, note, claudeCost);
    }

    static String extension(String fileName) {
        String n = fileName == null ? "" : fileName.strip().toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1);
    }

    /** Text files from Excel and phones come as UTF-8, UTF-16 ("Unicode text") or the old Windows encoding. */
    static String decode(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
        }
        if (b.length >= 2 && (((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) || ((b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF))) {
            String s = new String(b, StandardCharsets.UTF_16);
            return s.startsWith("﻿") ? s.substring(1) : s;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return new String(b, Charset.forName("windows-1252"));
        }
    }

    private static String pdfText(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setEndPage(Math.min(doc.getNumberOfPages(), 40));
            return stripper.getText(doc);
        } catch (IOException e) {
            throw new IllegalArgumentException("Couldn't open this PDF. If it has a password, remove it first.");
        }
    }

    private static String clip(String text) {
        if (text == null) return null;
        String t = text.strip();
        return t.length() > TEXT_SHOWN ? t.substring(0, TEXT_SHOWN) + "…" : t;
    }

    private static String n(String s) {
        return s == null ? "" : s.strip();
    }

    /** A picture made ready to read: shrunk to fit, as PNG (screenshots) or JPEG (photos), with what Claude would charge. */
    record Picture(byte[] bytes, String ext, String mediaType, int width, int height) {

        static Picture of(byte[] data, String ext, int maxEdge, long maxPixels) {
            BufferedImage img;
            try {
                img = ImageIO.read(new ByteArrayInputStream(data));
            } catch (IOException | RuntimeException e) {
                img = null;
            }
            if (img == null) {
                if (ext.equals("webp")) return new Picture(data, ext, "image/webp", 0, 0); // ImageIO can't read WebP; send as is
                throw new IllegalArgumentException("Couldn't open that picture. Try a PNG or JPEG screenshot.");
            }
            int w = img.getWidth(), h = img.getHeight();
            double scale = Math.min(1.0, Math.min((double) maxEdge / Math.max(w, h), Math.sqrt((double) maxPixels / ((double) w * h))));
            int nw = Math.max(1, (int) Math.round(w * scale)), nh = Math.max(1, (int) Math.round(h * scale));
            boolean photo = ext.equals("jpg") || ext.equals("jpeg");
            if (scale >= 1.0 && (ext.equals("png") || photo)) {
                return new Picture(data, photo ? "jpg" : "png", photo ? "image/jpeg" : "image/png", w, h);
            }
            BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(java.awt.Color.WHITE); // transparent screenshots read as black on white
            g.fillRect(0, 0, nw, nh);
            g.drawImage(img, 0, 0, nw, nh, null);
            g.dispose();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try {
                ImageIO.write(out, photo ? "jpg" : "png", buf);
            } catch (IOException e) {
                throw new IllegalArgumentException("Couldn't open that picture. Try a PNG or JPEG screenshot.");
            }
            return new Picture(buf.toByteArray(), photo ? "jpg" : "png", photo ? "image/jpeg" : "image/png", nw, nh);
        }

        /**
         * Claude Haiku 4.5 at $1 per million tokens in, $5 out: a picture is about width × height / 750 tokens, plus
         * the instructions (~350) and a short answer (~300 out). Unknown sizes count as the largest picture.
         */
        double estimateUsd() {
            long pixels = width > 0 ? (long) width * height : CLAUDE_PIXELS;
            double in = Math.ceil(pixels / 750.0) + 350;
            return in / 1_000_000 + 300 * 5.0 / 1_000_000;
        }
    }
}
