package com.creatorcrm.channels.gmail;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

/**
 * An email's HTML, cleaned so it can be shown inside the app: layout, pictures and links stay; scripts, forms,
 * frames, event handlers and anything that isn't a web, mail or picture address are removed. The page is also
 * served with {@link #CSP} and shown in a sandboxed frame, so even a mistake here can't run code.
 */
public final class EmailHtml {
    private EmailHtml() {}

    /** For the email page only: pictures and inline styles, nothing that runs or submits, framed by the app alone. */
    public static final String CSP = "default-src 'none'; img-src https: http: data:; style-src 'unsafe-inline'; "
            + "font-src https: data:; frame-ancestors 'self'; base-uri 'none'; form-action 'none'; "
            + "sandbox allow-popups allow-popups-to-escape-sandbox allow-same-origin";

    private static final Safelist SAFE = Safelist.relaxed()
            .addTags("center", "font", "hr", "style", "details", "summary", "s", "strike", "small", "big", "del", "ins",
                    "mark", "address", "section", "article", "header", "footer", "main", "figure", "figcaption")
            .addAttributes(":all", "style", "class", "align", "valign", "width", "height", "bgcolor", "dir", "title",
                    "border", "cellpadding", "cellspacing", "color", "face", "size")
            .addAttributes("details", "open")
            .addProtocols("a", "href", "tel")
            .addProtocols("img", "src", "data");

    /** Gmail, Apple Mail, Yahoo and Outlook's quoted history. */
    private static final String QUOTES = "div.gmail_quote, blockquote.gmail_quote, blockquote[type=cite], div.yahoo_quoted";

    private static final String BASE_STYLE = "html,body{margin:0;background:#fff;color:#1f1f1f}"
            + "body{padding:14px 16px;font:14px/1.5 -apple-system,'Segoe UI',Roboto,Arial,sans-serif;overflow-wrap:anywhere;overflow-x:auto}"
            + "img{max-width:100%;height:auto}a{color:#1a5fd0}.plain{white-space:pre-wrap}"
            + "details.quoted{margin-top:12px;color:#555}"
            + "details.quoted>summary{cursor:pointer;color:#555;font-size:13px;display:inline-block;padding:2px 10px;"
            + "border:1px solid #ddd;border-radius:12px;margin-bottom:6px}";

    /** A complete page showing the email's HTML, cleaned. */
    public static String page(String html) {
        return wrap(clean(html));
    }

    /** A complete page showing a text-only message, with its web addresses made clickable. */
    public static String textPage(String text) {
        return wrap("<div class=\"plain\">" + linkify(Entities.escape(text == null ? "" : text)) + "</div>");
    }

    static String clean(String html) {
        Document dirty = Jsoup.parse(html == null ? "" : html);
        // Styles usually live in <head>, which the cleaner drops: move them into the body first.
        dirty.body().insertChildren(0, new ArrayList<>(dirty.head().select("style")));
        foldQuotes(dirty);
        Document doc = new Cleaner(SAFE).clean(dirty);
        doc.select("img").forEach(img -> {
            if (isTrackingPixel(img)) img.remove();
        });
        doc.select("a[href]").forEach(a -> a.attr("target", "_blank").attr("rel", "noopener noreferrer"));
        doc.outputSettings().prettyPrint(false);
        return doc.body().html();
    }

    /**
     * Earlier messages in the thread go behind a "Show earlier messages" button, like in Gmail. A forwarded email,
     * or one that is nothing but the quote, stays open so nothing she needs is hidden.
     */
    private static void foldQuotes(Document doc) {
        List<List<Element>> quotes = new ArrayList<>();
        for (Element q : doc.select(QUOTES)) {
            if (q.parents().stream().noneMatch(p -> p.is(QUOTES))) quotes.add(List.of(q));
        }
        // Outlook: a reply header, then the old message as the rest of the body.
        Element outlook = doc.selectFirst("#appendonsend, #divRplyFwdMsg");
        if (outlook != null && outlook.parents().stream().noneMatch(p -> p.is(QUOTES))) {
            List<Element> rest = new ArrayList<>(List.of(outlook));
            for (Element s = outlook.nextElementSibling(); s != null; s = s.nextElementSibling()) rest.add(s);
            quotes.add(rest);
        }
        for (List<Element> parts : quotes) {
            Element details = new Element("details").addClass("quoted");
            details.appendElement("summary").text("Show earlier messages");
            parts.get(0).before(details);
            String text = "";
            for (Element p : parts) {
                text += p.text();
                details.appendChild(p);
            }
            if (text.contains("Forwarded message") || text.contains("Begin forwarded message")) details.attr("open", "");
        }
        Document rest = doc.clone();
        rest.select("details.quoted").remove();
        if (rest.body().text().isBlank() && rest.body().select("img").isEmpty()) {
            Element first = doc.selectFirst("details.quoted");
            if (first != null) first.attr("open", "");
        }
    }

    /** 1×1 pictures only tell the sender the email was opened. */
    private static boolean isTrackingPixel(Element img) {
        String w = img.attr("width").strip(), h = img.attr("height").strip();
        String style = img.attr("style").replace(" ", "").toLowerCase(java.util.Locale.ROOT);
        return w.matches("[01](px)?") || h.matches("[01](px)?") || style.contains("display:none")
                || style.matches(".*(^|;)(width|height):[01]px.*");
    }

    private static final Pattern URL = Pattern.compile("https?://(?:(?!&quot;|&#39;|&apos;|&lt;|&gt;)[^\\s<>\"])+");

    /** Web addresses in already-escaped text become links; trailing punctuation stays outside the link. */
    static String linkify(String escaped) {
        Matcher m = URL.matcher(escaped);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String url = m.group();
            String tail = "";
            while (!url.isEmpty() && ".,;:!?)]'".indexOf(url.charAt(url.length() - 1)) >= 0) {
                tail = url.charAt(url.length() - 1) + tail;
                url = url.substring(0, url.length() - 1);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    "<a href=\"" + url + "\" target=\"_blank\" rel=\"noopener noreferrer\">" + url + "</a>" + tail));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String wrap(String body) {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"referrer\" content=\"no-referrer\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><style>" + BASE_STYLE
                + "</style></head><body>" + body + "</body></html>";
    }
}
