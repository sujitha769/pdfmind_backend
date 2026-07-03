# PDFMind — Backend

A Spring Boot RAG (Retrieval-Augmented Generation) API that lets you upload PDFs and ask questions grounded in their content. Answers are generated only from the uploaded documents — not the model's general knowledge.

**Live API:** `https://pdfmind-backend-ldw6.onrender.com`
**Frontend:** [pdfmind123.netlify.app](https://pdfmind123.netlify.app) — [pdfmind_frontend repo](https://github.com/sujitha769/pdfmind_frontend)

## How it works

1. A PDF is uploaded and its text extracted with **PDFBox**
2. The text is split into chunks
3. Each chunk is embedded via **Hugging Face** (`sentence-transformers/all-MiniLM-L6-v2`) and stored in **Qdrant**, tagged with the source filename
4. A question is embedded the same way and matched against stored chunks in Qdrant (optionally filtered to one document)
5. The matched chunks are passed as context to **Groq** (`llama-3.3-70b-versatile`), which answers strictly from that context

## Tech stack

- Java 22, Spring Boot 4.1
- Apache PDFBox — PDF text extraction
- Qdrant — vector database (Qdrant Cloud in production)
- Hugging Face Inference API — embeddings
- Groq API — LLM inference

## Getting started

### Prerequisites

- Java 22
- A [Groq API key](https://console.groq.com)
- A [Hugging Face API key](https://huggingface.co/settings/tokens)
- A Qdrant instance — either [local](https://qdrant.tech/documentation/quick-start/) (`docker run -p 6333:6333 qdrant/qdrant`) or [Qdrant Cloud](https://cloud.qdrant.io)

### Environment variables

| Variable | Description |
|---|---|
| `GROQ_API_KEY` | Groq API key |
| `HF_API_KEY` | Hugging Face API key |
| `QDRANT_URL` | Qdrant instance URL (e.g. `http://localhost:6333` or your Qdrant Cloud cluster URL) |
| `QDRANT_API_KEY` | Qdrant Cloud API key (leave unset for local Qdrant) |

Set these as actual environment variables — `application.properties` references them via `${VAR_NAME}` and never contains real secrets.

### Run locally

```bash
./mvnw spring-boot:run
```

The API starts on `http://localhost:8080`.

### Build a jar

```bash
./mvnw clean package -DskipTests
java -jar target/*.jar
```

## API reference

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/upload` | Upload a PDF (`multipart/form-data`, field name `file`). Extracts, chunks, embeds, and stores it in Qdrant. |
| `GET` | `/ask?question=...&source=...` | Ask a question. `source` (optional) restricts the answer to one uploaded file by its original filename. |
| `GET` | `/test` | Health check for the PDF service. |
| `GET` | `/groq-test?prompt=...` | Send a raw prompt directly to Groq, bypassing retrieval. |
| `GET` | `/embed-test?text=...` | Returns the embedding vector length for a given text. |
| `GET` | `/qdrant-test` | Creates the Qdrant collection (`curriculum`, 384 dims, cosine distance) and its required `source` payload index. |

### Example

```bash
curl -X POST https://pdfmind-backend-ldw6.onrender.com/upload \
  -F "file=@syllabus.pdf"

curl "https://pdfmind-backend-ldw6.onrender.com/ask?question=What+is+the+late+work+penalty&source=syllabus.pdf"
```

## Deployment

Deployed on [Render](https://render.com) as a web service, auto-deploying from `main`.

- **Build command:** `./mvnw clean package -DskipTests`
- **Start command:** `java -jar target/*.jar`
- **Port:** Render assigns a port via the `PORT` env var; `application.properties` binds to it with `server.port=${PORT:8080}`

CORS is restricted to known frontend origins in `UploadController`. Add any new frontend domain to the `@CrossOrigin` list before it can call the API from a browser.

> **Free-tier note:** Both Render's web service and Hugging Face's serverless inference API sleep/cold-start when idle. The first request after a period of inactivity can take 30–60 seconds and occasionally needs a retry — `EmbeddingService` retries automatically on a Hugging Face cold-start response.

## Notes

- Qdrant requires a payload index on any field used in a search filter. If the `curriculum` collection is ever recreated manually, re-create the `source` index:
  ```bash
  curl -X PUT "$QDRANT_URL/collections/curriculum/index" \
    -H "api-key: $QDRANT_API_KEY" \
    -H "Content-Type: application/json" \
    -d '{"field_name": "source", "field_schema": "keyword"}'
  ```
  (`QdrantService.createCollection()` also creates this automatically going forward.)
- Re-uploading a file with the same name overwrites its existing chunks in Qdrant rather than duplicating them (deterministic point IDs derived from `source + chunk index`).
