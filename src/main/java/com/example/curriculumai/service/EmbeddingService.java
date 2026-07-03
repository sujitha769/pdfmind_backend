package com.example.curriculumai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
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

        } catch (Exception e) {
            throw new RuntimeException("Embedding generation failed", e);
        }
    }
}