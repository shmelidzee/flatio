package com.flatio.domain.listing;

import com.flatio.domain.country.Country;
import com.flatio.domain.currency.Currency;
import com.flatio.domain.source.Source;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(
    name = "listings",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_listing_external_source",
        columnNames = {"external_id", "source_id"}
    )
)
@Getter
@Setter
public class Listing {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "external_id", nullable = false, length = 255)
  private String externalId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "source_id", nullable = false)
  private Source source;

  @Column(nullable = false, length = 500)
  private String title;

  @Column(columnDefinition = "TEXT")
  private String description;

  @Enumerated(EnumType.STRING)
  @Column(name = "deal_type", nullable = false, length = 20)
  private DealType dealType;

  @Enumerated(EnumType.STRING)
  @Column(name = "price_unit", length = 10)
  private PriceUnit priceUnit;

  @Column(name = "property_type", length = 50)
  private String propertyType;

  @Column(nullable = false, precision = 15, scale = 2)
  private BigDecimal price;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "currency_id", nullable = false)
  private Currency currency;

  @Column(name = "price_usd", precision = 15, scale = 2)
  private BigDecimal priceUsd;

  @Column(name = "price_byn", precision = 15, scale = 2)
  private BigDecimal priceByn;

  @Column
  private Integer rooms;

  @Column(name = "floor_number")
  private Integer floorNumber;

  @Column(name = "floors_total")
  private Integer floorsTotal;

  @Column(name = "area_total_m2", precision = 8, scale = 2)
  private BigDecimal areaTotalM2;

  @Column(name = "area_living_m2", precision = 8, scale = 2)
  private BigDecimal areaLivingM2;

  @Column(name = "area_kitchen_m2", precision = 8, scale = 2)
  private BigDecimal areaKitchenM2;

  @Column(length = 500)
  private String address;

  @Column(precision = 10, scale = 7)
  private BigDecimal latitude;

  @Column(precision = 10, scale = 7)
  private BigDecimal longitude;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "country_id", nullable = false)
  private Country country;

  @Column(length = 100)
  private String city;

  @Column(length = 100)
  private String district;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private ListingStatus status;

  @Column(name = "source_url", nullable = false, length = 1000)
  private String sourceUrl;

  @Column(name = "photo_url", length = 1000)
  private String photoUrl;

  @Column(name = "dedup_hash", length = 64)
  private String dedupHash;

  @Column(name = "is_owner")
  private Boolean isOwner;

  @Column(name = "reposted_from")
  private Long repostedFrom;

  @Column(name = "last_reposted_at")
  private Instant lastRepostedAt;

  @Column(name = "missed_syncs_count", nullable = false)
  private int missedSyncsCount;

  @Column(name = "published_at")
  private Instant publishedAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
