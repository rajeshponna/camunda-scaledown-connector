package io.github.rajeshponna.scaledown.document;

import io.camunda.connector.api.document.Document;
import io.camunda.connector.api.document.DocumentMetadata;
import io.github.rajeshponna.scaledown.client.ScaleDownErrors;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Turns a Camunda document into the base64 + MIME type pair ScaleDown expects. */
public final class DocumentEncoder {

  public static final Set<String> SUPPORTED =
      Set.of("application/pdf", "image/jpeg", "image/png", "image/tiff");

  private static final Map<String, String> ALIASES =
      Map.of("image/jpg", "image/jpeg", "image/pjpeg", "image/jpeg", "image/tif", "image/tiff");

  private static final Map<String, String> BY_EXTENSION =
      Map.of(
          "pdf", "application/pdf",
          "jpg", "image/jpeg",
          "jpeg", "image/jpeg",
          "png", "image/png",
          "tif", "image/tiff",
          "tiff", "image/tiff");

  /** Default ceiling for a single document. Base64 adds about a third on the wire. */
  public static final long DEFAULT_MAX_BYTES = 20L * 1024 * 1024;

  private final long maxBytes;

  public DocumentEncoder() {
    this(DEFAULT_MAX_BYTES);
  }

  public DocumentEncoder(long maxBytes) {
    this.maxBytes = maxBytes;
  }

  public record EncodedDocument(String base64, String mimeType, String fileName) {}

  public EncodedDocument encode(Document document, String mimeTypeOverride) {
    if (document == null) {
      throw ScaleDownErrors.fatal(
          ScaleDownErrors.INVALID_INPUT,
          "Input source is 'document' but no document was provided. Map a document reference, e.g. =documents[1].");
    }
    DocumentMetadata metadata = safeMetadata(document);
    String fileName = metadata != null ? metadata.getFileName() : null;
    String mime = resolveMimeType(mimeTypeOverride, metadata, fileName);

    Long size = metadata != null ? metadata.getSize() : null;
    if (size != null && size > maxBytes) {
      throw ScaleDownErrors.fatal(
          ScaleDownErrors.UNSUPPORTED_DOCUMENT,
          "Document '" + nameOf(fileName) + "' is " + size + " bytes, above the "
              + maxBytes + " byte limit of this connector.");
    }

    String base64;
    try {
      base64 = document.asBase64();
    } catch (RuntimeException e) {
      throw ScaleDownErrors.fatal(
          ScaleDownErrors.INVALID_INPUT,
          "Could not read document '" + nameOf(fileName) + "': " + e.getMessage(),
          e,
          Map.of());
    }
    if (base64 == null || base64.isEmpty()) {
      throw ScaleDownErrors.fatal(
          ScaleDownErrors.INVALID_INPUT, "Document '" + nameOf(fileName) + "' is empty.");
    }
    // Size was unknown up front: check the decoded length now.
    long decoded = (long) base64.length() * 3 / 4;
    if (size == null && decoded > maxBytes) {
      throw ScaleDownErrors.fatal(
          ScaleDownErrors.UNSUPPORTED_DOCUMENT,
          "Document '" + nameOf(fileName) + "' is about " + decoded
              + " bytes, above the " + maxBytes + " byte limit of this connector.");
    }
    return new EncodedDocument(base64, mime, fileName);
  }

  static String resolveMimeType(String override, DocumentMetadata metadata, String fileName) {
    String candidate = blankToNull(override);
    if (candidate == null && metadata != null) {
      candidate = blankToNull(metadata.getContentType());
    }
    if (candidate != null) {
      // "application/pdf; charset=binary" -> "application/pdf"
      candidate = candidate.split(";")[0].trim().toLowerCase(Locale.ROOT);
      candidate = ALIASES.getOrDefault(candidate, candidate);
    }
    if ((candidate == null || candidate.equals("application/octet-stream")) && fileName != null) {
      int dot = fileName.lastIndexOf('.');
      if (dot >= 0) {
        candidate = BY_EXTENSION.get(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
      }
    }
    if (candidate == null) {
      throw ScaleDownErrors.fatal(
          ScaleDownErrors.UNSUPPORTED_DOCUMENT,
          "Cannot tell the type of document '" + nameOf(fileName)
              + "'. Set 'MIME type override' to application/pdf, image/jpeg, image/png or image/tiff.");
    }
    if (!SUPPORTED.contains(candidate)) {
      throw ScaleDownErrors.fatal(
          ScaleDownErrors.UNSUPPORTED_DOCUMENT,
          "Document '" + nameOf(fileName) + "' has type " + candidate
              + ". ScaleDown accepts application/pdf, image/jpeg, image/png and image/tiff.");
    }
    return candidate;
  }

  private static DocumentMetadata safeMetadata(Document document) {
    try {
      return document.metadata();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String nameOf(String fileName) {
    return fileName != null ? fileName : "unnamed";
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
