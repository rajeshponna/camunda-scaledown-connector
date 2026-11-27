package io.github.rajeshponna.scaledown;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.error.ConnectorRetryException;
import io.github.rajeshponna.scaledown.client.ScaleDownErrors;
import io.github.rajeshponna.scaledown.document.DocumentEncoder;
import io.github.rajeshponna.scaledown.model.Operation;
import io.github.rajeshponna.scaledown.model.ScaleDownRequest;
import io.github.rajeshponna.scaledown.model.ScaleDownRequest.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScaleDownConnectorFunctionTest {

  static final String KEY = "sk-test-123";

  MockScaleDown server;
  ScaleDownConnectorFunction function;
  ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  @BeforeEach
  void setUp() throws Exception {
    server = new MockScaleDown();
    function = new ScaleDownConnectorFunction(HttpClient.newHttpClient(), mapper, new DocumentEncoder());
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  // ---------- helpers

  ScaleDownRequest req(Operation op, Input input, Object section, ReviewOptions review) {
    return req(op, input, section, review, new ConnectionOptions(server.baseUrl(), 10, false, false));
  }

  ScaleDownRequest req(Operation op, Input input, Object section, ReviewOptions review, ConnectionOptions conn) {
    return new ScaleDownRequest(
        op,
        new Authentication(KEY),
        input,
        section instanceof ClassifyOptions c ? c : null,
        section instanceof DecisionsOptions d ? d : null,
        section instanceof ExtractOptions e ? e : null,
        section instanceof SummarizeOptions s ? s : null,
        section instanceof CompressOptions c ? c : null,
        review,
        conn);
  }

  static Input text(String t) {
    return new Input("text", t, null, null, null);
  }

  static Map<String, Object> labels() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("billing", "Is this about a billing issue?");
    m.put("technical", "Is this about a bug?");
    return m;
  }

  static final String CLASSIFY_OK = """
      {"top_label":"billing","scores":{"billing":0.91,"technical":0.09},
       "labels":[],"results":[{"id":"0","top_label":"billing","matched_labels":[],"scores":{"billing":0.91,"technical":0.09}}],
       "chunk_count":1,"ocr_text":null,"reasoning":"Mentions a double charge."}""";

  static ConnectorException expectError(String code, Runnable r) {
    ConnectorException e = assertThrows(ConnectorException.class, r::run);
    assertEquals(code, e.getErrorCode(), e.getMessage());
    return e;
  }

  // ---------- classify

  @Test
  void classifyTextBuildsRequestAndNormalizesResponse() {
    server.reply("/classify", 200, CLASSIFY_OK);
    var out = function.execute(req(Operation.CLASSIFY, text("I was charged twice"),
        new ClassifyOptions(labels(), false, null, null), null));

    var sent = server.last();
    assertEquals("/classify", sent.path());
    assertEquals(KEY, sent.header("x-api-key"));
    assertEquals("I was charged twice", sent.body().path("text").asText());
    assertEquals("billing", sent.body().path("labels").path(0).path("name").asText());
    assertEquals("Is this about a billing issue?", sent.body().path("labels").path(0).path("rubric").asText());
    assertFalse(sent.body().path("multi_label").asBoolean());
    assertTrue(sent.body().path("reasoning").asBoolean(), "reasoning defaults to true");
    assertTrue(sent.body().path("threshold").isMissingNode());

    assertEquals("classify", out.get("operation"));
    assertEquals("billing", out.get("topLabel"));
    assertEquals(0.91, (Double) out.get("confidence"), 1e-9);
    assertEquals("Mentions a double charge.", out.get("reasoning"));
    assertEquals(false, out.get("needsReview"));
    assertFalse(out.containsKey("raw"));
    assertFalse(out.containsKey("ocrText"));
  }

  @Test
  void classifyAcceptsListOfLabelsAndJsonString() {
    server.reply("/classify", 200, CLASSIFY_OK);
    function.execute(req(Operation.CLASSIFY, text("x"),
        new ClassifyOptions("[{\"name\":\"billing\",\"rubric\":\"Billing?\"}]", false, null, false), null));
    var body = server.last().body();
    assertEquals("Billing?", body.path("labels").path(0).path("rubric").asText());
    assertFalse(body.path("reasoning").asBoolean());
  }

  @Test
  void classifyFlagsLowConfidence() {
    server.reply("/classify", 200, CLASSIFY_OK);
    var out = function.execute(req(Operation.CLASSIFY, text("x"),
        new ClassifyOptions(labels(), false, null, null), new ReviewOptions(0.95, "flag")));
    assertEquals(true, out.get("needsReview"));
    assertEquals(List.of("billing"), out.get("lowConfidence"));
  }

  @Test
  void classifyLowConfidenceCanFailWithErrorCode() {
    server.reply("/classify", 200, CLASSIFY_OK);
    var e = expectError(ScaleDownErrors.LOW_CONFIDENCE, () -> function.execute(req(Operation.CLASSIFY, text("x"),
        new ClassifyOptions(labels(), false, null, null), new ReviewOptions(0.95, "error"))));
    assertEquals(0, ((ConnectorRetryException) e).getRetries(), "low confidence must not retry");
    assertEquals(List.of("billing"), e.getErrorVariables().get("lowConfidence"));
  }

  @Test
  void classifyMultiLabelSendsThresholdAndReturnsMatches() {
    server.reply("/classify", 200, """
        {"top_label":"billing","scores":{"billing":0.8,"technical":0.6},
         "results":[{"id":"0","top_label":"billing","matched_labels":["billing","technical"],"scores":{}}]}""");
    var out = function.execute(req(Operation.CLASSIFY, text("x"),
        new ClassifyOptions(labels(), true, 0.55, true), new ReviewOptions(0.5, "flag")));
    var body = server.last().body();
    assertTrue(body.path("multi_label").asBoolean());
    assertEquals(0.55, body.path("threshold").asDouble(), 1e-9);
    assertTrue(body.path("reasoning").isMissingNode(), "reasoning is single-label only");
    assertEquals(List.of("billing", "technical"), out.get("matchedLabels"));
    assertEquals(false, out.get("needsReview"));
  }

  @Test
  void classifyBatchChunks() {
    server.reply("/classify", 200, """
        {"top_label":"billing","scores":{},"chunk_count":2,"results":[
          {"id":"1","top_label":"billing","matched_labels":[],"scores":{"billing":0.9,"technical":0.1}},
          {"id":"b","top_label":"technical","matched_labels":[],"scores":{"billing":0.45,"technical":0.55}}]}""");
    var chunks = List.of("first text", Map.of("id", "b", "text", "second text"));
    var out = function.execute(req(Operation.CLASSIFY, new Input("chunks", null, null, null, chunks),
        new ClassifyOptions(labels(), false, null, null), new ReviewOptions(0.7, "flag")));
    var sentChunks = server.last().body().path("chunks");
    assertEquals("1", sentChunks.path(0).path("id").asText());
    assertEquals("first text", sentChunks.path(0).path("text").asText());
    assertEquals("b", sentChunks.path(1).path("id").asText());
    assertEquals(2, ((List<?>) out.get("results")).size());
    assertEquals(List.of("b"), out.get("lowConfidence"));
    assertEquals(2, out.get("chunkCount"));
  }

  @Test
  void classifyDocumentSendsBase64AndMimeType() {
    server.reply("/classify", 200, CLASSIFY_OK.replace("\"ocr_text\":null", "\"ocr_text\":\"INVOICE 42\""));
    var doc = new TestDocument("%PDF-1.7 fake".getBytes(StandardCharsets.UTF_8), "application/pdf", "invoice.pdf");
    var out = function.execute(req(Operation.CLASSIFY, new Input("document", null, doc, null, null),
        new ClassifyOptions(labels(), false, null, null), null,
        new ConnectionOptions(server.baseUrl(), 10, true, true)));
    var body = server.last().body();
    assertEquals(doc.asBase64(), body.path("document").asText());
    assertEquals("application/pdf", body.path("document_mime_type").asText());
    assertTrue(body.path("text").isMissingNode());
    assertEquals("INVOICE 42", out.get("ocrText"));
    assertNotNull(out.get("raw"));
  }

  @Test
  void classifyRejectsTooManyLabelsBeforeCallingApi() {
    Map<String, Object> many = new LinkedHashMap<>();
    for (int i = 0; i < 21; i++) {
      many.put("l" + i, "q" + i + "?");
    }
    expectError(ScaleDownErrors.INVALID_INPUT, () -> function.execute(req(Operation.CLASSIFY, text("x"),
        new ClassifyOptions(many, false, null, null), null)));
    assertTrue(server.requests.isEmpty());
  }

  @Test
  void classifyRejectsLabelWithoutRubric() {
    expectError(ScaleDownErrors.INVALID_INPUT, () -> function.execute(req(Operation.CLASSIFY, text("x"),
        new ClassifyOptions(List.of("billing"), false, null, null), null)));
  }

  // ---------- decisions

  static Map<String, Object> questions() {
    Map<String, Object> q = new LinkedHashMap<>();
    q.put("category", Map.of("type", "choice", "criteria", Map.of("billing", "Payment", "technical", "Bug")));
    q.put("churnRisk", Map.of("type", "noul", "instructions", "Leaving?"));
    q.put("severity", Map.of("type", "score", "criteria", List.of("Low", "Medium", "High")));
    return q;
  }

  @Test
  void decisionsMapsChoiceNoulAndScore() {
    server.reply("/v1/scaledown", 200, """
        {"model":"classify-1","answers":{
          "category":{"choice":"billing","probabilities":{"billing":0.88,"technical":0.12},"confidence":0.88},
          "churnRisk":{"noul":0.2},
          "severity":{"score":1.4,"confidence":0.6,"legend":["Low","Medium","High"],"probabilities":[0.1,0.4,0.5]}},
         "usage":{"input_tokens":120,"output_tokens":0,"cost":0.00001}}""");
    var out = function.execute(req(Operation.DECISIONS, text("Charged twice, fix it or I cancel"),
        new DecisionsOptions(null, questions()), new ReviewOptions(0.7, "flag")));

    var body = server.last().body();
    assertEquals("classify-1", body.path("model").asText(), "defaults to the pinned model");
    assertEquals("Charged twice, fix it or I cancel", body.path("state").path("text").asText());
    assertEquals("noul", body.path("questions").path("churnRisk").path("type").asText());

    @SuppressWarnings("unchecked") var decisions = (Map<String, Object>) out.get("decisions");
    @SuppressWarnings("unchecked") var conf = (Map<String, Double>) out.get("confidences");
    @SuppressWarnings("unchecked") var flags = (Map<String, Boolean>) out.get("flags");
    assertEquals("billing", decisions.get("category"));
    assertEquals(0.2, (Double) decisions.get("churnRisk"), 1e-9);
    assertEquals(0.8, conf.get("churnRisk"), 1e-9, "a 0.2 yes is a confident no");
    assertEquals(false, flags.get("churnRisk"));
    assertEquals(1.4, (Double) decisions.get("severity"), 1e-9);
    assertEquals(List.of("severity"), out.get("lowConfidence"));
    assertEquals(true, out.get("needsReview"));
    assertNotNull(out.get("usage"));
  }

  @Test
  void decisionsValidatesQuestionShapes() {
    expectError(ScaleDownErrors.INVALID_INPUT, () -> function.execute(req(Operation.DECISIONS, text("x"),
        new DecisionsOptions(null, Map.of("q", Map.of("type", "maybe"))), null)));
    expectError(ScaleDownErrors.INVALID_INPUT, () -> function.execute(req(Operation.DECISIONS, text("x"),
        new DecisionsOptions(null, Map.of("q", Map.of("type", "score", "criteria", List.of("only one")))), null)));
    expectError(ScaleDownErrors.INVALID_INPUT, () -> function.execute(req(Operation.DECISIONS, text("x"),
        new DecisionsOptions(null, Map.of("q", Map.of("type", "choice"))), null)));
    expectError(ScaleDownErrors.INVALID_INPUT, () -> function.execute(req(Operation.DECISIONS,
        new Input("chunks", null, null, null, List.of("a")), new DecisionsOptions(null, questions()), null)));
    assertTrue(server.requests.isEmpty());
  }

  // ---------- extract

  @Test
  void extractFieldsModeFlattensBestValues() throws Exception {
    String content = mapper.writeValueAsString(Map.of(
        "entities", List.of(
            Map.of("text", "Acme Corp", "type", "vendor", "confidence", 0.97, "start", 0, "end", 9, "context", "Acme Corp invoiced"),
            Map.of("text", "$500", "type", "totalAmount", "confidence", 0.55, "start", 19, "end", 23, "context", "$500 on"),
            Map.of("text", "$50", "type", "totalAmount", "confidence", 0.41, "start", 40, "end", 43, "context", "tax $50")),
        "structured_result", Map.of(),
        "input_tokens", 42));
    server.reply("/v1/chat/completions", 200, mapper.writeValueAsString(Map.of(
        "id", "x", "object", "chat.completion",
        "choices", List.of(Map.of("index", 0, "message", Map.of("role", "assistant", "content", content))),
        "usage", Map.of("prompt_tokens", 42, "completion_tokens", 0, "total_tokens", 42))));

    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("vendor", "Company that issued the invoice");
    fields.put("totalAmount", "Total amount due");
    fields.put("invoiceDate", "Invoice date");
    var out = function.execute(req(Operation.EXTRACT, text("Acme Corp invoiced $500 on ..."),
        new ExtractOptions(null, "fields", fields, null, "Amounts include currency.", 0.3, 2, 0),
        new ReviewOptions(0.6, "flag")));

    JsonNode body = server.last().body();
    assertEquals("extract-1", body.path("model").asText());
    assertEquals("system", body.path("messages").path(0).path("role").asText());
    assertEquals("Acme Corp invoiced $500 on ...", body.path("messages").path(1).path("content").asText());
    JsonNode schema = body.path("response_format").path("json_schema").path("schema");
    assertEquals("json_schema", body.path("response_format").path("type").asText());
    assertEquals("Total amount due", schema.path("properties").path("totalAmount").path("description").asText());
    assertEquals(0.3, body.path("threshold").asDouble(), 1e-9);
    assertEquals(2, body.path("top_n").asInt());
    assertEquals(0, body.path("context_chars").asInt());

    @SuppressWarnings("unchecked") var f = (Map<String, Object>) out.get("fields");
    assertEquals("Acme Corp", f.get("vendor"));
    assertEquals("$500", f.get("totalAmount"), "highest-confidence match wins");
    assertEquals(List.of("invoiceDate"), out.get("missingFields"));
    assertEquals(List.of("totalAmount"), out.get("lowConfidence"));
    assertEquals(42, out.get("inputTokens"));
  }

  @Test
  void extractJsonSchemaModeSurfacesStructuredResult() throws Exception {
    String content = mapper.writeValueAsString(Map.of(
        "entities", List.of(),
        "structured_result", Map.of("lineItems", List.of(Map.of("description", "Brake pads", "amount", 120)))));
    server.reply("/v1/chat/completions", 200, mapper.writeValueAsString(Map.of(
        "choices", List.of(Map.of("message", Map.of("content", content))))));
    Map<String, Object> schema = Map.of("properties", Map.of("lineItems", Map.of("type", "array",
        "items", Map.of("type", "object", "properties", Map.of("description", Map.of("type", "string"))))));
    var doc = new TestDocument(new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2}, null, "estimate.JPG");

    var out = function.execute(req(Operation.EXTRACT, new Input("document", null, doc, null, null),
        new ExtractOptions("extract", "jsonSchema", null, schema, null, null, null, null), null));

    JsonNode body = server.last().body();
    assertEquals("image/jpeg", body.path("document_mime_type").asText(), "falls back to the file extension");
    assertEquals("Extract the requested fields from the attached document.",
            body.path("messages").path(0).path("content").asText(),
            "ScaleDown rejects an empty user message, so a document-only call sends an instruction");
    assertEquals("object", body.path("response_format").path("json_schema").path("schema").path("type").asText());
    @SuppressWarnings("unchecked") var f = (Map<String, Object>) out.get("fields");
    assertInstanceOf(List.class, f.get("lineItems"));
    assertEquals(List.of(), out.get("missingFields"));
  }

  @Test
  void extractRejectsRefInSchema() {
    Map<String, Object> schema = Map.of("properties", Map.of("a", Map.of("$ref", "#/$defs/x")));
    expectError(ScaleDownErrors.INVALID_INPUT, () -> function.execute(req(Operation.EXTRACT, text("x"),
        new ExtractOptions(null, "jsonSchema", null, schema, null, null, null, null), null)));
  }

  // ---------- summarize

  @Test
  void summarizeDocumentWithInstructionAndTruncation() throws Exception {
    String content = mapper.writeValueAsString(Map.of(
        "summary", "- Claim for rear bumper\n- Estimate 45,000", "input_chars", 9000, "output_chars", 40,
        "latency_ms", 830, "input_tokens", 2100, "finish_reason", "length", "notice", "Output truncated"));
    server.reply("/v1/chat/completions", 200, mapper.writeValueAsString(Map.of(
        "choices", List.of(Map.of("message", Map.of("content", content), "finish_reason", "length")))));
    var doc = new TestDocument("%PDF".getBytes(StandardCharsets.UTF_8), "application/pdf; charset=binary", "claim.pdf");

    var out = function.execute(req(Operation.SUMMARIZE, new Input("document", null, doc, null, null),
        new SummarizeOptions(null, "Use bullet points.", 300), null));

    JsonNode body = server.last().body();
    assertEquals("summarize-1", body.path("model").asText());
    assertEquals("Use bullet points.", body.path("messages").path(0).path("content").asText());
    assertEquals("Summarize the attached document.", body.path("messages").path(1).path("content").asText());
    assertEquals(300, body.path("max_tokens").asInt());
    assertEquals("application/pdf", body.path("document_mime_type").asText());
    assertEquals("- Claim for rear bumper\n- Estimate 45,000", out.get("summary"));
    assertEquals(true, out.get("truncated"));
    assertEquals(830, out.get("latencyMs"));
  }

  @Test
  void summarizeRejectsMaxTokensAboveCap() {
    expectError(ScaleDownErrors.INVALID_INPUT, () -> function.execute(req(Operation.SUMMARIZE, text("x"),
        new SummarizeOptions(null, null, 5000), null)));
  }

  // ---------- compress

  @Test
  void compressDefaultsToAutoAndReportsSavings() {
    server.reply("/compress/raw/", 200, """
        {"results":{"success":true,"compressed_prompt":"short ctx","original_prompt":"Q?",
          "original_prompt_tokens":1000,"compressed_prompt_tokens":350,"compression_ratio":0.35},
         "model_used":"gpt-4o","latency_ms":210}""");
    var out = function.execute(req(Operation.COMPRESS, null,
        new CompressOptions("very long context", "Q?", null), null));
    var body = server.last().body();
    assertEquals("auto", body.path("scaledown").path("rate").asText());
    assertEquals("short ctx", out.get("compressedPrompt"));
    assertEquals(650, out.get("tokensSaved"));
    assertEquals(0.35, (Double) out.get("compressionRatio"), 1e-9);
  }

  @Test
  void compressParsesNumericRateAndHandlesArrayResults() {
    server.reply("/compress/raw/", 200, """
        {"results":[{"success":true,"compressed_prompt":"c","original_prompt":"p",
          "original_prompt_tokens":10,"compressed_prompt_tokens":4}]}""");
    var out = function.execute(req(Operation.COMPRESS, null, new CompressOptions("ctx", "p", "0.6"), null));
    assertEquals(0.6, server.last().body().path("scaledown").path("rate").asDouble(), 1e-9);
    assertEquals(6, out.get("tokensSaved"));
  }

  @Test
  void compressRejectsBadRate() {
    expectError(ScaleDownErrors.INVALID_INPUT, () -> function.execute(req(Operation.COMPRESS, null,
        new CompressOptions("ctx", "p", "1.5"), null)));
    expectError(ScaleDownErrors.INVALID_INPUT, () -> function.execute(req(Operation.COMPRESS, null,
        new CompressOptions("ctx", "p", "fast"), null)));
  }

  // ---------- errors and transport

  @Test
  void unauthorizedFailsFastWithoutRetries() {
    server.reply("/classify", 401, "{\"detail\":\"Missing or invalid x-api-key\"}");
    var e = expectError(ScaleDownErrors.UNAUTHORIZED, () -> function.execute(req(Operation.CLASSIFY, text("x"),
        new ClassifyOptions(labels(), false, null, null), null)));
    assertEquals(0, ((ConnectorRetryException) e).getRetries());
    assertEquals(401, e.getErrorVariables().get("httpStatus"));
    assertFalse(e.getMessage().contains(KEY), "API key must never appear in error messages");
  }

  @Test
  void insufficientCreditsAndInvalidRequestFailFast() {
    server.reply("/classify", 402, "{\"detail\":\"Insufficient credits\"}");
    var e = expectError(ScaleDownErrors.INSUFFICIENT_CREDITS, () -> function.execute(req(Operation.CLASSIFY,
        text("x"), new ClassifyOptions(labels(), false, null, null), null)));
    assertEquals(0, ((ConnectorRetryException) e).getRetries());
    server.reply("/classify", 422, "{\"detail\":\"unreadable document\"}");
    expectError(ScaleDownErrors.INVALID_REQUEST, () -> function.execute(req(Operation.CLASSIFY,
        text("x"), new ClassifyOptions(labels(), false, null, null), null)));
  }

  @Test
  void rateLimitIsRetryableAndHonoursRetryAfter() {
    server.reply("/classify", 429, "{\"detail\":\"slow down\"}", Map.of("Retry-After", "7"));
    var e = expectError(ScaleDownErrors.RATE_LIMITED, () -> function.execute(req(Operation.CLASSIFY, text("x"),
        new ClassifyOptions(labels(), false, null, null), null)));
    var retry = (ConnectorRetryException) e;
    assertNull(retry.getRetries(), "uses the job's remaining retries");
    assertEquals(Duration.ofSeconds(7), retry.getBackoffDuration());
  }

  @Test
  void serverErrorsAreRetryable() {
    server.reply("/classify", 502, "{\"detail\":\"model down\"}");
    var e = expectError(ScaleDownErrors.UNAVAILABLE, () -> function.execute(req(Operation.CLASSIFY, text("x"),
        new ClassifyOptions(labels(), false, null, null), null)));
    assertNull(((ConnectorRetryException) e).getRetries());
    server.reply("/classify", 504, "{}");
    expectError(ScaleDownErrors.TIMEOUT, () -> function.execute(req(Operation.CLASSIFY, text("x"),
        new ClassifyOptions(labels(), false, null, null), null)));
  }

  @Test
  void clientTimeoutIsRetryable() {
    server.replySlow("/classify", CLASSIFY_OK, 2500);
    var e = expectError(ScaleDownErrors.TIMEOUT, () -> function.execute(req(Operation.CLASSIFY, text("x"),
        new ClassifyOptions(labels(), false, null, null), null,
        new ConnectionOptions(server.baseUrl(), 1, false, false))));
    assertNull(((ConnectorRetryException) e).getRetries());
  }

  @Test
  void invalidJsonResponseIsReported() {
    server.reply("/classify", 200, "<html>gateway</html>");
    expectError(ScaleDownErrors.INVALID_RESPONSE, () -> function.execute(req(Operation.CLASSIFY, text("x"),
        new ClassifyOptions(labels(), false, null, null), null)));
  }

  @Test
  void plainHttpIsOnlyAllowedForLocalhost() {
    expectError(ScaleDownErrors.INVALID_INPUT, () -> function.execute(req(Operation.CLASSIFY, text("x"),
        new ClassifyOptions(labels(), false, null, null), null,
        new ConnectionOptions("http://api.scaledown.xyz", 10, false, false))));
  }

  @Test
  void missingApiKeyAndBadInputsFailFast() {
    var noKey = new ScaleDownRequest(Operation.CLASSIFY, new Authentication(" "), text("x"),
        new ClassifyOptions(labels(), false, null, null), null, null, null, null, null, null);
    expectError(ScaleDownErrors.INVALID_INPUT, () -> function.execute(noKey));
    expectError(ScaleDownErrors.INVALID_INPUT, () -> function.execute(req(Operation.CLASSIFY, text("  "),
        new ClassifyOptions(labels(), false, null, null), null)));
    expectError(ScaleDownErrors.INVALID_INPUT, () -> function.execute(req(Operation.CLASSIFY, text("x"),
        new ClassifyOptions(labels(), false, null, null), new ReviewOptions(1.5, "flag"))));
  }

  @Test
  void unsupportedDocumentTypeIsRejected() {
    var doc = new TestDocument("hello".getBytes(StandardCharsets.UTF_8), "text/plain", "notes.txt");
    expectError(ScaleDownErrors.UNSUPPORTED_DOCUMENT, () -> function.execute(req(Operation.SUMMARIZE,
        new Input("document", null, doc, null, null), new SummarizeOptions(null, null, null), null)));
    var big = new TestDocument(new byte[2048], "application/pdf", "big.pdf");
    var small = new ScaleDownConnectorFunction(HttpClient.newHttpClient(), mapper, new DocumentEncoder(1024));
    expectError(ScaleDownErrors.UNSUPPORTED_DOCUMENT, () -> small.execute(req(Operation.SUMMARIZE,
        new Input("document", null, big, null, null), new SummarizeOptions(null, null, null), null)));
    assertTrue(server.requests.isEmpty());
  }

  // ---------- binding (what the Connectors runtime hands us)

  @Test
  void bindsTemplateVariablesIncludingStringNumbers() throws Exception {
    server.reply("/classify", 200, CLASSIFY_OK);
    String variables = """
        {"operation":"classify",
         "authentication":{"apiKey":"sk-test-123"},
         "input":{"source":"text","text":"I was charged twice"},
         "classify":{"labels":{"billing":"Billing?","technical":"Bug?"},"multiLabel":false,"reasoning":true},
         "review":{"minConfidence":"0.5","onLowConfidence":"flag"},
         "connection":{"baseUrl":"%s","timeoutSeconds":"30","includeOcrText":false,"includeRawResponse":false}}
        """.formatted(server.baseUrl());
    var context = new TestContext(mapper, variables);
    @SuppressWarnings("unchecked")
    var out = (Map<String, Object>) function.execute(context);
    assertEquals("billing", out.get("topLabel"));
    assertEquals(false, out.get("needsReview"));
  }

  @Test
  void unknownOperationIsAnInputError() {
    var context = new TestContext(mapper, "{\"operation\":\"translate\",\"authentication\":{\"apiKey\":\"k\"}}");
    var e = expectError(ScaleDownErrors.INVALID_INPUT, () -> function.execute(context));
    assertTrue(e.getMessage().contains("translate"), e.getMessage());
  }
}
