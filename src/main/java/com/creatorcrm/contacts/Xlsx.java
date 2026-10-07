package com.creatorcrm.contacts;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads the first sheet of an Excel workbook (.xlsx) as rows of text, in plain Java: an .xlsx file is a zip of XML
 * files, and contact lists only need the cell values, not formulas or formatting.
 */
final class Xlsx {
    private Xlsx() {}

    /** Stop reading a zip entry past this size, so a crafted file can't fill memory. */
    static final int MAX_ENTRY_BYTES = 60_000_000;
    static final int MAX_COLUMNS = 200;

    static List<List<String>> read(byte[] file) throws IOException {
        Map<String, byte[]> parts = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(file))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                String name = e.getName();
                if (name.equals("xl/workbook.xml") || name.equals("xl/_rels/workbook.xml.rels")
                        || name.equals("xl/sharedStrings.xml") || name.startsWith("xl/worksheets/sheet")) {
                    parts.put(name, readCapped(zip));
                }
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("Not a readable Excel file", e);
        }
        String sheet = firstSheet(parts);
        if (sheet == null || !parts.containsKey(sheet)) throw new IOException("Couldn't find a sheet in this Excel file");
        try {
            List<String> shared = sharedStrings(parts.get("xl/sharedStrings.xml"));
            return rows(parts.get(sheet), shared);
        } catch (XMLStreamException e) {
            throw new IOException("Couldn't read this Excel file", e);
        }
    }

    private static byte[] readCapped(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) {
            if (out.size() + n > MAX_ENTRY_BYTES) throw new IOException("This Excel file is too big. Save the contacts sheet as a CSV and import that.");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** The first sheet in the workbook's own order (not always sheet1.xml). */
    private static String firstSheet(Map<String, byte[]> parts) {
        try {
            String rid = null;
            if (parts.containsKey("xl/workbook.xml")) {
                XMLStreamReader r = reader(parts.get("xl/workbook.xml"));
                while (r.hasNext() && rid == null) {
                    if (r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("sheet")) {
                        for (int i = 0; i < r.getAttributeCount(); i++) {
                            if (r.getAttributeLocalName(i).equals("id")) rid = r.getAttributeValue(i);
                        }
                    }
                }
            }
            if (rid != null && parts.containsKey("xl/_rels/workbook.xml.rels")) {
                XMLStreamReader r = reader(parts.get("xl/_rels/workbook.xml.rels"));
                while (r.hasNext()) {
                    if (r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("Relationship")
                            && rid.equals(r.getAttributeValue(null, "Id"))) {
                        String target = r.getAttributeValue(null, "Target");
                        if (target == null) break;
                        if (target.startsWith("/")) return target.substring(1);
                        return "xl/" + target.replaceFirst("^\\./", "");
                    }
                }
            }
        } catch (XMLStreamException e) {
            // fall back to the usual name
        }
        return parts.containsKey("xl/worksheets/sheet1.xml") ? "xl/worksheets/sheet1.xml"
                : parts.keySet().stream().filter(k -> k.startsWith("xl/worksheets/sheet")).sorted().findFirst().orElse(null);
    }

    private static List<String> sharedStrings(byte[] xml) throws XMLStreamException {
        List<String> out = new ArrayList<>();
        if (xml == null) return out;
        XMLStreamReader r = reader(xml);
        StringBuilder cur = null;
        boolean phonetic = false;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "si" -> cur = new StringBuilder();
                    case "rPh" -> phonetic = true;
                    case "t" -> {
                        String t = r.getElementText();
                        if (cur != null && !phonetic) cur.append(t);
                    }
                    default -> { }
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                if (r.getLocalName().equals("si") && cur != null) {
                    out.add(cur.toString());
                    cur = null;
                } else if (r.getLocalName().equals("rPh")) {
                    phonetic = false;
                }
            }
        }
        return out;
    }

    private static List<List<String>> rows(byte[] xml, List<String> shared) throws XMLStreamException {
        List<List<String>> out = new ArrayList<>();
        XMLStreamReader r = reader(xml);
        List<String> row = null;
        int col = 0;
        String type = null;
        String value = null;
        StringBuilder inline = null;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "row" -> {
                        row = new ArrayList<>();
                        col = 0;
                    }
                    case "c" -> {
                        String ref = r.getAttributeValue(null, "r");
                        int at = ref == null ? -1 : column(ref);
                        col = at >= 0 ? at : col;
                        type = r.getAttributeValue(null, "t");
                        value = null;
                        inline = null;
                    }
                    case "v" -> value = r.getElementText();
                    case "is" -> inline = new StringBuilder();
                    case "t" -> {
                        String t = r.getElementText();
                        if (inline != null) inline.append(t);
                    }
                    default -> { }
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                if (r.getLocalName().equals("c") && row != null) {
                    if (col < MAX_COLUMNS) {
                        while (row.size() < col) row.add("");
                        row.add(cell(type, value, inline, shared));
                    }
                    col++;
                } else if (r.getLocalName().equals("row") && row != null) {
                    out.add(row);
                    row = null;
                }
            }
        }
        return out;
    }

    private static String cell(String type, String value, StringBuilder inline, List<String> shared) {
        if ("inlineStr".equals(type)) return inline == null ? "" : inline.toString();
        if (value == null) return "";
        if ("s".equals(type)) {
            try {
                int i = Integer.parseInt(value.strip());
                return i >= 0 && i < shared.size() ? shared.get(i) : "";
            } catch (NumberFormatException e) {
                return "";
            }
        }
        if ("b".equals(type)) return "1".equals(value) ? "TRUE" : "FALSE";
        if (type == null || "n".equals(type)) return number(value);
        return value; // str (formula result), e (error)
    }

    /** Phone numbers saved as numbers come back as 4.47911123456E11; write them out in full. */
    static String number(String v) {
        try {
            BigDecimal d = new BigDecimal(v.strip());
            return d.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return v;
        }
    }

    /** "C12" → 2 (zero-based column). */
    static int column(String ref) {
        int n = 0;
        int i = 0;
        for (; i < ref.length() && Character.isLetter(ref.charAt(i)); i++) {
            n = n * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
        }
        return i == 0 ? -1 : n - 1;
    }

    private static XMLStreamReader reader(byte[] xml) throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        return f.createXMLStreamReader(new ByteArrayInputStream(xml));
    }
}
