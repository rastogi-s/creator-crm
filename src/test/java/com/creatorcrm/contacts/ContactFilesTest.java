package com.creatorcrm.contacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creatorcrm.FakeLlm;
import com.creatorcrm.llm.ContactCards.Card;
import com.creatorcrm.llm.LlmClient;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Reading contacts out of files: Excel, contact cards, loose text from pictures and PDFs, and the column guesses. */
class ContactFilesTest {

    /** A computer that reads every picture as {@code text}, or can't read pictures when {@code text} is null. */
    static ImageOcr ocr(String text) {
        return new ImageOcr() {
            @Override
            public String unavailable() {
                return text == null ? "This computer can't read text in pictures by itself." : null;
            }

            @Override
            public String read(byte[] image, String ext) {
                return text;
            }
        };
    }

    static class PictureLlm extends FakeLlm {
        int pictures;

        @Override
        public LlmClient.PictureContacts readContactPicture(byte[] image, String mediaType) {
            pictures++;
            return new LlmClient.PictureContacts(List.of(new Card("ana@lumi.com", "Ana Ruiz", "PR Lead", "Lumi", "", "", "", "")), 0.0042);
        }
    }

    @Test
    void readsTheFirstExcelSheetWithSharedAndInlineText() throws IOException {
        byte[] xlsx = xlsx(
                "<sst><si><t>Company</t></si><si><t>Work Email</t></si><si><r><t>Glow</t></r><r><t>berry</t></r></si>"
                        + "<si><t>ana@glowberry.com</t></si></sst>",
                "<worksheet><sheetData>"
                        + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c><c r=\"D1\" t=\"inlineStr\"><is><t>Phone</t></is></c></row>"
                        + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>2</v></c><c r=\"B2\" t=\"s\"><v>3</v></c><c r=\"D2\"><v>4.47911123456E11</v></c></row>"
                        + "</sheetData></worksheet>");
        ContactFiles.Read r = new ContactFiles(ocr(null), new FakeLlm()).read("list.xlsx", xlsx, false);
        assertThat(r.headers()).containsExactly("Company", "Work Email", "Column 3", "Phone");
        assertThat(r.rows()).containsExactly(List.of("Glowberry", "ana@glowberry.com", "", "447911123456"));
        assertThat(r.mapping()).containsEntry("brand", 0).containsEntry("email", 1).containsEntry("phone", 3);
        assertThat(r.firstLine()).isEqualTo(2);
    }

    @Test
    void oldExcelAndUnknownFilesSayWhatToDo() {
        ContactFiles files = new ContactFiles(ocr(null), new FakeLlm());
        assertThatThrownBy(() -> files.read("old.xls", new byte[] {1}, false)).hasMessageContaining("Save As");
        assertThatThrownBy(() -> files.read("notes.docx", new byte[] {1}, false)).hasMessageContaining("can import");
        assertThatThrownBy(() -> files.read("photo.heic", new byte[] {1}, false)).hasMessageContaining("screenshot");
    }

    @Test
    void readsPhoneContactCards() {
        String vcf = "BEGIN:VCARD\r\nVERSION:3.0\r\nN:Ruiz;Ana;;;\r\nFN:Ana Ruiz\r\nORG:Lumi Skin\\, Inc.;Marketing\r\n"
                + "TITLE:Influencer Partnerships\r\nitem1.EMAIL;type=INTERNET;type=WORK:ana@lumiskin.com\r\n"
                + "EMAIL;type=INTERNET:ana.ruiz@lumi\r\n skin.com\r\nTEL;type=CELL:+1 415 555 0100\r\n"
                + "URL:https://www.instagram.com/lumiskin/\r\nURL:https://lumiskin.com\r\nEND:VCARD\r\n"
                + "BEGIN:VCARD\r\nVERSION:2.1\r\nFN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:Ren=C3=A9e Park\r\nEND:VCARD\r\n";
        List<Card> cards = VCards.read(vcf);
        assertThat(cards).hasSize(2); // Renée has no email
        assertThat(cards.get(0)).isEqualTo(new Card("ana@lumiskin.com", "Ana Ruiz", "Influencer Partnerships", "Lumi Skin, Inc.",
                "https://lumiskin.com", "+1 415 555 0100", "lumiskin", ""));
        assertThat(cards.get(1).email()).isEqualTo("ana.ruiz@lumiskin.com");
        assertThat(VCards.quotedPrintable("Ren=C3=A9e")).isEqualTo("Renée");
    }

    @Test
    void picksPeopleOffABusinessCard() {
        List<Card> cards = TextContacts.read(String.join("\n",
                "SUNLEAF BOTANICS",
                "Maya Chen",
                "Influencer Partnerships Manager",
                "maya.chen @sunleafbotanics.com",
                "M: +1 (415) 555-0142",
                "www.sunleafbotanics.com",
                "@sunleafbotanics"));
        assertThat(cards).containsExactly(new Card("maya.chen@sunleafbotanics.com", "Maya Chen", "Influencer Partnerships Manager",
                "Sunleaf Botanics", "www.sunleafbotanics.com", "+1 (415) 555-0142", "sunleafbotanics", ""));
    }

    @Test
    void picksOnePersonPerLineFromAList() {
        List<Card> cards = TextContacts.read(String.join("\n",
                "Name   Role   Email",
                "Ana Ruiz, PR Lead, ana@lumi.com",
                "Ben Okafor | Brand Partnerships | ben@glowberry.co.uk | 020 7946 0958",
                "collabs@dewdrop.io"));
        assertThat(cards).extracting(Card::email).containsExactly("ana@lumi.com", "ben@glowberry.co.uk", "collabs@dewdrop.io");
        assertThat(cards.get(0).name()).isEqualTo("Ana Ruiz");
        assertThat(cards.get(0).title()).isEqualTo("PR Lead");
        assertThat(cards.get(1).name()).isEqualTo("Ben Okafor");
        assertThat(cards.get(1).title()).isEqualTo("Brand Partnerships");
        assertThat(cards.get(1).phone()).isEqualTo("020 7946 0958");
        assertThat(cards.get(2).name()).isEmpty();
        assertThat(TextContacts.unshout("MAYA CHEN")).isEqualTo("Maya Chen");
        assertThat(TextContacts.unshout("GLOW CO LLC")).isEqualTo("Glow CO LLC");
    }

    @Test
    void picksTwoCardsFromOneScreenshot() {
        List<Card> cards = TextContacts.read(String.join("\n",
                "Priya Shah", "Head of PR", "priya@glowberry.com", "glowberry.com",
                "Tom Reed", "Founder", "tom@peaktrail.co", "+44 7700 900123"));
        assertThat(cards).hasSize(2);
        assertThat(cards.get(0)).isEqualTo(new Card("priya@glowberry.com", "Priya Shah", "Head of PR", "", "glowberry.com", "", "", ""));
        assertThat(cards.get(1)).isEqualTo(new Card("tom@peaktrail.co", "Tom Reed", "Founder", "", "", "+44 7700 900123", "", ""));
    }

    @Test
    void picturesAreReadOnThisComputerAndClaudeOnlyWhenAsked() throws IOException {
        PictureLlm llm = new PictureLlm();
        ContactFiles files = new ContactFiles(ocr("Ana Ruiz\nPR Lead\nana@lumi.com"), llm);
        ContactFiles.Read local = files.read("card.png", png(3000, 2000), false);
        assertThat(local.method()).contains("free");
        assertThat(local.rows()).containsExactly(List.of("ana@lumi.com", "Ana Ruiz", "PR Lead", "", "", "", "", ""));
        assertThat(local.text()).contains("PR Lead");
        assertThat(local.claudeCost()).startsWith("$0.00");
        assertThat(llm.pictures).isZero();

        ContactFiles.Read claude = files.read("card.png", png(3000, 2000), true);
        assertThat(llm.pictures).isEqualTo(1);
        assertThat(claude.method()).isEqualTo("Read by Claude for $0.004");
        assertThat(claude.costUsd()).isEqualTo(0.0042);
        assertThat(claude.mapping()).containsEntry("email", 0).containsEntry("name", 1).containsEntry("title", 2).containsEntry("brand", 3);
    }

    @Test
    void withoutAPictureReaderClaudeIsOfferedWithItsPrice() throws IOException {
        ContactFiles.Read r = new ContactFiles(ocr(null), new PictureLlm()).read("shot.jpg", jpg(), false);
        assertThat(r.rows()).isEmpty();
        assertThat(r.note()).contains("can't read text in pictures");
        assertThat(r.claudeCost()).matches("\\$0\\.00\\d");
    }

    @Test
    void shrinksBigPicturesTheWayClaudeWould() throws IOException {
        ContactFiles.Picture p = ContactFiles.Picture.of(png(4000, 3000), "png", ContactFiles.CLAUDE_EDGE, ContactFiles.CLAUDE_PIXELS);
        assertThat((long) p.width() * p.height()).isLessThanOrEqualTo(ContactFiles.CLAUDE_PIXELS + 2000);
        assertThat(p.mediaType()).isEqualTo("image/png");
        assertThat(p.estimateUsd()).isBetween(0.001, 0.01);
    }

    @Test
    void decodesExcelTextExportsAndGuessesHeaderlessLists() {
        assertThat(ContactFiles.decode("Café".getBytes(Charset.forName("windows-1252")))).isEqualTo("Café");
        assertThat(ContactFiles.decode("﻿Email\tName".getBytes(StandardCharsets.UTF_16LE))).isEqualTo("Email\tName");
        ContactFiles files = new ContactFiles(ocr(null), new FakeLlm());
        ContactFiles.Read tsv = files.read("list.tsv", "E-mail Address\tFirst Name\tLast Name\nana@lumi.com\tAna\tRuiz\n".getBytes(), false);
        assertThat(tsv.mapping()).isEqualTo(Map.of("email", 0, "first", 1, "last", 2));
        ContactFiles.Read bare = files.read("list.csv", "Ana Ruiz,ana@lumi.com\nBen,ben@glow.com\n".getBytes(), false);
        assertThat(bare.headers()).containsExactly("Column 1", "Column 2");
        assertThat(bare.rows()).hasSize(2);
        assertThat(bare.firstLine()).isEqualTo(1);
        assertThat(bare.mapping()).isEqualTo(Map.of("email", 1));
    }

    private static byte[] png(int w, int h) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private static byte[] jpg() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return out.toByteArray();
    }

    private static byte[] xlsx(String shared, String sheet) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, "xl/workbook.xml", "<workbook xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                    + "<sheets><sheet name=\"Contacts\" sheetId=\"1\" r:id=\"rId7\"/></sheets></workbook>");
            put(zip, "xl/_rels/workbook.xml.rels", "<Relationships><Relationship Id=\"rId7\" Target=\"worksheets/sheet2.xml\"/></Relationships>");
            put(zip, "xl/sharedStrings.xml", shared);
            put(zip, "xl/worksheets/sheet1.xml", "<worksheet><sheetData><row><c><v>1</v></c></row></sheetData></worksheet>");
            put(zip, "xl/worksheets/sheet2.xml", sheet);
        }
        return out.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
