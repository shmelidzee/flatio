package com.flatio.integration.kufar.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Cursor link for Kufar pagination. {@code label} is {@code "next"} or {@code "prev"},
 * {@code token} is the opaque cursor value to pass as the {@code cursor} query parameter.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KufarPageLink(String token, String label) {}
