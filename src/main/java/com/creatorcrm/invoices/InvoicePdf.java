package com.creatorcrm.invoices;

import com.creatorcrm.domain.Invoice;
import com.creatorcrm.settings.SettingsService;
import com.lowagie.text.Chunk;
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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Renders an invoice as a one-page PDF, on demand. Business details come from Settings, Invoices. */
@Component
public class InvoicePdf {
    private static final Color MUTED = new Color(110, 104, 98);
    private static final Color ACCENT = new Color(194, 65, 12);
    private static final Color RULE = new Color(225, 220, 214);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);

    private final SettingsService settings;

    public InvoicePdf(SettingsService settings) {
        this.settings = settings;
    }

    public static String money(String currency, BigDecimal amount) {
        NumberFormat f = NumberFormat.getNumberInstance(Locale.US);
        f.setMinimumFractionDigits(2);
        f.setMaximumFractionDigits(2);
        return currency + " " + f.format(amount == null ? BigDecimal.ZERO : amount.setScale(2, RoundingMode.HALF_UP));
    }

    public static String date(LocalDate d) {
        return d == null ? "" : DATE.format(d);
    }

    public byte[] render(Invoice inv) {
        Font title = new Font(Font.HELVETICA, 26, Font.BOLD, ACCENT);
        Font name = new Font(Font.HELVETICA, 15, Font.BOLD);
        Font body = new Font(Font.HELVETICA, 10.5f);
        Font bold = new Font(Font.HELVETICA, 10.5f, Font.BOLD);
        Font label = new Font(Font.HELVETICA, 8.5f, Font.BOLD, MUTED);
        Font placeholder = new Font(Font.HELVETICA, 10.5f, Font.ITALIC, MUTED);
        Font total = new Font(Font.HELVETICA, 13, Font.BOLD);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 54, 54, 54, 54);
        try {
            PdfWriter.getInstance(doc, out);
            doc.addTitle("Invoice " + inv.number);
            doc.open();

            // From (left) and invoice number/dates (right)
            PdfPTable head = table(new float[] {3, 2});
            PdfPCell from = cell();
            from.addElement(new Paragraph(settings.invoiceBusinessName(), name));
            addLines(from, settings.invoiceAddress(), body, placeholder, "Add your address in Settings, Invoices");
            if (!settings.invoiceTaxId().isBlank()) from.addElement(new Paragraph("Tax ID: " + settings.invoiceTaxId(), body));
            head.addCell(from);
            PdfPCell meta = cell();
            Paragraph t = new Paragraph("INVOICE", title);
            t.setAlignment(Element.ALIGN_RIGHT);
            meta.addElement(t);
            meta.addElement(right(inv.number, bold));
            meta.addElement(right("Issued " + date(inv.issuedDate), body));
            meta.addElement(right("Due " + date(inv.dueDate), bold));
            head.addCell(meta);
            doc.add(head);

            // Bill to
            Paragraph billLabel = new Paragraph("BILL TO", label);
            billLabel.setSpacingBefore(26);
            doc.add(billLabel);
            PdfPCell billTo = cell();
            addLines(billTo, inv.billTo, body, placeholder, "Brand name");
            if (inv.billToEmail != null && !inv.billToEmail.isBlank()) billTo.addElement(new Paragraph(inv.billToEmail, body));
            PdfPTable billTable = table(new float[] {1});
            billTable.addCell(billTo);
            doc.add(billTable);

            // Line items
            PdfPTable items = table(new float[] {5, 2});
            items.setSpacingBefore(24);
            items.addCell(headerCell("DESCRIPTION", label, Element.ALIGN_LEFT));
            items.addCell(headerCell("AMOUNT", label, Element.ALIGN_RIGHT));
            for (LineItem li : LineItem.parse(inv.lineItems)) {
                items.addCell(rowCell(new Phrase(li.description() == null ? "" : li.description(), body), Element.ALIGN_LEFT));
                items.addCell(rowCell(new Phrase(money(inv.currency, li.amount()), body), Element.ALIGN_RIGHT));
            }
            PdfPCell totalLabel = rowCell(new Phrase("Total due", total), Element.ALIGN_LEFT);
            totalLabel.setBorder(Rectangle.NO_BORDER);
            PdfPCell totalValue = rowCell(new Phrase(money(inv.currency, inv.amount), total), Element.ALIGN_RIGHT);
            totalValue.setBorder(Rectangle.NO_BORDER);
            items.addCell(totalLabel);
            items.addCell(totalValue);
            doc.add(items);

            // How to pay
            Paragraph payLabel = new Paragraph("HOW TO PAY", label);
            payLabel.setSpacingBefore(26);
            doc.add(payLabel);
            PdfPCell pay = cell();
            addLines(pay, settings.invoicePaymentDetails(), body, placeholder, "Add your payment details in Settings, Invoices");
            pay.addElement(new Paragraph("Please include " + inv.number + " with your payment.", body));
            PdfPTable payTable = table(new float[] {1});
            payTable.addCell(pay);
            doc.add(payTable);

            if (inv.notes != null && !inv.notes.isBlank()) {
                Paragraph notesLabel = new Paragraph("NOTES", label);
                notesLabel.setSpacingBefore(20);
                doc.add(notesLabel);
                doc.add(new Paragraph(inv.notes, body));
            }

            Paragraph thanks = new Paragraph(new Chunk("Thank you!", bold));
            thanks.setSpacingBefore(30);
            doc.add(thanks);
        } catch (DocumentException e) {
            throw new IllegalStateException("Could not create the invoice PDF", e);
        } finally {
            if (doc.isOpen()) doc.close();
        }
        return out.toByteArray();
    }

    private static PdfPTable table(float[] widths) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        return t;
    }

    private static PdfPCell cell() {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(0);
        return c;
    }

    private static PdfPCell headerCell(String text, Font font, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(RULE);
        c.setHorizontalAlignment(align);
        c.setPaddingBottom(6);
        return c;
    }

    private static PdfPCell rowCell(Phrase p, int align) {
        PdfPCell c = new PdfPCell(p);
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(RULE);
        c.setHorizontalAlignment(align);
        c.setPaddingTop(8);
        c.setPaddingBottom(8);
        return c;
    }

    private static Paragraph right(String text, Font font) {
        Paragraph p = new Paragraph(text, font);
        p.setAlignment(Element.ALIGN_RIGHT);
        return p;
    }

    private static void addLines(PdfPCell cell, String text, Font font, Font placeholder, String missing) {
        if (text == null || text.isBlank()) {
            cell.addElement(new Paragraph("[" + missing + "]", placeholder));
            return;
        }
        for (String line : text.strip().split("\\R")) cell.addElement(new Paragraph(line, font));
    }
}
