package io.github.rajeshponna.scaledown.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum Operation {
  CLASSIFY,
  DECISIONS,
  EXTRACT,
  SUMMARIZE,
  COMPRESS;

  @JsonCreator
  public static Operation fromValue(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Operation.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Unknown operation '" + value + "'. Use classify, decisions, extract, summarize or compress.");
    }
  }

  @JsonValue
  public String value() {
    return name().toLowerCase(Locale.ROOT);
  }
}
