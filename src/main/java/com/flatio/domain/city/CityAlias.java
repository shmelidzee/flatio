package com.flatio.domain.city;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Represents a source-specific alias for a {@link City}.
 *
 * <p>External listing sources may refer to the same city using different spellings
 * (e.g. «Минск» vs «Мінск»). Each row maps one alias string used by a particular
 * source to the canonical {@link City} record.
 */
@Entity
@Table(name = "city_aliases")
@Getter
@Setter
public class CityAlias {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** City this alias belongs to. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "city_id", nullable = false)
  private City city;

  /**
   * The alias string as it appears in the external source
   * (e.g. «Мінск», «Минск»).
   */
  @Column(nullable = false, length = 255)
  private String alias;

  /**
   * Identifier of the external source that uses this alias
   * (e.g. "realt", "onliner", "kufar").
   */
  @Column(name = "source_id", nullable = false, length = 100)
  private String sourceId;
}
