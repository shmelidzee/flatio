package com.flatio.service;

import com.flatio.domain.listing.DealType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

@Service
public class DedupHashServiceImpl implements DedupHashService {

  private static final String FIELD_SEPARATOR = "|";

  @Override
  public String computeDedupHash(String address, Integer rooms, BigDecimal areaTotalM2, DealType dealType) {
    String raw = normalizeString(address)
        + FIELD_SEPARATOR + normalizeInteger(rooms)
        + FIELD_SEPARATOR + normalizeDecimal(areaTotalM2)
        + FIELD_SEPARATOR + normalizeEnum(dealType);
    return sha256(raw);
  }

  private String normalizeString(String value) {
    if (value == null) {
      return "";
    }
    return value.toLowerCase().trim().replaceAll("\\s+", " ");
  }

  private String normalizeInteger(Integer value) {
    return value == null ? "" : value.toString();
  }

  private String normalizeDecimal(BigDecimal value) {
    if (value == null) {
      return "";
    }
    return value.stripTrailingZeros().toPlainString();
  }

  private String normalizeEnum(Enum<?> value) {
    return value == null ? "" : value.name().toLowerCase();
  }

  private String sha256(String input) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hashBytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm not available on this JVM", e);
    }
  }
}
