package com.flatio.domain.blacklist;

import com.flatio.domain.user.User;
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
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * An item a user has excluded from their search results and notifications: a single listing, an
 * entire source, or a stop-word.
 *
 * <p>{@code value} is a polymorphic column whose meaning depends on {@code type}: the numeric ID
 * of a {@link com.flatio.domain.listing.Listing} for {@code LISTING}, the numeric ID of a
 * {@link com.flatio.domain.source.Source} for {@code SOURCE}, or a free-text stop-word for
 * {@code KEYWORD}. It is stored as a string (rather than a foreign key) because a single column
 * cannot reference two different target tables depending on the row's type, and a stop-word has
 * no target entity at all. Uniqueness of {@code (user, type, value)} is enforced by a database
 * constraint, so a blacklist entry is never duplicated.
 */
@Entity
@Table(
    name = "blacklist_entries",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_blacklist_entries_user_type_value", columnNames = {"user_id", "type", "value"})
)
@Getter
@Setter
public class BlacklistEntry {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private BlacklistEntryType type;

  @Column(nullable = false, length = 100)
  private String value;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;
}
