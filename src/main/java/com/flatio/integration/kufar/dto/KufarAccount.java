package com.flatio.integration.kufar.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Seller account info. {@code type} is {@code "private"} for owners, {@code "company"} for agencies.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KufarAccount(Long id, String type) {}
