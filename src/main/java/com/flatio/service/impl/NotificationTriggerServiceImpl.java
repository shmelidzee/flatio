package com.flatio.service.impl;

import com.flatio.domain.city.City;
import com.flatio.domain.listing.Listing;
import com.flatio.domain.listing.ListingStatus;
import com.flatio.domain.notification.Notification;
import com.flatio.domain.notification.NotificationStatus;
import com.flatio.domain.subscription.Subscription;
import com.flatio.domain.subscription.TriggerType;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
  private final SearchCriteriaJsonMapper searchCriteriaJsonMapper;

  @Override
  @Async("notificationTriggerExecutor")
  @Transactional
  public void evaluate(List<ListingChange> changes) {
    if (changes.isEmpty()) {
      return;
    }
    List<Subscription> subscriptions = subscriptionRepository.findByActiveTrue();
    Map<Subscription, SubscriptionSearchCriteria> criteriaBySubscription = resolveCriteria(subscriptions);
    Map<Long, City> citiesById = preloadCities(criteriaBySubscription.values());
    for (ListingChange change : changes) {
      evaluateChange(change, subscriptions, criteriaBySubscription, citiesById);
    }
  }

  private Map<Subscription, SubscriptionSearchCriteria> resolveCriteria(List<Subscription> subscriptions) {
    Map<Subscription, SubscriptionSearchCriteria> result = new HashMap<>();
    for (Subscription subscription : subscriptions) {
      result.put(subscription, searchCriteriaJsonMapper.toCriteria(subscription.getSearchCriteria()));
    }
    return result;
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

  private void evaluateChange(ListingChange change, List<Subscription> subscriptions,
      Map<Subscription, SubscriptionSearchCriteria> criteriaBySubscription, Map<Long, City> citiesById) {
    try {
      for (Subscription subscription : subscriptions) {
        SubscriptionSearchCriteria criteria = criteriaBySubscription.get(subscription);
        if (matchesCriteria(criteria, change.listing(), citiesById)) {
          evaluateSubscriptionTriggers(subscription, change);
        }
      }
    } catch (RuntimeException ex) {
      log.error("Failed to evaluate notification triggers: listingId={}", change.listing().getId(), ex);
    }
  }

  private void evaluateSubscriptionTriggers(Subscription subscription, ListingChange change) {
    Listing listing = change.listing();
    if (listing.getStatus() == ListingStatus.REPOSTED) {
      createNotificationIfEnabled(subscription, listing, TriggerType.REPOSTED);
      return;
    }
    if (change.changeType() == ListingChangeType.NEW) {
      createNotificationIfEnabled(subscription, listing, TriggerType.NEW_LISTING);
      return;
    }
    evaluateUpdateTriggers(subscription, change);
  }

  private void evaluateUpdateTriggers(Subscription subscription, ListingChange change) {
    if (isReactivation(change)) {
      createNotificationIfEnabled(subscription, change.listing(), TriggerType.REACTIVATED);
    }
    if (isSignificantPriceDrop(change, subscription.getPriceDropThreshold())) {
      createNotificationIfEnabled(subscription, change.listing(), TriggerType.PRICE_DROP);
    }
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
   * Creates a PENDING notification for the given subscription/listing/trigger combination unless
   * the subscription does not have this trigger enabled, or a notification for this exact
   * combination already exists (FR-SUB-8 deduplication).
   *
   * @param subscription the subscription to notify
   * @param listing      the listing the notification is about
   * @param triggerType  the event that raised the notification
   */
  private void createNotificationIfEnabled(Subscription subscription, Listing listing, TriggerType triggerType) {
    if (!subscription.getTriggers().contains(triggerType)) {
      return;
    }
    boolean alreadyNotified = notificationRepository
        .findBySubscriptionAndListingAndTriggerType(subscription, listing, triggerType)
        .isPresent();
    if (alreadyNotified) {
      return;
    }
    Notification notification = new Notification();
    notification.setSubscription(subscription);
    notification.setListing(listing);
    notification.setTriggerType(triggerType);
    notification.setStatus(NotificationStatus.PENDING);
    notificationRepository.save(notification);
    log.info("Notification created: subscriptionId={}, listingId={}, triggerType={}",
        subscription.getId(), listing.getId(), triggerType);
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
}
