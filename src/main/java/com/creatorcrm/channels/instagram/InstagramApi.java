package com.creatorcrm.channels.instagram;

import com.creatorcrm.config.CrmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Thin client for the Instagram API with Instagram Login (graph.instagram.com) and, for the optional Facebook
 * connection used for brand lookups, the Facebook Graph API (graph.facebook.com). Tokens are sent in the
 * Authorization header, never in URLs, so they don't end up in proxy/server logs.
 */
@Component
public class InstagramApi {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final String graph;
    private final String facebookGraph;
    private final String apiVersion;

    public InstagramApi(CrmProperties props) {
        this.apiVersion = props.instagram().apiVersion();
        this.graph = "https://graph.instagram.com/" + apiVersion;
        this.facebookGraph = "https://graph.facebook.com/" + apiVersion;
    }

    public String apiVersion() {
        return apiVersion;
    }

    public JsonNode get(String token, String path, Map<String, String> query) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(graph + path + "?" + form(query)))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(30)).GET().build();
        return send(req);
    }

    /** Facebook Graph API call with a Page token (Instagram API with Facebook Login). */
    public JsonNode getFacebook(String token, String path, Map<String, String> query) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(facebookGraph + path + "?" + form(query)))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(30)).GET().build();
        return send(req);
    }

    public JsonNode sendText(String token, String recipientId, String text) throws IOException, InterruptedException {
        String body = JSON.writeValueAsString(Map.of(
                "recipient", Map.of("id", recipientId),
                "message", Map.of("text", text)));
        HttpRequest req = HttpRequest.newBuilder(URI.create(graph + "/me/messages"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return send(req);
    }

    /** Authorization code -> short-lived token (+ user_id). */
    public JsonNode exchangeCode(String appId, String appSecret, String redirectUri, String code)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.instagram.com/oauth/access_token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(form(Map.of(
                        "client_id", appId, "client_secret", appSecret, "grant_type", "authorization_code",
                        "redirect_uri", redirectUri, "code", code))))
                .build();
        return send(req);
    }

    /** Short-lived -> long-lived (60 day) token. */
    public JsonNode longLived(String appSecret, String shortToken) throws IOException, InterruptedException {
        return tokenEndpoint("https://graph.instagram.com/access_token", Map.of(
                "grant_type", "ig_exchange_token", "client_secret", appSecret, "access_token", shortToken));
    }

    /** Facebook Login: authorization code -> short-lived user token. */
    public JsonNode facebookExchangeCode(String appId, String appSecret, String redirectUri, String code)
            throws IOException, InterruptedException {
        return tokenEndpoint(facebookGraph + "/oauth/access_token", Map.of(
                "client_id", appId, "client_secret", appSecret, "redirect_uri", redirectUri, "code", code));
    }

    /** Facebook Login: short-lived -> long-lived user token. Page tokens read with it don't expire. */
    public JsonNode facebookLongLived(String appId, String appSecret, String shortToken) throws IOException, InterruptedException {
        return tokenEndpoint(facebookGraph + "/oauth/access_token", Map.of(
                "grant_type", "fb_exchange_token", "client_id", appId, "client_secret", appSecret,
                "fb_exchange_token", shortToken));
    }

    /** Extend a long-lived token by another 60 days (token must be at least 24h old). */
    public JsonNode refresh(String token) throws IOException, InterruptedException {
        return tokenEndpoint("https://graph.instagram.com/refresh_access_token", Map.of(
                "grant_type", "ig_refresh_token", "access_token", token));
    }

    // Meta's token endpoints take the token as a query parameter (server-to-server over TLS).
    private JsonNode tokenEndpoint(String url, Map<String, String> query) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url + "?" + form(query)))
                .timeout(Duration.ofSeconds(30)).GET().build();
        return send(req);
    }

    private JsonNode send(HttpRequest req) throws IOException, InterruptedException {
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode node = res.body() == null || res.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(res.body());
        if (res.statusCode() >= 400) {
            String msg = node.path("error").path("message").asText(node.path("error_message").asText("HTTP " + res.statusCode()));
            throw new InstagramApiException(res.statusCode(), msg);
        }
        return node;
    }

    private static String form(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    public static class InstagramApiException extends IOException {
        public final int status;

        public InstagramApiException(int status, String message) {
            super("Instagram API error " + status + ": " + message);
            this.status = status;
        }
    }
}
