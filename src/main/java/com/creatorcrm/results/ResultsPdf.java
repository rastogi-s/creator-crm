package com.creatorcrm.results;

import com.creatorcrm.channels.instagram.InstagramStatsService;
import com.creatorcrm.domain.CampaignResult;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.settings.SettingsService;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** The one-page campaign results PDF a brand gets with the recap email, rendered on demand like invoices. */
@Component
public class ResultsPdf {
    private static final Color MUTED = new Color(110, 104, 98);
    private static final Color ACCENT = new Color(194, 65, 12);
    private static final Color TILE = new Color(250, 246, 242);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);

    /** What the PDF shows besides the numbers. */
    record Header(String brand, String campaign, String deliverables, String creator, String instagram) {}

    private final SettingsService settings;
    private final InstagramStatsService instagram;

    public ResultsPdf(SettingsService settings, InstagramStatsService instagram) {
        this.settings = settings;
        this.instagram = instagram;
    }

    public static String fileName(String brand) {
        String safe = brand == null ? "" : brand.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "");
        return (safe.isEmpty() ? "Campaign" : safe) + "-results.pdf";
    }

    public byte[] render(CampaignResult r, Opportunity o, String brand) {
        String username = instagram.current().map(InstagramStatsService.Stats::username).orElse("");
        return render(r, new Header(brand, o.campaign, o.deliverables, settings.invoiceBusinessName(), username));
    }

    byte[] render(CampaignResult r, Header h) {
        Font title = new Font(Font.HELVETICA, 24, Font.BOLD, ACCENT);
        Font sub = new Font(Font.HELVETICA, 13, Font.BOLD);
        Font body = new Font(Font.HELVETICA, 10.5f);
        Font muted = new Font(Font.HELVETICA, 9, Font.NORMAL, MUTED);
        Font label = new Font(Font.HELVETICA, 8.5f, Font.BOLD, MUTED);
        Font number = new Font(Font.HELVETICA, 22, Font.BOLD);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 54, 54, 54, 54);
        try {
            PdfWriter.getInstance(doc, out);
            doc.addTitle("Campaign results: " + h.brand());
            doc.open();

            doc.add(new Paragraph("Campaign results", title));
            String what = h.brand() + (blank(h.campaign()) ? "" : " · " + h.campaign());
            Paragraph p = new Paragraph(what, sub);
            p.setSpacingBefore(4);
            doc.add(p);
            List<String> facts = new ArrayList<>();
            facts.add("By " + h.creator() + (blank(h.instagram()) ? "" : " (@" + h.instagram().replaceFirst("^@", "") + ")"));
            if (r.postedAt != null) facts.add("Posted " + DATE.format(r.postedAt.toLocalDate()));
            if (!blank(h.deliverables())) facts.add(h.deliverables());
            Paragraph f = new Paragraph(String.join("   |   ", facts), body);
            f.setSpacingBefore(6);
            doc.add(f);
            if (!blank(r.postUrl)) doc.add(new Paragraph(r.postUrl, muted));

            PdfPTable tiles = new PdfPTable(3);
            tiles.setWidthPercentage(100);
            tiles.setSpacingBefore(26);
            List<String[]> shown = new ArrayList<>();
            add(shown, "ACCOUNTS REACHED", r.reach);
            add(shown, "VIEWS", r.views);
            add(shown, "LIKES", r.likes);
            add(shown, "COMMENTS", r.comments);
            add(shown, "SAVES", r.saves);
            add(shown, "SHARES", r.shares);
            Double rate = CampaignResults.engagementRate(r);
            if (rate != null) shown.add(new String[] {"ENGAGEMENT RATE", String.format(Locale.US, "%.1f%%", rate)});
            for (String[] s : shown) tiles.addCell(tile(s[0], s[1], label, number));
            for (int i = shown.size() % 3; i != 0 && i < 3; i++) tiles.addCell(empty());
            if (!shown.isEmpty()) doc.add(tiles);

            if (rate != null) {
                Paragraph note = new Paragraph("Engagement rate = likes, comments, saves and shares, divided by accounts reached.", muted);
                note.setSpacingBefore(8);
                doc.add(note);
            }
            Paragraph source = new Paragraph(r.source == CampaignResult.Source.INSTAGRAM && r.fetchedAt != null
                    ? "Numbers from Instagram Insights, " + DATE.format(r.fetchedAt.toLocalDate()) + "."
                    : "Numbers as reported by " + h.creator() + ".", muted);
            source.setSpacingBefore(4);
            doc.add(source);

            Paragraph thanks = new Paragraph("Thank you for the collaboration. I'd love to work together again!", body);
            thanks.setSpacingBefore(30);
            doc.add(thanks);
            doc.add(new Paragraph(h.creator(), sub));
        } catch (DocumentException e) {
            throw new IllegalStateException("Could not create the results PDF", e);
        } finally {
            if (doc.isOpen()) doc.close();
        }
        return out.toByteArray();
    }

    private static void add(List<String[]> shown, String label, Long value) {
        if (value != null) shown.add(new String[] {label, String.format(Locale.US, "%,d", value)});
    }

    private static PdfPCell tile(String label, String value, Font labelFont, Font numberFont) {
        PdfPCell c = new PdfPCell();
        c.setBackgroundColor(TILE);
        c.setBorderWidth(3);
        c.setBorderColor(Color.WHITE);
        c.setPadding(12);
        c.addElement(new Paragraph(label, labelFont));
        Paragraph n = new Paragraph(new Phrase(value, numberFont));
        n.setSpacingBefore(4);
        c.addElement(n);
        return c;
    }

    private static PdfPCell empty() {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        return c;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
