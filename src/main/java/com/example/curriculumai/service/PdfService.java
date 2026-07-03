package com.example.curriculumai.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PdfService {

    // stores chunks temporarily in memory
    private List<String> chunks;

    // Target chunk size in characters. Bigger than before (was 500) so
    // related facts (e.g. a label and its value) are less likely to get
    // split across two different chunks.
    private static final int CHUNK_SIZE = 900;

    // How many characters of the previous chunk's tail to carry into the
    // next chunk, so context isn't lost right at a chunk boundary.
    private static final int OVERLAP_SIZE = 150;

    // Splits on sentence-ending punctuation followed by whitespace, OR on
    // blank lines (common in PDFs between sections/bullet points).
    private static final Pattern SENTENCE_BOUNDARY =
            Pattern.compile("(?<=[.!?])\\s+|\\n\\s*\\n");

    public String checkService() {
        return "Pdf Service Working";
    }

    // extract text from uploaded PDF
    public String extractText(MultipartFile file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    /**
     * Splits text into chunks along sentence/paragraph boundaries instead
     * of raw character offsets, so a fact like "Score: 928/1000" never
     * gets cut in half or separated from its label. Sentences are packed
     * greedily up to CHUNK_SIZE, with a small overlap carried between
     * consecutive chunks to preserve context at the edges.
     */
    public List<String> splitText(String text) {

        List<String> sentences = splitIntoSentences(text);
        List<String> chunks = new ArrayList<>();

        StringBuilder current = new StringBuilder();

        for (String sentence : sentences) {

            // If adding this sentence would overflow the target size,
            // and we already have content, close out the current chunk.
            if (current.length() > 0 && current.length() + sentence.length() > CHUNK_SIZE) {
                chunks.add(current.toString().trim());

                // Start the next chunk with the tail of the previous one
                // (overlap) so context carries across the boundary.
                String tail = current.length() > OVERLAP_SIZE
                        ? current.substring(current.length() - OVERLAP_SIZE)
                        : current.toString();

                current = new StringBuilder(tail);
            }

            current.append(sentence).append(" ");

            // Safety net: a single "sentence" longer than CHUNK_SIZE
            // (e.g. a huge unbroken line) gets flushed on its own so we
            // never produce a chunk that's absurdly oversized.
            if (current.length() > CHUNK_SIZE * 2) {
                chunks.add(current.toString().trim());
                current = new StringBuilder();
            }
        }

        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }

        // Drop any empty/near-empty chunks that can result from PDFs with
        // lots of blank lines or formatting whitespace.
        chunks.removeIf(c -> c.isBlank() || c.length() < 20);

        return chunks;
    }

    private List<String> splitIntoSentences(String text) {
        List<String> sentences = new ArrayList<>();
        Matcher matcher = SENTENCE_BOUNDARY.matcher(text);

        int lastEnd = 0;
        while (matcher.find()) {
            String sentence = text.substring(lastEnd, matcher.start()).trim();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
            lastEnd = matcher.end();
        }

        String remainder = text.substring(lastEnd).trim();
        if (!remainder.isEmpty()) {
            sentences.add(remainder);
        }

        return sentences;
    }

    // save chunks in memory
    public void saveChunks(List<String> chunks) {
        this.chunks = chunks;
    }

    // return saved chunks
    public List<String> getChunks() {
        return chunks;
    }

    public String searchChunk(String question) {

        for (String chunk : chunks) {
            if (chunk.toLowerCase().contains(question.toLowerCase())) {
                return chunk;
            }
        }

        return "No matching chunk found";
    }
}