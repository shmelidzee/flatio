package com.flatio.web.controller;

import com.flatio.service.SubscriptionService;
import com.flatio.web.dto.CreateSubscriptionRequest;
import com.flatio.web.dto.SubscriptionResponse;
import com.flatio.web.dto.UpdateSubscriptionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for managing the authenticated user's search subscriptions.
 */
@RestController
@RequestMapping("/api/v1/subscriptions")
@Tag(name = "Subscriptions", description = "Manage saved search subscriptions and notification settings")
@RequiredArgsConstructor
public class SubscriptionController {

  private final SubscriptionService subscriptionService;

  /**
   * Creates a new active subscription from the given search filter.
   *
   * @param request        the subscription details
   * @param authentication the authenticated caller
   * @return the created subscription
   */
  @Operation(
      summary = "Create a subscription",
      description = "Creates a new active subscription from a search filter. Fails with 422 "
          + "if the user's tariff active subscription limit is reached."
  )
  @ApiResponse(responseCode = "200", description = "Subscription created")
  @ApiResponse(responseCode = "400", description = "Invalid request body")
  @ApiResponse(responseCode = "401", description = "Unauthorized")
  @ApiResponse(responseCode = "422", description = "Active subscription limit exceeded")
  @PostMapping
  public SubscriptionResponse create(
      @Valid @RequestBody CreateSubscriptionRequest request,
      Authentication authentication
  ) {
    return subscriptionService.create(currentUserId(authentication), request);
  }

  /**
   * Returns a paginated list of the authenticated user's subscriptions.
   *
   * @param pageable       pagination and sorting (default: 20 per page, sorted by createdAt DESC)
   * @param authentication the authenticated caller
   * @return page of the caller's subscriptions
   */
  @Operation(
      summary = "List my subscriptions",
      description = "Returns a paginated list of subscriptions owned by the authenticated user."
  )
  @ApiResponse(responseCode = "200", description = "Subscriptions page returned")
  @ApiResponse(responseCode = "401", description = "Unauthorized")
  @GetMapping
  public Page<SubscriptionResponse> findMine(
      @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
      Authentication authentication
  ) {
    return subscriptionService.findByUser(currentUserId(authentication), pageable);
  }

  /**
   * Returns a single subscription owned by the authenticated user.
   *
   * @param id             the subscription ID
   * @param authentication the authenticated caller
   * @return the subscription
   */
  @Operation(
      summary = "Get a subscription",
      description = "Returns full details of a subscription owned by the authenticated user."
  )
  @ApiResponse(responseCode = "200", description = "Subscription found")
  @ApiResponse(responseCode = "404", description = "Subscription not found")
  @ApiResponse(responseCode = "401", description = "Unauthorized")
  @GetMapping("/{id}")
  public SubscriptionResponse findById(@PathVariable Long id, Authentication authentication) {
    return subscriptionService.findByIdForUser(currentUserId(authentication), id);
  }

  /**
   * Updates a subscription's name, filter, and delivery settings.
   *
   * @param id             the subscription ID
   * @param request        the new subscription details
   * @param authentication the authenticated caller
   * @return the updated subscription
   */
  @Operation(
      summary = "Update a subscription",
      description = "Replaces a subscription's name, search filter, and delivery settings. "
          + "Does not change whether the subscription is active — use pause/resume for that."
  )
  @ApiResponse(responseCode = "200", description = "Subscription updated")
  @ApiResponse(responseCode = "400", description = "Invalid request body")
  @ApiResponse(responseCode = "404", description = "Subscription not found")
  @ApiResponse(responseCode = "401", description = "Unauthorized")
  @PutMapping("/{id}")
  public SubscriptionResponse update(
      @PathVariable Long id,
      @Valid @RequestBody UpdateSubscriptionRequest request,
      Authentication authentication
  ) {
    return subscriptionService.update(currentUserId(authentication), id, request);
  }

  /**
   * Pauses a subscription, stopping notifications until it is resumed.
   *
   * @param id             the subscription ID
   * @param authentication the authenticated caller
   * @return the paused subscription
   */
  @Operation(summary = "Pause a subscription", description = "Stops notifications for the subscription until resumed.")
  @ApiResponse(responseCode = "200", description = "Subscription paused")
  @ApiResponse(responseCode = "404", description = "Subscription not found")
  @ApiResponse(responseCode = "401", description = "Unauthorized")
  @PatchMapping("/{id}/pause")
  public SubscriptionResponse pause(@PathVariable Long id, Authentication authentication) {
    return subscriptionService.pause(currentUserId(authentication), id);
  }

  /**
   * Resumes a paused subscription.
   *
   * @param id             the subscription ID
   * @param authentication the authenticated caller
   * @return the resumed subscription
   */
  @Operation(
      summary = "Resume a subscription",
      description = "Reactivates a paused subscription. Fails with 422 if the user's tariff "
          + "active subscription limit is reached."
  )
  @ApiResponse(responseCode = "200", description = "Subscription resumed")
  @ApiResponse(responseCode = "404", description = "Subscription not found")
  @ApiResponse(responseCode = "401", description = "Unauthorized")
  @ApiResponse(responseCode = "422", description = "Active subscription limit exceeded")
  @PatchMapping("/{id}/resume")
  public SubscriptionResponse resume(@PathVariable Long id, Authentication authentication) {
    return subscriptionService.resume(currentUserId(authentication), id);
  }

  /**
   * Deletes a subscription.
   *
   * @param id             the subscription ID
   * @param authentication the authenticated caller
   */
  @Operation(summary = "Delete a subscription", description = "Permanently deletes a subscription.")
  @ApiResponse(responseCode = "200", description = "Subscription deleted")
  @ApiResponse(responseCode = "404", description = "Subscription not found")
  @ApiResponse(responseCode = "401", description = "Unauthorized")
  @DeleteMapping("/{id}")
  public void delete(@PathVariable Long id, Authentication authentication) {
    subscriptionService.delete(currentUserId(authentication), id);
  }

  private Long currentUserId(Authentication authentication) {
    return Long.valueOf(authentication.getName());
  }
}
