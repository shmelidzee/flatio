package com.flatio.domain.city;

import com.flatio.domain.country.Country;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Represents a city in the cities reference table.
 *
 * <p>Stores the canonical Russian name together with optional Belarusian and English names,
 * geographic coordinates and a reference to the country the city belongs to.
 * Used for normalising city names coming from various external listing sources.
 */
@Entity
@Table(name = "cities")
@Getter
@Setter
public class City {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Canonical Russian city name (e.g. «Минск»). */
  @Column(name = "name_ru", nullable = false, length = 255)
  private String nameRu;

  /** Belarusian city name (e.g. «Мінск»). */
  @Column(name = "name_be", length = 255)
  private String nameBe;

  /** English city name (e.g. "Minsk"). */
  @Column(name = "name_en", length = 255)
  private String nameEn;

  /** Country this city belongs to. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "country_id", nullable = false)
  private Country country;

  /** Latitude in decimal degrees (WGS-84). */
  @Column(precision = 9, scale = 6)
  private BigDecimal latitude;

  /** Longitude in decimal degrees (WGS-84). */
  @Column(precision = 9, scale = 6)
  private BigDecimal longitude;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
