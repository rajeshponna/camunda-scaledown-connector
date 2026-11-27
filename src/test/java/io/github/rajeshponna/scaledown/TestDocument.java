package io.github.rajeshponna.scaledown;

import io.camunda.connector.api.document.Document;
import io.camunda.connector.api.document.DocumentLinkParameters;
import io.camunda.connector.api.document.DocumentMetadata;
import io.camunda.connector.api.document.DocumentReference;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;

/** Minimal in-memory Camunda document for tests. */
record TestDocument(byte[] content, String contentType, String fileName) implements Document {

  @Override
  public DocumentMetadata metadata() {
    return new DocumentMetadata() {
      @Override public String getContentType() { return contentType; }
      @Override public OffsetDateTime getExpiresAt() { return null; }
      @Override public Long getSize() { return (long) content.length; }
      @Override public String getFileName() { return fileName; }
      @Override public String getProcessDefinitionId() { return null; }
      @Override public Long getProcessInstanceKey() { return null; }
      @Override public Map<String, Object> getCustomProperties() { return Map.of(); }
    };
  }

  @Override public String asBase64() { return Base64.getEncoder().encodeToString(content); }
  @Override public InputStream asInputStream() { return new ByteArrayInputStream(content); }
  @Override public byte[] asByteArray() { return content; }
  @Override public DocumentReference reference() { return null; }
  @Override public String generateLink(DocumentLinkParameters parameters) { return null; }
}
