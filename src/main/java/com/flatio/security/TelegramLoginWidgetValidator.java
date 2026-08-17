package com.flatio.security;

import com.flatio.common.exception.InvalidTelegramAuthException;
import com.flatio.telegram.config.BotConfig;
import com.flatio.web.dto.TelegramLoginWidgetRequest;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Validates the Telegram Login Widget authentication payload per Telegram's official
 * verification algorithm.
 *
 * <p>See <a href="https://core.telegram.org/widgets/login#checking-authorization">Checking
 * authorization</a>. This is deliberately a separate class from {@link TelegramInitDataValidator}
 * even though both verify Telegram-signed data with the same bot token: the Login Widget derives
 * its HMAC secret as {@code SHA-256(bot_token)}, whereas WebApp {@code initData} derives it as
 * {@code HMAC-SHA256("WebAppData", bot_token)} — reusing one validator for both would silently
 * verify against the wrong secret for whichever payload type it was not designed for.
 */
@Component
@RequiredArgsConstructor
public class TelegramLoginWidgetValidator {

  private static final String HMAC_SHA256 = "HmacSHA256";
  private static final String SHA_256 = "SHA-256";

  /** Telegram recommends rejecting widget auth data older than this to limit replay attacks. */
  private static final long MAX_AUTH_AGE_SECONDS = 86_400L;

  private final BotConfig botConfig;

  /**
   * Validates the signature and freshness of a Telegram Login Widget payload.
   *
   * @param request the widget callback payload, never null
   * @throws InvalidTelegramAuthException if the signature is invalid or {@code auth_date}
   *     is too old
   */
  public void validate(TelegramLoginWidgetRequest request) {
    String expectedHash = computeHash(buildDataCheckString(request));
    if (!MessageDigest.isEqual(
        request.hash().getBytes(StandardCharsets.UTF_8), expectedHash.getBytes(StandardCharsets.UTF_8))) {
      throw new InvalidTelegramAuthException("Telegram Login Widget signature is invalid");
    }
    if (Instant.now().getEpochSecond() - request.authDate() > MAX_AUTH_AGE_SECONDS) {
      throw new InvalidTelegramAuthException("Telegram Login Widget auth_date has expired");
    }
  }

  private String buildDataCheckString(TelegramLoginWidgetRequest request) {
    var fields = new TreeMap<String, String>();
    fields.put("id", String.valueOf(request.id()));
    fields.put("first_name", request.firstName());
    putIfPresent(fields, "last_name", request.lastName());
    putIfPresent(fields, "username", request.username());
    putIfPresent(fields, "photo_url", request.photoUrl());
    fields.put("auth_date", String.valueOf(request.authDate()));
    return fields.entrySet().stream()
        .map(entry -> entry.getKey() + "=" + entry.getValue())
        .collect(Collectors.joining("\n"));
  }

  private void putIfPresent(TreeMap<String, String> fields, String key, String value) {
    if (value != null) {
      fields.put(key, value);
    }
  }

  private String computeHash(String dataCheckString) {
    byte[] secretKey = sha256(botConfig.token().getBytes(StandardCharsets.UTF_8));
    byte[] hashBytes = hmacSha256(secretKey, dataCheckString.getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(hashBytes);
  }

  private byte[] sha256(byte[] input) {
    try {
      return MessageDigest.getInstance(SHA_256).digest(input);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm is not available", e);
    }
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
}
