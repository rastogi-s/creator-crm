package com.creatorcrm.ideas;

import com.creatorcrm.contracts.PdfText;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The text of a brand's brief file: PDF, Word (.docx), PowerPoint (.pptx) or plain text. Word and PowerPoint files are
 * zip archives of XML; only the text runs are read, so pictures and layout drop out.
 */
final class BriefFiles {
    private BriefFiles() {}

    /** Per file and in all: a brief's must-dos are near the top, and Claude doesn't need a 40-page deck word for word. */
    static final int MAX_CHARS = 30_000;
    /** Unzipped size read from one Office file, so a zip bomb can't fill the memory. */
    private static final long MAX_UNZIPPED = 40L * 1024 * 1024;

    private static final Pattern WORD_TEXT = Pattern.compile("<w:t(?:\\s[^>]*)?>([^<]*)</w:t>|<w:tab/>|</w:p>|<w:br/>");
    private static final Pattern SLIDE_TEXT = Pattern.compile("<a:t>([^<]*)</a:t>|</a:p>");
    private static final Pattern SLIDE_NAME = Pattern.compile("ppt/slides/slide(\\d+)\\.xml");

    /** The file's text, or null when it has none the app can read (a scan, a picture-only deck). */
    static String text(String fileName, byte[] data) throws IOException {
        String ext = extension(fileName);
        String text = switch (ext) {
            case "pdf" -> {
                String t = PdfText.extract(data);
                yield PdfText.looksScanned(t) ? null : t;
            }
            case "docx" -> word(data);
            case "pptx" -> slides(data);
            case "txt", "md" -> new String(data, StandardCharsets.UTF_8);
            default -> null;
        };
        if (text == null) return null;
        text = text.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n\\s*\\n(\\s*\\n)+", "\n\n").strip();
        if (text.replaceAll("\\s", "").length() < 20) return null;
        return clip(text);
    }

    static String clip(String text) {
        return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) + "\n[...the rest was cut]" : text;
    }

    static String extension(String fileName) {
        String n = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1);
    }

    static String word(byte[] docx) throws IOException {
        String xml = unzip(docx, "word/document.xml");
        if (xml == null) throw new IOException("Not a Word file");
        StringBuilder sb = new StringBuilder();
        Matcher m = WORD_TEXT.matcher(xml);
        while (m.find()) {
            if (m.group(1) != null) sb.append(xmlText(m.group(1)));
            else if (m.group().startsWith("<w:tab")) sb.append(' ');
            else sb.append('\n');
        }
        return sb.toString();
    }

    static String slides(byte[] pptx) throws IOException {
        TreeMap<Integer, String> slides = new TreeMap<>();
        forEachEntry(pptx, (name, xml) -> {
            Matcher n = SLIDE_NAME.matcher(name);
            if (n.matches()) slides.put(Integer.parseInt(n.group(1)), xml);
        });
        if (slides.isEmpty()) throw new IOException("Not a PowerPoint file");
        List<String> out = new ArrayList<>();
        slides.forEach((number, xml) -> {
            StringBuilder sb = new StringBuilder("Slide ").append(number).append(":\n");
            Matcher m = SLIDE_TEXT.matcher(xml);
            while (m.find()) sb.append(m.group(1) != null ? xmlText(m.group(1)) : "\n");
            out.add(sb.toString().strip());
        });
        return String.join("\n\n", out);
    }

    private static String xmlText(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    private interface EntryHandler { void accept(String name, String xml); }

    private static String unzip(byte[] zip, String wanted) throws IOException {
        String[] found = {null};
        forEachEntry(zip, (name, xml) -> { if (name.equals(wanted)) found[0] = xml; });
        return found[0];
    }

    private static void forEachEntry(byte[] zip, EntryHandler handler) throws IOException {
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                String name = e.getName();
                if (!name.equals("word/document.xml") && !SLIDE_NAME.matcher(name).matches()) continue;
                byte[] data = read(in, MAX_UNZIPPED - total);
                total += data.length;
                handler.accept(name, new String(data, StandardCharsets.UTF_8));
            }
        }
    }

    private static byte[] read(InputStream in, long max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) {
            if (out.size() + n > max) throw new IOException("The file is too big to read");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
