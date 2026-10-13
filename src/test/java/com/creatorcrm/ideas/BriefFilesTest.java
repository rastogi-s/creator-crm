package com.creatorcrm.ideas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class BriefFilesTest {

    static byte[] zip(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (var e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    static byte[] docx(String... paragraphs) throws Exception {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?><w:document><w:body>");
        for (String p : paragraphs) xml.append("<w:p><w:r><w:t xml:space=\"preserve\">").append(p).append("</w:t></w:r></w:p>");
        return zip(Map.of("[Content_Types].xml", "<Types/>", "word/document.xml", xml.append("</w:body></w:document>").toString()));
    }

    @Test
    void readsWordParagraphs() throws Exception {
        String text = BriefFiles.text("Brief.DOCX", docx("Key message: cozy &amp; calm", "Tag @novanest", "No competitor brands"));
        assertThat(text).isEqualTo("Key message: cozy & calm\nTag @novanest\nNo competitor brands");
    }

    @Test
    void readsSlidesInOrder() throws Exception {
        byte[] pptx = zip(Map.of(
                "ppt/slides/slide10.xml", "<p:sld><a:p><a:r><a:t>Deadline: 20 October</a:t></a:r></a:p></p:sld>",
                "ppt/slides/slide2.xml", "<p:sld><a:p><a:r><a:t>Show the lamp first</a:t></a:r></a:p><a:p><a:r><a:t>Use #cozycorner</a:t></a:r></a:p></p:sld>"));
        String text = BriefFiles.text("deck.pptx", pptx);
        assertThat(text).isEqualTo("Slide 2:\nShow the lamp first\nUse #cozycorner\n\nSlide 10:\nDeadline: 20 October");
    }

    @Test
    void plainTextAndUnreadableFiles() throws Exception {
        assertThat(BriefFiles.text("notes.txt", "Film in daylight, 30 to 45 seconds.".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("Film in daylight, 30 to 45 seconds.");
        assertThat(BriefFiles.text("empty.docx", docx(""))).isNull();
        assertThat(BriefFiles.text("photo.jpg", new byte[] {1, 2, 3})).isNull();
        assertThatThrownBy(() -> BriefFiles.text("fake.docx", zip(Map.of("other.xml", "<x/>")))).hasMessageContaining("Word");
    }

    @Test
    void longBriefsAreCut() throws Exception {
        String text = BriefFiles.text("long.txt", "word ".repeat(10_000).getBytes(StandardCharsets.UTF_8));
        assertThat(text).hasSizeLessThan(BriefFiles.MAX_CHARS + 50).endsWith("[...the rest was cut]");
    }
}
