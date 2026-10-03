package net.kdt.pojavlaunch.content;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import net.kdt.pojavlaunch.Tools;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Map;

/**
 * Small JSON-over-HTTP client for the content APIs.
 * Unlike the old ApiHandler it sends a User-Agent (Modrinth asks for one), uses connect/read
 * timeouts (a dead connection can no longer hang forever) and retries transient failures.
 */
public final class Http {
    public static final String USER_AGENT = "MojoLauncher/content-system (github.com/hackfireoffical/MojoLauncher)";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 25000;
    private static final int MAX_ATTEMPTS = 3;

    private Http() {}

    public static class HttpStatusException extends IOException {
        public final int code;

        public HttpStatusException(int code, String url) {
            super("HTTP " + code + " for " + url);
            this.code = code;
        }
    }

    public static JsonElement getJson(String url, Map<String, String> headers) throws IOException {
        IOException lastError = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Interrupted");
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("Accept", "application/json");
                if (headers != null) {
                    for (Map.Entry<String, String> header : headers.entrySet()) {
                        connection.setRequestProperty(header.getKey(), header.getValue());
                    }
                }
                int code = connection.getResponseCode();
                if (code >= 200 && code < 300) {
                    try (InputStream in = connection.getInputStream()) {
                        return JsonParser.parseString(Tools.read(in));
                    } catch (RuntimeException e) {
                        throw new IOException("Malformed response from " + url, e);
                    }
                }
                throw new HttpStatusException(code, url);
            } catch (HttpStatusException e) {
                // Client errors (404, 403...) will not fix themselves; 429 and 5xx might.
                if (e.code != 429 && e.code < 500) throw e;
                lastError = e;
            } catch (IOException e) {
                lastError = e;
            } finally {
                if (connection != null) connection.disconnect();
            }
            if (attempt < MAX_ATTEMPTS - 1) {
                try {
                    Thread.sleep(700L * (attempt + 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted", e);
                }
            }
        }
        throw lastError != null ? lastError : new IOException("Request failed: " + url);
    }

    public static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is required", e);
        }
    }

    /** Wraps a value in double quotes (used for Modrinth's JSON-array query parameters). */
    public static String quoted(String value) {
        return new StringBuilder().append((char) 34).append(value).append((char) 34).toString();
    }
}
