package eu.socle.integrations;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Client HTTP pour le test de connexion SIEM — même sémantique que le worker Go
 * (POST JSON + headers provider). Injectable pour les tests unitaires.
 */
public interface SiemHttpClient {

    record Result(int statusCode, String errorMessage) {
        boolean ok() {
            return errorMessage == null && statusCode >= 200 && statusCode < 300;
        }
    }

    Result post(String url, String jsonBody, Map<String, String> headers);

    final class Jdk implements SiemHttpClient {
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        @Override
        public Result post(String url, String jsonBody, Map<String, String> headers) {
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody != null ? jsonBody : "{}"));
                if (headers != null) {
                    headers.forEach(b::header);
                }
                HttpResponse<Void> resp = client.send(b.build(), HttpResponse.BodyHandlers.discarding());
                int code = resp.statusCode();
                if (code >= 300) {
                    return new Result(code, "HTTP " + code);
                }
                return new Result(code, null);
            } catch (Exception e) {
                return new Result(0, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        }
    }
}
