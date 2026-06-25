package com.flatio.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flatio.common.exception.InvalidTelegramAuthException;
import com.flatio.telegram.config.BotConfig;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Validates Telegram WebApp {@code initData} per Telegram's official verification algorithm.
 *
 * <p>See <a href="https://core.telegram.org/bots/webapps#validating-data-received-via-the-mini-app">
 * Validating data received via the Mini App</a>. The bot token (already configured for outbound
 * API calls via {@link BotConfig}) doubles as the HMAC secret here — Telegram signs
 * {@code initData} with a key derived from it, so only Telegram (or someone who knows the bot
 * token) can produce a valid signature.
 */
@Component
@RequiredArgsConstructor
public class TelegramInitDataValidator {

  private static final String HMAC_SHA256 = "HmacSHA256";
  private static final String WEB_APP_DATA_KEY = "WebAppData";
  private static final String HASH_FIELD = "hash";
  private static final String AUTH_DATE_FIELD = "auth_date";
  private static final String USER_FIELD = "user";

  /** Telegram recommends rejecting initData older than this to limit replay attacks. */
  private static final long MAX_AUTH_AGE_SECONDS = 86_400L;

  private final BotConfig botConfig;
  private final ObjectMapper objectMapper;

  /**
   * Validates the given {@code initData} string and extracts the embedded Telegram user.
   *
   * @param initData raw {@code initData} string from the Telegram WebApp, never null
   * @return the verified Telegram user embedded in {@code initData}, never null
   * @throws InvalidTelegramAuthException if the signature is missing, invalid, or expired
   */
  public TelegramWebAppUser validate(String initData) {
    Map<String, String> fields = parseFields(initData);

    String hash = fields.remove(HASH_FIELD);
    if (hash == null || hash.isBlank()) {
      throw new InvalidTelegramAuthException("initData is missing the hash field");
    }

    String expectedHash = computeHash(buildDataCheckString(fields));
    if (!MessageDigest.isEqual(
        hash.getBytes(StandardCharsets.UTF_8), expectedHash.getBytes(StandardCharsets.UTF_8))) {
      throw new InvalidTelegramAuthException("initData signature is invalid");
    }

    validateAuthDate(fields.get(AUTH_DATE_FIELD));
    return parseUser(fields.get(USER_FIELD));
  }

  private Map<String, String> parseFields(String initData) {
    Map<String, String> fields = new LinkedHashMap<>();
    for (String pair : initData.split("&")) {
      int separatorIndex = pair.indexOf('=');
      if (separatorIndex < 0) {
        continue;
      }
      String key = pair.substring(0, separatorIndex);
      String value = URLDecoder.decode(pair.substring(separatorIndex + 1), StandardCharsets.UTF_8);
      fields.put(key, value);
    }
    return fields;
  }

  private String buildDataCheckString(Map<String, String> fields) {
    return String.join("\n",
        new TreeMap<>(fields).entrySet().stream()
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .toArray(String[]::new));
  }

  private String computeHash(String dataCheckString) {
    byte[] secretKey = hmacSha256(
        WEB_APP_DATA_KEY.getBytes(StandardCharsets.UTF_8), botConfig.token().getBytes(StandardCharsets.UTF_8));
    byte[] hashBytes = hmacSha256(secretKey, dataCheckString.getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(hashBytes);
  }

  private byte[] hmacSha256(byte[] key, byte[] message) {
    try {
      Mac mac = Mac.getInstance(HMAC_SHA256);
      mac.init(new SecretKeySpec(key, HMAC_SHA256));
      return mac.doFinal(message);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HmacSHA256 algorithm is not available", e);
    }
  }

  private void validateAuthDate(String authDateValue) {
    if (authDateValue == null) {
      throw new InvalidTelegramAuthException("initData is missing the auth_date field");
    }
    long authDate;
    try {
      authDate = Long.parseLong(authDateValue);
    } catch (NumberFormatException e) {
      throw new InvalidTelegramAuthException("initData has an unparseable auth_date field");
    }
    if (Instant.now().getEpochSecond() - authDate > MAX_AUTH_AGE_SECONDS) {
      throw new InvalidTelegramAuthException("initData has expired");
    }
  }

  private TelegramWebAppUser parseUser(String userJson) {
    if (userJson == null || userJson.isBlank()) {
      throw new InvalidTelegramAuthException("initData is missing the user field");
    }
    try {
      return objectMapper.readValue(userJson, TelegramWebAppUser.class);
    } catch (Exception e) {
      throw new InvalidTelegramAuthException("initData has an unparseable user field");
    }
  }
}
