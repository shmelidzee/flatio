package com.flatio.service.impl;

import com.flatio.domain.blacklist.BlacklistEntry;
import com.flatio.domain.city.City;
import com.flatio.domain.listing.Listing;
import com.flatio.domain.listing.ListingStatus;
import com.flatio.domain.notification.Notification;
import com.flatio.domain.subscription.Subscription;
import com.flatio.domain.subscription.TriggerType;
import com.flatio.domain.user.User;
import com.flatio.repository.BlacklistEntryRepository;
import com.flatio.repository.CityRepository;
import com.flatio.repository.NotificationRepository;
import com.flatio.repository.SubscriptionRepository;
import com.flatio.service.NotificationTriggerService;
import com.flatio.service.domain.ListingChange;
import com.flatio.service.domain.ListingChangeType;
import com.flatio.web.dto.SubscriptionSearchCriteria;
import com.flatio.web.mapper.SearchCriteriaJsonMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link NotificationTriggerService} that matches listing changes against
 * every active subscription's search filter and trigger configuration.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationTriggerServiceImpl implements NotificationTriggerService {

  private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
  private static final int PRICE_DROP_PERCENT_SCALE = 2;

  private final SubscriptionRepository subscriptionRepository;
  private final NotificationRepository notificationRepository;
  private final CityRepository cityRepository;
  private final BlacklistEntryRepository blacklistEntryRepository;
  private final SearchCriteriaJsonMapper searchCriteriaJsonMapper;
  private final NotificationCreator notificationCreator;

  @Override
  @Async("notificationTriggerExecutor")
  @Transactional
  public void evaluate(List<ListingChange> changes) {
    if (changes.isEmpty()) {
      return;
    }
    List<Subscription> subscriptions = subscriptionRepository.findByActiveTrue();
    Map<Subscription, SubscriptionSearchCriteria> criteriaBySubscription = resolveCriteria(subscriptions);
    // Only subscriptions whose criteria resolved (successfully or to a legitimate null) are
    // evaluated — one with malformed search_criteria JSON is excluded here by resolveCriteria,
    // so it is skipped entirely rather than falling through matchesCriteria's null => "matches
    // everything" behaviour, which is reserved for subscriptions with no filter at all.
    List<Subscription> evaluableSubscriptions = List.copyOf(criteriaBySubscription.keySet());
    Map<Long, City> citiesById = preloadCities(criteriaBySubscription.values());
    Map<Long, BlacklistProfile> blacklistByUserId = preloadBlacklists(evaluableSubscriptions);
    EvaluationContext context =
        new EvaluationContext(evaluableSubscriptions, criteriaBySubscription, citiesById, blacklistByUserId);
    List<NotificationCandidate> candidates = collectCandidates(changes, context);
    Set<NotificationKey> existingKeys = loadExistingKeys(candidates);
    for (NotificationCandidate candidate : candidates) {
      if (!existingKeys.contains(candidate.toKey())) {
        createNotification(candidate.subscription(), candidate.listing(), candidate.triggerType());
      }
    }
  }

  private Map<Subscription, SubscriptionSearchCriteria> resolveCriteria(List<Subscription> subscriptions) {
    Map<Subscription, SubscriptionSearchCriteria> result = new HashMap<>();
    for (Subscription subscription : subscriptions) {
      resolveCriteriaForSubscription(subscription, result);
    }
    return result;
  }

  /**
   * Resolves one subscription's search criteria, isolating a malformed {@code search_criteria}
   * JSON on a single subscription from the rest of the {@link #evaluate} batch.
   *
   * <p>On failure the subscription is simply omitted from {@code result} so it does not
   * participate in this evaluation run at all, instead of letting the exception propagate out of
   * {@link #evaluate} and roll back notifications already computed for other subscriptions.
   *
   * @param subscription the subscription whose criteria to resolve
   * @param result       accumulator map to add the resolved criteria into, unless resolution fails
   */
  private void resolveCriteriaForSubscription(
      Subscription subscription, Map<Subscription, SubscriptionSearchCriteria> result) {
    try {
      result.put(subscription, searchCriteriaJsonMapper.toCriteria(subscription.getSearchCriteria()));
    } catch (RuntimeException ex) {
      log.error("Failed to resolve search criteria for subscription id={}", subscription.getId(), ex);
    }
  }

  private Map<Long, City> preloadCities(Collection<SubscriptionSearchCriteria> criteriaList) {
    List<Long> cityIds = criteriaList.stream()
        .filter(Objects::nonNull)
        .map(SubscriptionSearchCriteria::cityId)
        .filter(Objects::nonNull)
        .distinct()
        .toList();
    if (cityIds.isEmpty()) {
      return Map.of();
    }
    return cityRepository.findAllById(cityIds).stream()
        .collect(Collectors.toMap(City::getId, Function.identity()));
  }

  private List<NotificationCandidate> collectCandidates(List<ListingChange> changes, EvaluationContext context) {
    List<NotificationCandidate> candidates = new ArrayList<>();
    for (ListingChange change : changes) {
      collectCandidatesForChange(change, context, candidates);
    }
    return candidates;
  }

  private void collectCandidatesForChange(
      ListingChange change, EvaluationContext context, List<NotificationCandidate> candidates) {
    try {
      for (Subscription subscription : context.subscriptions()) {
        SubscriptionSearchCriteria criteria = context.criteriaBySubscription().get(subscription);
        BlacklistProfile blacklist = context.blacklistByUserId().get(subscription.getUser().getId());
        if (matchesCriteria(criteria, change.listing(), context.citiesById())
            && !isBlacklisted(change.listing(), blacklist)) {
          collectSubscriptionTriggers(subscription, change, candidates);
        }
      }
    } catch (RuntimeException ex) {
      log.error("Failed to evaluate notification triggers: listingId={}", change.listing().getId(), ex);
    }
  }

  /**
   * Batch-preloads every evaluable subscription owner's blacklist in one query (issue #414),
   * instead of one query per distinct user.
   *
   * @param subscriptions the subscriptions being evaluated this run
   * @return each user's blacklist profile, keyed by user ID; a user with no blacklist entries at
   *     all is simply absent from the map
   */
  private Map<Long, BlacklistProfile> preloadBlacklists(List<Subscription> subscriptions) {
    Set<User> users = subscriptions.stream().map(Subscription::getUser).collect(Collectors.toSet());
    if (users.isEmpty()) {
      return Map.of();
    }
    return blacklistEntryRepository.findByUserIn(users).stream()
        .collect(Collectors.groupingBy(
            entry -> entry.getUser().getId(),
            Collectors.collectingAndThen(Collectors.toList(), NotificationTriggerServiceImpl::buildBlacklistProfile)));
  }

  private static BlacklistProfile buildBlacklistProfile(List<BlacklistEntry> entries) {
    Set<Long> listingIds = new HashSet<>();
    Set<String> sourceCodes = new HashSet<>();
    List<String> keywords = new ArrayList<>();
    for (BlacklistEntry entry : entries) {
      switch (entry.getType()) {
        case LISTING -> listingIds.add(Long.valueOf(entry.getValue()));
        case SOURCE -> sourceCodes.add(entry.getValue());
        case KEYWORD -> keywords.add(entry.getValue());
      }
    }
    return new BlacklistProfile(listingIds, sourceCodes, keywords);
  }

  /**
   * Checks whether the given listing matches one of the subscriber's blacklist entries: an
   * excluded listing (by ID), an excluded source (by code), or a stop-word appearing in the
   * title, description, or address (issue #414).
   *
   * @param listing   the listing a change was raised for
   * @param blacklist the subscriber's blacklist profile, or null if they have no entries at all
   * @return true if the listing should be excluded from notifications for this subscriber
   */
  private boolean isBlacklisted(Listing listing, BlacklistProfile blacklist) {
    if (blacklist == null) {
      return false;
    }
    if (blacklist.listingIds().contains(listing.getId())) {
      return true;
    }
    if (blacklist.sourceCodes().contains(listing.getSource().getCode())) {
      return true;
    }
    return blacklist.keywords().stream().anyMatch(keyword -> matchesKeyword(listing, keyword));
  }

  private boolean matchesKeyword(Listing listing, String keyword) {
    return containsIgnoreCase(listing.getTitle(), keyword)
        || containsIgnoreCase(listing.getDescription(), keyword)
        || containsIgnoreCase(listing.getAddress(), keyword);
  }

  private void collectSubscriptionTriggers(
      Subscription subscription, ListingChange change, List<NotificationCandidate> candidates) {
    Listing listing = change.listing();
    if (listing.getStatus() == ListingStatus.REPOSTED) {
      addCandidateIfEnabled(subscription, listing, TriggerType.REPOSTED, candidates);
      return;
    }
    if (change.changeType() == ListingChangeType.NEW) {
      addCandidateIfEnabled(subscription, listing, TriggerType.NEW_LISTING, candidates);
      return;
    }
    collectUpdateTriggers(subscription, change, candidates);
  }

  private void collectUpdateTriggers(
      Subscription subscription, ListingChange change, List<NotificationCandidate> candidates) {
    if (isReactivation(change)) {
      addCandidateIfEnabled(subscription, change.listing(), TriggerType.REACTIVATED, candidates);
    }
    if (isSignificantPriceDrop(change, subscription.getPriceDropThreshold())) {
      addCandidateIfEnabled(subscription, change.listing(), TriggerType.PRICE_DROP, candidates);
    }
  }

  private void addCandidateIfEnabled(
      Subscription subscription, Listing listing, TriggerType triggerType, List<NotificationCandidate> candidates) {
    if (subscription.getTriggers().contains(triggerType)) {
      candidates.add(new NotificationCandidate(subscription, listing, triggerType));
    }
  }

  /**
   * Batch-loads which of the given candidate (subscription, listing, triggerType) triples already
   * have a notification, replacing a per-candidate {@code findBy...} lookup with a single query.
   *
   * @param candidates the candidate notifications gathered for this {@link #evaluate} run
   * @return keys of notifications that already exist for at least one of the candidates, never null
   */
  private Set<NotificationKey> loadExistingKeys(List<NotificationCandidate> candidates) {
    if (candidates.isEmpty()) {
      return Set.of();
    }
    Set<Subscription> subscriptions = candidates.stream()
        .map(NotificationCandidate::subscription)
        .collect(Collectors.toSet());
    Set<Listing> listings = candidates.stream()
        .map(NotificationCandidate::listing)
        .collect(Collectors.toSet());
    Set<TriggerType> triggerTypes = candidates.stream()
        .map(NotificationCandidate::triggerType)
        .collect(Collectors.toSet());
    return notificationRepository
        .findBySubscriptionInAndListingInAndTriggerTypeIn(subscriptions, listings, triggerTypes)
        .stream()
        .map(NotificationTriggerServiceImpl::toKey)
        .collect(Collectors.toSet());
  }

  private static NotificationKey toKey(Notification notification) {
    return new NotificationKey(
        notification.getSubscription().getId(), notification.getListing().getId(), notification.getTriggerType());
  }

  private boolean isReactivation(ListingChange change) {
    return change.changeType() == ListingChangeType.UPDATED
        && change.previousStatus() == ListingStatus.INACTIVE
        && change.listing().getStatus() == ListingStatus.ACTIVE;
  }

  private boolean isSignificantPriceDrop(ListingChange change, BigDecimal thresholdPercent) {
    BigDecimal oldPrice = change.oldPrice();
    BigDecimal newPrice = change.newPrice();
    if (oldPrice == null || newPrice == null || thresholdPercent == null
        || oldPrice.signum() <= 0 || newPrice.compareTo(oldPrice) >= 0) {
      return false;
    }
    BigDecimal dropPercent = oldPrice.subtract(newPrice)
        .multiply(ONE_HUNDRED)
        .divide(oldPrice, PRICE_DROP_PERCENT_SCALE, RoundingMode.HALF_UP);
    return dropPercent.compareTo(thresholdPercent) >= 0;
  }

  /**
   * Creates a PENDING notification for the given subscription/listing/trigger combination,
   * tolerating a concurrent {@link #evaluate} run racing to create the same notification.
   *
   * <p>The caller has already checked the trigger is enabled and no matching notification exists
   * per {@link #loadExistingKeys}, but that check and the insert are not atomic. {@link
   * NotificationCreator#create} runs the insert in its own {@code REQUIRES_NEW} transaction, so a
   * {@link DataIntegrityViolationException} here — the database's unique constraint rejecting a
   * concurrent duplicate — only discards this one candidate instead of marking the whole batch's
   * surrounding transaction rollback-only. This is expected under FR-SUB-8 deduplication.
   *
   * @param subscription the subscription to notify
   * @param listing      the listing the notification is about
   * @param triggerType  the event that raised the notification
   */
  private void createNotification(Subscription subscription, Listing listing, TriggerType triggerType) {
    try {
      notificationCreator.create(subscription, listing, triggerType);
    } catch (DataIntegrityViolationException ex) {
      log.debug("Notification already created by a concurrent evaluate run: subscriptionId={}, listingId={}, "
          + "triggerType={}", subscription.getId(), listing.getId(), triggerType);
    }
  }

  private boolean matchesCriteria(SubscriptionSearchCriteria criteria, Listing listing, Map<Long, City> citiesById) {
    if (criteria == null) {
      return true;
    }
    return matchesDealType(criteria, listing)
        && matchesPropertyType(criteria, listing)
        && matchesSource(criteria, listing)
        && matchesCity(criteria, listing, citiesById)
        && matchesPriceRange(criteria, listing)
        && matchesRooms(criteria, listing)
        && matchesQuery(criteria, listing)
        && matchesOwnerOnly(criteria, listing);
  }

  private boolean matchesDealType(SubscriptionSearchCriteria criteria, Listing listing) {
    return criteria.dealType() == null || criteria.dealType() == listing.getDealType();
  }

  private boolean matchesPropertyType(SubscriptionSearchCriteria criteria, Listing listing) {
    return criteria.propertyType() == null || criteria.propertyType().equalsIgnoreCase(listing.getPropertyType());
  }

  private boolean matchesSource(SubscriptionSearchCriteria criteria, Listing listing) {
    return criteria.sourceId() == null || criteria.sourceId().equalsIgnoreCase(listing.getSource().getCode());
  }

  private boolean matchesCity(SubscriptionSearchCriteria criteria, Listing listing, Map<Long, City> citiesById) {
    String cityName = resolveCityFilterName(criteria, citiesById);
    return cityName == null || containsIgnoreCase(listing.getCity(), cityName);
  }

  private String resolveCityFilterName(SubscriptionSearchCriteria criteria, Map<Long, City> citiesById) {
    if (criteria.city() != null && !criteria.city().isBlank()) {
      return criteria.city();
    }
    if (criteria.cityId() != null) {
      City city = citiesById.get(criteria.cityId());
      return city != null ? city.getNameRu() : null;
    }
    return null;
  }

  private boolean matchesPriceRange(SubscriptionSearchCriteria criteria, Listing listing) {
    BigDecimal effectivePrice = listing.getPriceByn() != null ? listing.getPriceByn() : listing.getPrice();
    if (criteria.priceMin() != null && (effectivePrice == null || effectivePrice.compareTo(criteria.priceMin()) < 0)) {
      return false;
    }
    return criteria.priceMax() == null
        || (effectivePrice != null && effectivePrice.compareTo(criteria.priceMax()) <= 0);
  }

  private boolean matchesRooms(SubscriptionSearchCriteria criteria, Listing listing) {
    return criteria.rooms() == null || criteria.rooms().equals(listing.getRooms());
  }

  private boolean matchesQuery(SubscriptionSearchCriteria criteria, Listing listing) {
    String query = criteria.query();
    if (query == null || query.isBlank()) {
      return true;
    }
    return containsIgnoreCase(listing.getTitle(), query)
        || containsIgnoreCase(listing.getDescription(), query)
        || containsIgnoreCase(listing.getAddress(), query);
  }

  private boolean matchesOwnerOnly(SubscriptionSearchCriteria criteria, Listing listing) {
    if (!Boolean.TRUE.equals(criteria.ownerOnly())) {
      return true;
    }
    return listing.getIsOwner() == null || Boolean.TRUE.equals(listing.getIsOwner());
  }

  private boolean containsIgnoreCase(String value, String search) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT));
  }

  /**
   * Per-{@link #evaluate} run state shared by candidate collection across all {@link ListingChange}
   * entries, grouped into one object to keep helper method parameter lists within the project's
   * 4-parameter limit.
   */
  private record EvaluationContext(
      List<Subscription> subscriptions,
      Map<Subscription, SubscriptionSearchCriteria> criteriaBySubscription,
      Map<Long, City> citiesById,
      Map<Long, BlacklistProfile> blacklistByUserId) {}

  /**
   * One user's blacklist, split by type for {@link #isBlacklisted} to check cheaply — listing IDs
   * and source codes are exact-match sets, keywords are matched as case-insensitive substrings.
   */
  private record BlacklistProfile(Set<Long> listingIds, Set<String> sourceCodes, List<String> keywords) {}

  /**
   * A (subscription, listing, triggerType) combination eligible for a notification, pending the
   * batch deduplication check in {@link #loadExistingKeys}.
   */
  private record NotificationCandidate(Subscription subscription, Listing listing, TriggerType triggerType) {

    private NotificationKey toKey() {
      return new NotificationKey(subscription.getId(), listing.getId(), triggerType);
    }
  }

  /**
   * Identity of a notification by its unique (subscription, listing, triggerType) triple, used to
   * check {@link NotificationCandidate} membership against already-existing notifications without
   * relying on JPA entity identity.
   */
  private record NotificationKey(Long subscriptionId, Long listingId, TriggerType triggerType) {}
}
