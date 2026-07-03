package com.example.curriculumai.controller;

import com.example.curriculumai.service.PdfService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.example.curriculumai.service.GroqService;
import com.example.curriculumai.service.EmbeddingService;
import com.example.curriculumai.service.QdrantService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;


@CrossOrigin(origins = "http://localhost:5173")
@RestController
public class UploadController {

    private final PdfService pdfService;
    private final GroqService groqService;
    private final EmbeddingService embeddingService;
    private final QdrantService qdrantService;

    public UploadController(PdfService pdfService, GroqService groqService, EmbeddingService embeddingService, QdrantService qdrantService) {
        this.pdfService = pdfService;
        this.groqService = groqService;
        this.embeddingService = embeddingService;
        this.qdrantService = qdrantService;
    }

    @GetMapping("/test")
    public String test() {
        return pdfService.checkService();
    }

    @PostMapping("/upload")
    public String uploadPdf(@RequestParam("file") MultipartFile file) {
        try {
            String extractedText = pdfService.extractText(file);

            List<String> chunks = pdfService.splitText(extractedText);

            String source = file.getOriginalFilename();

            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                float[] vector = embeddingService.getEmbedding(chunk);

                // Deterministic ID based on source + chunk index.
                // Re-uploading the same file overwrites the same points
                // in Qdrant instead of inserting duplicates.
                String idSeed = source + "::" + i;
                String id = UUID.nameUUIDFromBytes(idSeed.getBytes(StandardCharsets.UTF_8)).toString();

                qdrantService.storeVector(id, vector, chunk, source);
            }

            return "Extracted text length = " + extractedText.length() +
                    ", Chunks created = " + chunks.size() +
                    ", Source = " + source;

        } catch (IOException e) {
            return "Error processing PDF: " + e.getMessage();
        }
    }

    @GetMapping("/ask")
    public String askQuestion(@RequestParam String question,
                              @RequestParam(required = false) String source) {

        float[] questionVector = embeddingService.getEmbedding(question);
        String matchedChunk = qdrantService.searchVector(questionVector, source);

        if (matchedChunk == null || matchedChunk.isBlank()) {
            return "I could not find this in the uploaded curriculum.";
        }

        return groqService.askWithContext(matchedChunk, question);
    }

    @GetMapping("/groq-test")
    public String testGroq(@RequestParam String prompt) {
        return groqService.askGroq(prompt);
    }

    @GetMapping("/embed-test")
    public String testEmbedding(@RequestParam String text) {

        float[] vector = embeddingService.getEmbedding(text);

        return "Vector size = " + vector.length;
    }

    @GetMapping("/qdrant-test")
    public String testQdrant() {
        return qdrantService.createCollection();
    }
}