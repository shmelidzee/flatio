package com.flatio.integration.kufar.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Key-value parameter from Kufar ad ({@code pl} = parameter label, {@code vl} = value).
 *
 * <p>Known parameter labels: {@code roomsCount}, {@code floor}, {@code totalFloors},
 * {@code area}, {@code geopoint}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KufarAdParameter(String pl, String vl) {}
