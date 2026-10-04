package com.creatorcrm.web;

import com.creatorcrm.domain.Draft;
import com.creatorcrm.invoices.InvoiceService;
import com.creatorcrm.invoices.PaymentReminders;
import com.creatorcrm.invoices.InvoiceService.InvoiceEdit;
import com.creatorcrm.invoices.InvoiceService.InvoiceView;
import com.creatorcrm.settings.SettingsService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Money tab and invoice API. Emailing an invoice only creates a draft; it is sent from Drafts like any message. */
@RestController
@RequestMapping("/api")
public class InvoiceController {

    public record Paid(LocalDate paidDate) {}

    private final InvoiceService invoices;
    private final SettingsService settings;
    private final PaymentReminders reminders;

    public InvoiceController(InvoiceService invoices, SettingsService settings, PaymentReminders reminders) {
        this.reminders = reminders;
        this.invoices = invoices;
        this.settings = settings;
    }

    @GetMapping("/money")
    public InvoiceService.Money money(@RequestParam(required = false) Integer year) {
        return invoices.money(year);
    }

    @GetMapping("/money/invoices.csv")
    public ResponseEntity<byte[]> csv(@RequestParam int year) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("invoices-" + year + ".csv").build().toString())
                .body(invoices.csv(year).getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/opportunities/{id}/invoices")
    public List<InvoiceView> forDeal(@PathVariable Long id) {
        return invoices.forDeal(id);
    }

    @PostMapping("/opportunities/{id}/invoices")
    public InvoiceView create(@PathVariable Long id) {
        return invoices.view(invoices.createForDeal(id, settings.today()));
    }

    @GetMapping("/invoices/{id}")
    public InvoiceView get(@PathVariable Long id) {
        return invoices.view(invoices.get(id));
    }

    @PutMapping("/invoices/{id}")
    public InvoiceView update(@PathVariable Long id, @RequestBody InvoiceEdit edit) {
        return invoices.view(invoices.update(id, edit));
    }

    @GetMapping("/invoices/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean download) {
        String number = invoices.get(id).number;
        ContentDisposition cd = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(number + ".pdf").build();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString()).body(invoices.pdf(id));
    }

    @PostMapping("/invoices/{id}/email")
    public Draft email(@PathVariable Long id) {
        return invoices.emailDraft(id);
    }

    /** The next payment reminder, now. Like every reminder, it waits in Drafts for approval. */
    @PostMapping("/invoices/{id}/reminder")
    public Draft reminder(@PathVariable Long id) {
        return reminders.draftNow(id);
    }

    @PostMapping("/invoices/{id}/sent")
    public InvoiceView sent(@PathVariable Long id) {
        return invoices.view(invoices.markSent(id));
    }

    @PostMapping("/invoices/{id}/paid")
    public InvoiceView paid(@PathVariable Long id, @RequestBody(required = false) Paid p) {
        LocalDate date = p == null || p.paidDate() == null ? settings.today() : p.paidDate();
        return invoices.view(invoices.markPaid(id, date));
    }

    @PostMapping("/invoices/{id}/void")
    public InvoiceView voidInvoice(@PathVariable Long id) {
        return invoices.view(invoices.voidInvoice(id));
    }
}
