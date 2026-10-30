package bdsm.integration.support;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

public final class Api {

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private Api() {
    }

    public record Response(int status, String text) {

        public JsonObject json() {
            try {
                JsonElement element = JsonParser.parseString(text);
                return element.getAsJsonObject();
            } catch (JsonParseException | IllegalStateException e) {
                throw new AssertionError("Status " + status + " with a body that is not a JSON object: " + text, e);
            }
        }

        public List<Integer> resultIds() {
            if (status != 200) throw new AssertionError("Expected status 200, got " + status + ": " + text);
            List<Integer> ids = new ArrayList<>();
            json().getAsJsonArray("results").forEach(book -> ids.add(book.getAsJsonObject().get("book_id").getAsInt()));
            return ids;
        }
    }

    public static Response get(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build();
        try {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new AssertionError("GET " + url + " failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("GET " + url + " was interrupted", e);
        }
    }

    public static Response search(String... parameters) {
        StringJoiner query = new StringJoiner("&");
        for (int i = 0; i < parameters.length; i += 2) {
            query.add(parameters[i] + "=" + URLEncoder.encode(parameters[i + 1], StandardCharsets.UTF_8));
        }
        return get(Settings.SEARCH_URL + "/search?" + query);
    }

    public static Response book(Object bookId) {
        return get(Settings.SEARCH_URL + "/books/" + bookId);
    }

    public static boolean isSearchable(int bookId) {
        return book(bookId).status() == 200;
    }

    public static long booksIndexed() {
        Response status = get(Settings.INDEXING_URL + "/index/status");
        return status.status() == 200 ? status.json().get("books_indexed").getAsLong() : -1;
    }
}
