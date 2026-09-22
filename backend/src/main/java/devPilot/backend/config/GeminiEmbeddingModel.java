package devPilot.backend.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;

/**
 * Custom EmbeddingModel for Google Gemini featuring automatic 429 rate-limit retries
 * with exponential backoff to handle Gemini Free Tier quotas (100 RPM).
 */
@Component
@Primary
@Slf4j
public class GeminiEmbeddingModel implements EmbeddingModel {

    private static final int DIMENSIONS = 768;
    // 3 concurrent threads keeps us safely within Gemini Free Tier rate limits (100 RPM)
    private final ExecutorService executorService = Executors.newFixedThreadPool(3);

    private final RestClient restClient;
    private final String apiKey;

    public GeminiEmbeddingModel(
            RestClient.Builder restClientBuilder,
            @Value("${spring.ai.openai.api-key:}") String apiKey) {
        this.restClient = restClientBuilder.build();
        this.apiKey = apiKey;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        List<CompletableFuture<Embedding>> futures = new ArrayList<>();

        for (int i = 0; i < texts.size(); i++) {
            final int index = i;
            final String text = texts.get(i);
            futures.add(CompletableFuture.supplyAsync(
                    () -> new Embedding(fetchGeminiEmbedding(text), index),
                    executorService));
        }

        List<Embedding> embeddings = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return fetchGeminiEmbedding(document.getFormattedContent());
    }

    @Override
    public float[] embed(String text) {
        return fetchGeminiEmbedding(text);
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    @SuppressWarnings("unchecked")
    private float[] fetchGeminiEmbedding(String text) {
        int maxRetries = 5;
        long waitMs = 2500;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:embedContent?key=" + apiKey;

                Map<String, Object> body = Map.of(
                        "content", Map.of(
                                "parts", List.of(Map.of("text", text))
                        ),
                        "outputDimensionality", DIMENSIONS
                );

                Map<String, Object> response = restClient.post()
                        .uri(url)
                        .body(body)
                        .retrieve()
                        .body(Map.class);

                if (response != null && response.containsKey("embedding")) {
                    Map<String, Object> embeddingMap = (Map<String, Object>) response.get("embedding");
                    List<Number> values = (List<Number>) embeddingMap.get("values");
                    if (values != null) {
                        float[] floatArray = new float[values.size()];
                        for (int j = 0; j < values.size(); j++) {
                            floatArray[j] = values.get(j).floatValue();
                        }
                        return floatArray;
                    }
                }
            } catch (Exception ex) {
                String msg = ex.getMessage();
                boolean isRateLimit = msg != null && (msg.contains("429") || msg.contains("RESOURCE_EXHAUSTED") || msg.contains("Quota exceeded"));

                if (isRateLimit && attempt < maxRetries) {
                    log.warn("Gemini rate limit hit (429). Retrying attempt {}/{} in {}ms...", attempt, maxRetries, waitMs);
                    try {
                        Thread.sleep(waitMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted during rate limit retry", ie);
                    }
                    waitMs *= 2; // Exponential backoff (2.5s -> 5s -> 10s...)
                } else if (attempt == maxRetries) {
                    log.error("Failed to fetch Gemini embedding after {} attempts", maxRetries, ex);
                    throw new IllegalStateException("Failed to fetch Gemini embedding after retries: " + ex.getMessage(), ex);
                } else {
                    log.error("Error fetching Gemini embedding", ex);
                    throw new IllegalStateException("Failed to fetch Gemini embedding: " + ex.getMessage(), ex);
                }
            }
        }

        return new float[DIMENSIONS];
    }
}
