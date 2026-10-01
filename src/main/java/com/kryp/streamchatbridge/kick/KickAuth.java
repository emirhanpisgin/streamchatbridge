package com.kryp.streamchatbridge.kick;

import com.kryp.streamchatbridge.util.BrowserUtils;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;


import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class KickAuth {

    private static final String AUTHORIZE_URL = "https://id.kick.com/oauth/authorize";

    private static final String TOKEN_URL = "https://id.kick.com/oauth/token";

    private static final String INTROSPECT_URL = "https://id.kick.com/oauth/token/introspect";

    private static final String API_URL = "https://api.kick.com/public/v1";

    private static final String USERS_URL = API_URL + "/users";

    private static final String REDIRECT_URI = "http://localhost:17564/kick/callback";

    /*
     * These correspond to the permissions selected
     * for the user's Kick application.
     */
    private static final String SCOPES = "user:read channel:read chat:write events:subscribe";

    private static final Gson GSON = new Gson();

    private static final SecureRandom RANDOM = new SecureRandom();

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final KickCredentials credentials;

    private volatile String userId;

    private volatile String username;

    public KickAuth() {
        credentials = KickCredentials.load();
    }

    public boolean restoreSession() {
        if (!credentials.hasClientCredentials()) {
            return false;
        }

        if (credentials.hasAccessToken() && !credentials.isAccessTokenExpired()) {

            if (loadCurrentUser()) {
                return true;
            }
        }

        if (credentials.hasRefreshToken() && refreshAccessToken()) {

            return loadCurrentUser();
        }

        return false;
    }

    public boolean authenticate() {
        if (!credentials.hasClientCredentials()) {
            System.err.println("[Stream Chat Bridge] Kick client credentials are not configured.");

            return false;
        }

        HttpServer callbackServer = null;

        try {
            String state = randomUrlSafeString(32);

            String codeVerifier = randomUrlSafeString(64);

            String codeChallenge = createCodeChallenge(codeVerifier);

            CompletableFuture<String> codeFuture = new CompletableFuture<>();

            callbackServer = HttpServer.create(new InetSocketAddress("localhost", 17564), 0);

            HttpServer server = callbackServer;

            server.createContext("/kick/callback", exchange -> handleCallback(exchange, state, codeFuture));

            server.start();

            String authorizationUrl = AUTHORIZE_URL + "?response_type=code" + "&client_id=" + encode(credentials.clientId) + "&redirect_uri=" + encode(REDIRECT_URI) + "&scope=" + encode(SCOPES) + "&code_challenge=" + encode(codeChallenge) + "&code_challenge_method=S256" + "&state=" + encode(state);

            System.out.println("[Stream Chat Bridge] Opening Kick authentication...");

            System.out.println("[Stream Chat Bridge] Kick authorization URL: " + authorizationUrl);

            BrowserUtils.open(authorizationUrl);

            String code = codeFuture.get(5, TimeUnit.MINUTES);

            if (!exchangeCodeForTokens(code, codeVerifier)) {
                return false;
            }

            return loadCurrentUser();

        } catch (Exception e) {
            System.err.println("[Stream Chat Bridge] Kick authentication failed: " + e.getMessage());

            return false;

        } finally {
            if (callbackServer != null) {
                callbackServer.stop(0);
            }
        }
    }

    public synchronized boolean refreshAccessToken() {
        if (!credentials.hasClientCredentials() || !credentials.hasRefreshToken()) {

            return false;
        }

        try {
            String body = "grant_type=refresh_token" + "&refresh_token=" + encode(credentials.refreshToken) + "&client_id=" + encode(credentials.clientId) + "&client_secret=" + encode(credentials.clientSecret);

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(TOKEN_URL)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.err.println("[Stream Chat Bridge] Kick token refresh failed. HTTP " + response.statusCode() + ": " + response.body());

                return false;
            }

            JsonObject json = GSON.fromJson(response.body(), JsonObject.class);

            if (json == null || !json.has("access_token")) {
                return false;
            }

            credentials.accessToken = json.get("access_token").getAsString();

            if (json.has("refresh_token") && !json.get("refresh_token").isJsonNull()) {

                credentials.refreshToken = json.get("refresh_token").getAsString();
            }

            updateExpiration(json);

            credentials.save();

            System.out.println("[Stream Chat Bridge] Kick access token refreshed.");

            return true;

        } catch (Exception e) {
            System.err.println("[Stream Chat Bridge] Kick token refresh failed: " + e.getMessage());

            return false;
        }
    }

    public synchronized String getValidAccessToken() {
        if (!credentials.hasAccessToken()) {
            return null;
        }

        if (credentials.isAccessTokenExpired()) {
            if (!refreshAccessToken()) {
                return null;
            }
        }

        return credentials.accessToken;
    }

    private boolean exchangeCodeForTokens(String code, String codeVerifier) {
        try {
            String body = "grant_type=authorization_code" + "&code=" + encode(code) + "&client_id=" + encode(credentials.clientId) + "&client_secret=" + encode(credentials.clientSecret) + "&redirect_uri=" + encode(REDIRECT_URI) + "&code_verifier=" + encode(codeVerifier);

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(TOKEN_URL)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.err.println("[Stream Chat Bridge] Kick token exchange failed. HTTP " + response.statusCode() + ": " + response.body());

                return false;
            }

            JsonObject json = GSON.fromJson(response.body(), JsonObject.class);

            if (json == null || !json.has("access_token") || !json.has("refresh_token")) {

                return false;
            }

            credentials.accessToken = json.get("access_token").getAsString();

            credentials.refreshToken = json.get("refresh_token").getAsString();

            updateExpiration(json);

            credentials.save();

            System.out.println("[Stream Chat Bridge] Kick tokens obtained.");

            return true;

        } catch (Exception e) {
            System.err.println("[Stream Chat Bridge] Kick token exchange failed: " + e.getMessage());

            return false;
        }
    }

    private boolean loadCurrentUser() {
        String accessToken = getValidAccessToken();

        if (accessToken == null) {
            return false;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(USERS_URL)).header("Authorization", "Bearer " + accessToken).GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.err.println("[Stream Chat Bridge] Kick user lookup failed. HTTP " + response.statusCode() + ": " + response.body());

                return false;
            }

            JsonObject root = GSON.fromJson(response.body(), JsonObject.class);

            if (root == null || !root.has("data") || !root.get("data").isJsonArray() || root.getAsJsonArray("data").isEmpty()) {

                return false;
            }

            JsonObject user = root.getAsJsonArray("data").get(0).getAsJsonObject();

            userId = readId(user, "user_id", "id");

            username = readString(user, "name", "username");

            if (userId == null || username == null) {

                System.err.println("[Stream Chat Bridge] Kick user response did not contain expected account information.");

                return false;
            }

            System.out.println("[Stream Chat Bridge] Kick authenticated as: " + username);

            return true;

        } catch (Exception e) {
            System.err.println("[Stream Chat Bridge] Kick user lookup failed: " + e.getMessage());

            return false;
        }
    }

    public boolean introspectToken() {
        String accessToken = getValidAccessToken();

        if (accessToken == null) {
            return false;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(INTROSPECT_URL)).header("Authorization", "Bearer " + accessToken).POST(HttpRequest.BodyPublishers.noBody()).build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return false;
            }

            JsonObject root = GSON.fromJson(response.body(), JsonObject.class);

            if (root == null || !root.has("data")) {
                return false;
            }

            JsonObject data = root.getAsJsonObject("data");

            return data.has("active") && data.get("active").getAsBoolean();

        } catch (Exception e) {
            return false;
        }
    }

    public void logout() {
        userId = null;
        username = null;

        credentials.clearTokens();
    }

    public void resetClientCredentials() {
        userId = null;
        username = null;

        credentials.clientId = "";
        credentials.clientSecret = "";
        credentials.accessToken = "";
        credentials.refreshToken = "";
        credentials.expiresAt = 0L;

        credentials.save();
    }

    public void setClientCredentials(String clientId, String clientSecret) {
        String normalizedClientId = clientId == null ? "" : clientId.trim();

        String normalizedClientSecret = clientSecret == null ? "" : clientSecret.trim();

        boolean changed = !normalizedClientId.equals(credentials.clientId) || !normalizedClientSecret.equals(credentials.clientSecret);

        credentials.clientId = normalizedClientId;

        credentials.clientSecret = normalizedClientSecret;

        if (changed) {
            credentials.accessToken = "";
            credentials.refreshToken = "";
            credentials.expiresAt = 0L;

            userId = null;
            username = null;
        }

        credentials.save();
    }

    public boolean hasClientCredentials() {
        return credentials.hasClientCredentials();
    }

    public boolean isAuthenticated() {
        return credentials.hasAccessToken() && userId != null && username != null;
    }

    public String getClientId() {
        return credentials.clientId;
    }

    public static String getRedirectUri() {
        return REDIRECT_URI;
    }

    public static String getRequiredScopes() {
        return SCOPES;
    }

    public String getAccessToken() {
        return getValidAccessToken();
    }

    public String getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    private void updateExpiration(JsonObject tokenResponse) {
        if (tokenResponse.has("expires_in") && !tokenResponse.get("expires_in").isJsonNull()) {

            long expiresIn = tokenResponse.get("expires_in").getAsLong();

            credentials.expiresAt = System.currentTimeMillis() + expiresIn * 1_000L;

        } else {
            credentials.expiresAt = 0L;
        }
    }

    private void handleCallback(HttpExchange exchange, String expectedState, CompletableFuture<String> codeFuture) throws IOException {

        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendCallbackResponse(exchange, 405, "Method not allowed.");

                return;
            }

            Map<String, String> parameters = parseQuery(exchange.getRequestURI().getRawQuery());

            String error = parameters.get("error");

            if (error != null) {
                String description = parameters.getOrDefault("error_description", error);

                codeFuture.completeExceptionally(new IOException("Kick authorization failed: " + description));

                sendCallbackResponse(exchange, 400, "Kick authorization failed. You can close this tab.");

                return;
            }

            String state = parameters.get("state");

            if (state == null || !state.equals(expectedState)) {

                codeFuture.completeExceptionally(new IOException("Kick OAuth state did not match"));

                sendCallbackResponse(exchange, 400, "Invalid authentication state. You can close this tab.");

                return;
            }

            String code = parameters.get("code");

            if (code == null || code.isBlank()) {

                codeFuture.completeExceptionally(new IOException("Kick authorization code was missing"));

                sendCallbackResponse(exchange, 400, "Kick did not return an authorization code.");

                return;
            }

            codeFuture.complete(code);

            sendCallbackResponse(exchange, 200, "Kick connected to Stream Chat Bridge. You can close this tab and return to Minecraft.");

        } catch (Exception e) {
            codeFuture.completeExceptionally(e);

            sendCallbackResponse(exchange, 500, "Stream Chat Bridge could not complete Kick authentication.");
        }
    }

    private void sendCallbackResponse(HttpExchange exchange, int status, String message) throws IOException {

        String html = """
                <!doctype html>
                <html>
                <head>
                    <meta charset="utf-8">
                    <title>Stream Chat Bridge</title>
                </head>
                <body style="font-family:sans-serif;background:#111;color:#eee;padding:40px">
                    <h2>Stream Chat Bridge</h2>
                    <p>%s</p>
                </body>
                </html>
                """.formatted(escapeHtml(message));

        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");

        exchange.sendResponseHeaders(status, bytes.length);

        try (OutputStream output = exchange.getResponseBody()) {

            output.write(bytes);
        }
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> result = new HashMap<>();

        if (query == null || query.isBlank()) {
            return result;
        }

        for (String part : query.split("&")) {
            int separator = part.indexOf('=');

            String key;
            String value;

            if (separator >= 0) {
                key = part.substring(0, separator);

                value = part.substring(separator + 1);
            } else {
                key = part;
                value = "";
            }

            result.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }

        return result;
    }

    private static String randomUrlSafeString(int bytes) {
        byte[] data = new byte[bytes];

        RANDOM.nextBytes(data);

        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static String createCodeChallenge(String verifier) throws Exception {

        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));

        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String readId(JsonObject object, String... names) {
        for (String name : names) {
            if (object.has(name) && !object.get(name).isJsonNull()) {

                return object.get(name).getAsString();
            }
        }

        return null;
    }

    private static String readString(JsonObject object, String... names) {
        for (String name : names) {
            if (object.has(name) && !object.get(name).isJsonNull()) {

                String value = object.get(name).getAsString();

                if (!value.isBlank()) {
                    return value;
                }
            }
        }

        return null;
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}