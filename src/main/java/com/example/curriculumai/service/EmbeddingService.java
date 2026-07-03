package com.example.curriculumai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

import jakarta.annotation.PostConstruct;

@Service
public class EmbeddingService {

    @Value("${hf.api.key}")
    private String huggingFaceApiKey;

    private RestClient restClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Hugging Face's free serverless models cold-start when idle and
    // return 503 "currently loading" while spinning up. Retrying after
    // a short wait almost always succeeds on the 2nd or 3rd attempt.
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 4000;

    @PostConstruct
    public void initClient() {
        // Hugging Face deprecated api-inference.huggingface.co.
        // All serverless inference now goes through router.huggingface.co
        this.restClient = RestClient.builder()
                .baseUrl("https://router.huggingface.co/hf-inference")
                .defaultHeader("Authorization", "Bearer " + huggingFaceApiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    public float[] getEmbedding(String text) {
        return getEmbeddingWithRetry(text, 1);
    }

    private float[] getEmbeddingWithRetry(String text, int attempt) {
        try {
            Map<String, String> body = Map.of(
                    "inputs", text
            );

            String requestBody = objectMapper.writeValueAsString(body);

            String response = restClient.post()
                    .uri("/models/sentence-transformers/all-MiniLM-L6-v2/pipeline/feature-extraction")
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);

            // Some models return a nested array (e.g. one vector per token,
            // or wrapped one level deeper) instead of a flat float array.
            // Unwrap until we hit a node whose first child is a number.
            JsonNode vectorNode = root;
            while (vectorNode.isArray() && vectorNode.size() > 0
                    && vectorNode.get(0).isArray()) {
                vectorNode = vectorNode.get(0);
            }

            if (!vectorNode.isArray()) {
                throw new RuntimeException("Unexpected embedding response format: " + response);
            }

            float[] vector = new float[vectorNode.size()];
            for (int i = 0; i < vectorNode.size(); i++) {
                vector[i] = (float) vectorNode.get(i).asDouble();
            }

            return vector;

        } catch (HttpServerErrorException e) {
            // 503 = model is cold-starting on Hugging Face's side.
            // Wait and retry rather than failing the whole request.
            boolean isColdStart = e.getStatusCode().value() == 503;

            if (isColdStart && attempt < MAX_ATTEMPTS) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return getEmbeddingWithRetry(text, attempt + 1);
            }

            throw new RuntimeException(
                    "Embedding service unavailable after " + attempt + " attempt(s): "
                            + e.getStatusCode() + " - " + e.getResponseBodyAsString(), e
            );

        } catch (Exception e) {
            throw new RuntimeException("Embedding generation failed: " + e.getMessage(), e);
        }
    }
}