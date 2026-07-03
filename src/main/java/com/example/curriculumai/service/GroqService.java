package com.example.curriculumai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import jakarta.annotation.PostConstruct;

import java.util.List;
import java.util.Map;

@Service
public class GroqService {

    @Value("${groq.api.key}")
    private String groqApiKey;

    private RestClient restClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void initClient() {
        this.restClient = RestClient.builder()
                .baseUrl("https://api.groq.com/openai/v1")
                .defaultHeader("Authorization", "Bearer " + groqApiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    public String askGroq(String prompt) {
        try {
            Map<String, Object> body = Map.of(
                    "model", "llama-3.3-70b-versatile",
                    "messages", List.of(
                            Map.of("role", "user", "content", prompt)
                    )
            );

            String requestBody = objectMapper.writeValueAsString(body);

            String rawResponse = restClient.post()
                    .uri("/chat/completions")
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(rawResponse);
            JsonNode contentNode = root
                    .path("choices")
                    .path(0)
                    .path("message")
                    .path("content");

            if (contentNode.isMissingNode()) {
                throw new RuntimeException("Unexpected Groq response format: " + rawResponse);
            }

            return contentNode.asText();

        } catch (HttpClientErrorException e) {
            throw new RuntimeException(
                    "Groq API error: " + e.getStatusCode() + " - " + e.getResponseBodyAsString(), e
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to call Groq API", e);
        }
    }

    public String askWithContext(String context, String question) {

        String prompt = """

            You are a college curriculum assistant.

            STRICT RULES:
            1. Answer ONLY from the provided context.
            2. Do NOT use your own knowledge.
            3. Quote numbers, credit hours, and figures EXACTLY as written in the context.
               Do not calculate, round, infer, or guess numeric values.
            4. If answer is not in context, reply:
               "I could not find this in the uploaded curriculum."
            5. Keep answer short (maximum 2 sentences).
            6. Do not explain extra theory.

            Context:
            %s

            Student Question:
            %s

            """.formatted(context, question);

        return askGroq(prompt);
    }
}