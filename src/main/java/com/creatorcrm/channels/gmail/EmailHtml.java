package com.creatorcrm.channels.gmail;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
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
        Document doc = Document.createShell("");
        doc.outputSettings().prettyPrint(false);
        linkify(doc.body().appendElement("div").addClass("plain").text(text == null ? "" : text));
        return wrap(doc.body().html());
    }

    static String clean(String html) {
        Document dirty = Jsoup.parse(html == null ? "" : html);
        // Styles usually live in <head>, which the cleaner drops: move them into the body first.
        dirty.body().insertChildren(0, new ArrayList<>(dirty.head().select("style")));
        dirty.select("a[href]").forEach(a -> a.attr("href", fullHref(a.attr("href"))));
        foldQuotes(dirty);
        Document doc = new Cleaner(SAFE).clean(dirty);
        doc.select("img").forEach(img -> {
            if (isTrackingPixel(img)) img.remove();
        });
        // A link whose address was dropped (one the email left relative, say) still works if it shows the address.
        doc.select("a:not([href])").forEach(a -> {
            String text = a.text().strip();
            if (URL.matcher(text).matches() && trimTail(text).equals(text)) a.attr("href", fullHref(text));
        });
        linkify(doc.body());
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

    /** A web address written out in text: with its http(s):// or starting with www. */
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)[^\\s<>\"]+");
    private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:");
    private static final Pattern BARE_HOST = Pattern.compile("(?i)^(?:www\\.|[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,}(?:[/?#:]|$))");

    /**
     * Link addresses written without https:// ("www.brand.com", "brand.com/apply", "//brand.com") work in Gmail but
     * would be dropped by the cleaner, leaving text that looks like a link and does nothing.
     */
    static String fullHref(String href) {
        String h = href.strip();
        if (h.startsWith("//")) return "https:" + h;
        if (!SCHEME.matcher(h).find() && BARE_HOST.matcher(h).find()) return "https://" + h;
        return h;
    }

    private static String trimTail(String url) {
        while (!url.isEmpty() && ".,;:!?)]'".indexOf(url.charAt(url.length() - 1)) >= 0) url = url.substring(0, url.length() - 1);
        return url;
    }

    /**
     * Web addresses written out as text, outside any link, become links, as Gmail shows them. Trailing punctuation
     * stays outside the link.
     */
    static void linkify(Element root) {
        List<TextNode> texts = new ArrayList<>();
        root.traverse((node, depth) -> {
            if (node instanceof TextNode t && t.parent() instanceof Element p && p.closest("a, style, summary") == null) texts.add(t);
        });
        for (TextNode t : texts) {
            String text = t.getWholeText();
            Matcher m = URL.matcher(text);
            List<Node> parts = new ArrayList<>();
            int last = 0;
            while (m.find()) {
                String url = trimTail(m.group());
                if (url.length() <= 4 || url.equalsIgnoreCase("www.")) continue;
                if (m.start() > last) parts.add(new TextNode(text.substring(last, m.start())));
                parts.add(new Element("a").attr("href", fullHref(url)).attr("target", "_blank").attr("rel", "noopener noreferrer").text(url));
                last = m.start() + url.length();
            }
            if (parts.isEmpty()) continue;
            if (last < text.length()) parts.add(new TextNode(text.substring(last)));
            for (Node p : parts) t.before(p);
            t.remove();
        }
    }

    private static String wrap(String body) {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"referrer\" content=\"no-referrer\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><style>" + BASE_STYLE
                + "</style></head><body>" + body + "</body></html>";
    }
}
