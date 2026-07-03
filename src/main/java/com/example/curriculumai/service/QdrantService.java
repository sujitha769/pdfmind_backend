package com.example.curriculumai.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;

@Service
public class QdrantService {

    // Defaults to local Qdrant with no auth if env vars aren't set, so
    // local dev keeps working exactly as before. In production (Render),
    // QDRANT_URL and QDRANT_API_KEY point at your Qdrant Cloud cluster.
    @Value("${qdrant.url}")
    private String qdrantUrl;

    @Value("${qdrant.api.key}")
    private String qdrantApiKey;

    private RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Increased from 5 -> 8. Dense documents (resumes, syllabi) can have
    // the relevant fact ranked outside the top 5; more candidates gives
    // Groq a better chance of actually seeing it.
    private static final int SEARCH_LIMIT = 8;

    @PostConstruct
    public void initClient() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(qdrantUrl)
                .defaultHeader("Content-Type", "application/json");

        // Qdrant Cloud requires this header. Local Qdrant ignores it if
        // present, so it's safe to always set it when a key exists.
        if (qdrantApiKey != null && !qdrantApiKey.isBlank()) {
            builder.defaultHeader("api-key", qdrantApiKey);
        }

        this.restClient = builder.build();
    }

    public String createCollection() {

        String requestBody = """
            {
              "vectors": {
                "size": 384,
                "distance": "Cosine"
              }
            }
            """;

        String response = restClient.put()
                .uri("/collections/curriculum")
                .body(requestBody)
                .retrieve()
                .body(String.class);

        return response;
    }

    public String storeVector(String id, float[] vector, String chunk, String source) {

        try {

            Map<String, Object> body = Map.of(
                    "points", new Object[]{
                            Map.of(
                                    "id", id,
                                    "vector", vector,
                                    "payload", Map.of(
                                            "text", chunk,
                                            "source", source
                                    )
                            )
                    }
            );

            String requestBody = objectMapper.writeValueAsString(body);

            String response = restClient.put()
                    .uri("/collections/curriculum/points")
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            return response;

        } catch (Exception e) {
            throw new RuntimeException("Failed storing vector in Qdrant", e);
        }
    }

    // Returns the top N matching chunks joined together, deduplicated
    // in case the same chunk text was stored more than once.
    public String searchVector(float[] vector, String sourceFilter) {

        try {

            Map<String, Object> body;

            if (sourceFilter != null) {
                body = Map.of(
                        "vector", vector,
                        "limit", SEARCH_LIMIT,
                        "with_payload", true,
                        "filter", Map.of(
                                "must", List.of(
                                        Map.of(
                                                "key", "source",
                                                "match", Map.of("value", sourceFilter)
                                        )
                                )
                        )
                );
            } else {
                body = Map.of(
                        "vector", vector,
                        "limit", SEARCH_LIMIT,
                        "with_payload", true
                );
            }

            String requestBody = objectMapper.writeValueAsString(body);

            String response = restClient.post()
                    .uri("/collections/curriculum/points/search")
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode resultArray = root.path("result");

            if (!resultArray.isArray() || resultArray.isEmpty()) {
                return "";
            }

            // LinkedHashSet preserves order (best match first) while
            // dropping exact-duplicate chunk text returned by Qdrant.
            Set<String> seenChunks = new LinkedHashSet<>();

            for (JsonNode point : resultArray) {
                String text = point.path("payload").path("text").asText();
                if (text != null && !text.isBlank()) {
                    seenChunks.add(text.trim());
                }
            }

            StringBuilder combinedContext = new StringBuilder();
            for (String text : seenChunks) {
                combinedContext.append(text).append("\n---\n");
            }

            return combinedContext.toString();

        } catch (Exception e) {
            throw new RuntimeException("Qdrant search failed", e);
        }
    }

}