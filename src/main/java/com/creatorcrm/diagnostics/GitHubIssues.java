package com.creatorcrm.diagnostics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/** The three GitHub calls error reporting needs. The token only needs "Issues: read and write" on one repo. */
class GitHubIssues {

    record Issue(int number, String url, boolean open) {}

    /** GitHub said no for a reason retrying won't fix (bad token, no access). */
    static class Rejected extends IOException {
        Rejected(String message) { super(message); }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String apiBaseUrl;
    private final String repo;
    private final String token;
    private final String userAgent;

    GitHubIssues(String apiBaseUrl, String repo, String token, String userAgent) {
        this.apiBaseUrl = apiBaseUrl.replaceAll("/+$", "");
        this.repo = repo;
        this.token = token;
        this.userAgent = userAgent;
    }

    Issue create(String title, String body, List<String> labels) throws IOException, InterruptedException {
        ObjectNode req = json.createObjectNode().put("title", title).put("body", body);
        labels.forEach(req.putArray("labels")::add);
        HttpResponse<String> r = send("POST", "/repos/" + repo + "/issues", req.toString());
        if (!labels.isEmpty() && (r.statusCode() == 422 || r.statusCode() == 403)) {
            // Some tokens may open issues but not set labels; the title prefix still identifies the report.
            req.remove("labels");
            r = send("POST", "/repos/" + repo + "/issues", req.toString());
        }
        return issue(check(r));
    }

    Issue get(int number) throws IOException, InterruptedException {
        HttpResponse<String> r = send("GET", "/repos/" + repo + "/issues/" + number, null);
        if (r.statusCode() == 404 || r.statusCode() == 410) return null;
        return issue(check(r));
    }

    void comment(int number, String body) throws IOException, InterruptedException {
        check(send("POST", "/repos/" + repo + "/issues/" + number + "/comments",
                json.createObjectNode().put("body", body).toString()));
    }

    private HttpResponse<String> send(String method, String path, String body) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(apiBaseUrl + path)).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + token)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", userAgent);
        b = body == null ? b.GET() : b.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode check(HttpResponse<String> r) throws IOException {
        int s = r.statusCode();
        if (s == 401) throw new Rejected("GitHub didn't accept the error-report token (it may have expired)");
        if (s == 403 || s == 404) throw new Rejected("the error-report token can't write issues on " + repo);
        if (s / 100 != 2) throw new IOException("GitHub answered " + s);
        return json.readTree(r.body());
    }

    private static Issue issue(JsonNode n) {
        return new Issue(n.path("number").asInt(), n.path("html_url").asText(""), "open".equals(n.path("state").asText()));
    }
}
