package com.flatio.integration.kufar.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Image entry for a Kufar listing. {@code path} contains the full photo URL.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KufarImage(String id, String path) {}
