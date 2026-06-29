package com.flatio.integration.kufar.client;

import com.flatio.integration.core.RawListing;
import com.flatio.integration.kufar.config.KufarProperties;
import com.flatio.integration.kufar.dto.KufarAd;
import com.flatio.integration.kufar.dto.KufarAdParameter;
import com.flatio.integration.kufar.dto.KufarAccount;
import com.flatio.integration.kufar.dto.KufarImage;
import com.flatio.integration.kufar.dto.KufarPageLink;
import com.flatio.integration.kufar.dto.KufarPagination;
import com.flatio.integration.kufar.dto.KufarSearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KufarApiClientTest {

  @Mock
  private RestClient restClient;

  @Mock
  @SuppressWarnings("rawtypes")
  private RestClient.RequestHeadersUriSpec requestHeadersUriSpec;

  @Mock
  @SuppressWarnings("rawtypes")
  private RestClient.RequestHeadersSpec requestHeadersSpec;

  @Mock
  private RestClient.ResponseSpec responseSpec;

  private KufarApiClient apiClient;
  private KufarProperties.CategoryConfig config;

  @BeforeEach
  void setUp() {
    var properties = new KufarProperties(
        "https://api.kufar.by",
        "/search-api/v2/search/rendered-paginated",
        50,
        "ru",
        "https://content.kufar.by/listings_thumbnails",
        new KufarProperties.CategoryConfig("KUFAR_APARTMENT_RENT", "BY", "1010", "let"),
        new KufarProperties.CategoryConfig("KUFAR_APARTMENT_SALE", "BY", "1010", "sell"),
        new KufarProperties.CategoryConfig("KUFAR_ROOM_RENT", "BY", "1040", "let"),
        new KufarProperties.CategoryConfig("KUFAR_ROOM_SALE", "BY", "1040", "sell"),
        new KufarProperties.CategoryConfig("KUFAR_HOUSE_RENT", "BY", "1020", "let"),
        new KufarProperties.CategoryConfig("KUFAR_HOUSE_SALE", "BY", "1020", "sell")
    );
    apiClient = new KufarApiClient(restClient, properties);
    config = properties.apartmentRent();
  }

  // -------------------------------------------------------------------------
  // Happy path
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_return_listings_when_valid_response_provided() {
    // Given — price 12000000 kopecks = 120000 BYN
    var response = buildResponseWithAds(List.of(buildValidAd(123L, "Квартира в центре", 12000000L, "private")));
    mockRestClientReturning(response);

    // When
    List<RawListing> result = apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback");

    // Then
    assertThat(result).hasSize(1);
    assertThat(result.get(0).externalId()).isEqualTo("123");
    assertThat(result.get(0).title()).isEqualTo("Квартира в центре");
    assertThat(result.get(0).dealType()).isEqualTo("RENT");
    assertThat(result.get(0).propertyType()).isEqualTo("APARTMENT");
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_map_all_required_fields_correctly() {
    // Given — attributes in ad_parameters with machine key p; price 9000000 kopecks = 90000 BYN
    var adParams = List.of(
        new KufarAdParameter("Комнат", "rooms", "2", "2"),
        new KufarAdParameter("Этаж", "floor", "5", "5"),
        new KufarAdParameter("Этажность дома", "re_number_floors", "9", "9"),
        new KufarAdParameter("Общая площадь", "size", "", "58.5")
    );
    var image = new KufarImage("1", "adim1/456/1.jpg");
    var ad = new KufarAd(
        456L, "Двушка на Немиге", "Описание",
        9000000L, "BYR", "https://re.kufar.by/vi/456",
        new KufarAccount(100L, "private"), List.of(),
        adParams, null,
        List.of(image), "2024-01-15T10:30:00+03:00"
    );
    mockRestClientReturning(buildResponseWithAds(List.of(ad)));

    // When
    List<RawListing> result = apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback");
    RawListing listing = result.get(0);

    // Then
    assertThat(listing.externalId()).isEqualTo("456");
    assertThat(listing.title()).isEqualTo("Двушка на Немиге");
    assertThat(listing.description()).isEqualTo("Описание");
    assertThat(listing.price()).isEqualByComparingTo("90000");
    assertThat(listing.currency()).isEqualTo("BYN");
    assertThat(listing.rooms()).isEqualTo(2);
    assertThat(listing.floorNumber()).isEqualTo(5);
    assertThat(listing.floorsTotal()).isEqualTo(9);
    assertThat(listing.areaTotalM2()).isEqualByComparingTo("58.5");
    assertThat(listing.latitude()).isNull();
    assertThat(listing.longitude()).isNull();
    assertThat(listing.sourceUrl()).isEqualTo("https://re.kufar.by/vi/456");
    assertThat(listing.photoUrls()).containsExactly(
        "https://content.kufar.by/listings_thumbnails/adim1/456/1.jpg");
    assertThat(listing.isOwner()).isTrue();
    assertThat(listing.publishedAt()).isNotNull();
  }

  // -------------------------------------------------------------------------
  // Price handling
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_divide_price_byn_by_100_to_convert_kopecks_to_byn() {
    // Given — 15000000 kopecks = 150000 BYN
    var ad = buildValidAd(789L, "Квартира", 15000000L, "private");
    mockRestClientReturning(buildResponseWithAds(List.of(ad)));

    // When
    List<RawListing> result = apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback");

    // Then
    assertThat(result.get(0).price()).isEqualByComparingTo("150000");
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_skip_listing_when_price_is_missing_and_return_others() {
    // Given — ad without price and a valid ad
    var noPriceAd = new KufarAd(111L, "Без цены", null, null, null,
        "https://re.kufar.by/vi/111", null, List.of(), List.of(), null, List.of(), "2024-01-15T10:00:00+03:00");
    var validAd = buildValidAd(222L, "Нормальная квартира", 8000000L, "private");
    mockRestClientReturning(buildResponseWithAds(List.of(noPriceAd, validAd)));

    // When
    List<RawListing> result = apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback");

    // Then — no-price listing skipped, valid one returned
    assertThat(result).hasSize(1);
    assertThat(result.get(0).externalId()).isEqualTo("222");
  }

  // -------------------------------------------------------------------------
  // Title fallback
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_use_fallback_title_when_subject_is_blank() {
    // Given — ad with blank subject
    var ad = new KufarAd(333L, "   ", null, 7000000L, "BYR",
        "https://re.kufar.by/vi/333", new KufarAccount(1L, "private"),
        List.of(), List.of(), null, List.of(), "2024-01-15T10:00:00+03:00");
    mockRestClientReturning(buildResponseWithAds(List.of(ad)));

    // When
    List<RawListing> result = apiClient.fetchAll(config, "RENT", "APARTMENT", "Квартира (Kufar.by)");

    // Then
    assertThat(result).hasSize(1);
    assertThat(result.get(0).title()).isEqualTo("Квартира (Kufar.by)");
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_use_fallback_title_when_subject_is_null() {
    // Given
    var ad = new KufarAd(334L, null, null, 6000000L, "BYR",
        "https://re.kufar.by/vi/334", null, List.of(), List.of(), null, List.of(), "2024-01-15T10:00:00+03:00");
    mockRestClientReturning(buildResponseWithAds(List.of(ad)));

    // When
    List<RawListing> result = apiClient.fetchAll(config, "SELL", "ROOM", "Комната (Kufar.by)");

    // Then
    assertThat(result).hasSize(1);
    assertThat(result.get(0).title()).isEqualTo("Комната (Kufar.by)");
  }

  // -------------------------------------------------------------------------
  // isOwner mapping — via account field
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_set_is_owner_true_when_account_type_is_private() {
    // Given
    var ad = buildValidAd(401L, "Квартира", 10000000L, "private");
    mockRestClientReturning(buildResponseWithAds(List.of(ad)));

    // When
    List<RawListing> result = apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback");

    // Then
    assertThat(result.get(0).isOwner()).isTrue();
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_set_is_owner_false_when_account_type_is_company() {
    // Given
    var ad = buildValidAd(402L, "Квартира", 10000000L, "company");
    mockRestClientReturning(buildResponseWithAds(List.of(ad)));

    // When
    List<RawListing> result = apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback");

    // Then
    assertThat(result.get(0).isOwner()).isFalse();
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_null_is_owner_when_account_and_company_ad_are_absent() {
    // Given — account null, companyAd null
    var ad = new KufarAd(403L, "Квартира", null, 10000000L, "BYR",
        "https://re.kufar.by/vi/403", null, List.of(), List.of(), null, List.of(), "2024-01-15T10:00:00+03:00");
    mockRestClientReturning(buildResponseWithAds(List.of(ad)));

    // When
    List<RawListing> result = apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback");

    // Then — null, not false
    assertThat(result.get(0).isOwner()).isNull();
  }

  // -------------------------------------------------------------------------
  // isOwner mapping — via company_ad field (real API path)
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_set_is_owner_true_when_company_ad_is_false() {
    // Given — account absent, company_ad = false → private owner
    var ad = new KufarAd(404L, "Квартира", null, 10000000L, "BYR",
        "https://re.kufar.by/vi/404", null, List.of(), List.of(), false, List.of(), "2024-01-15T10:00:00+03:00");
    mockRestClientReturning(buildResponseWithAds(List.of(ad)));

    // When
    List<RawListing> result = apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback");

    // Then
    assertThat(result.get(0).isOwner()).isTrue();
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_set_is_owner_false_when_company_ad_is_true() {
    // Given — account absent, company_ad = true → agency
    var ad = new KufarAd(405L, "Квартира", null, 10000000L, "BYR",
        "https://re.kufar.by/vi/405", null, List.of(), List.of(), true, List.of(), "2024-01-15T10:00:00+03:00");
    mockRestClientReturning(buildResponseWithAds(List.of(ad)));

    // When
    List<RawListing> result = apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback");

    // Then
    assertThat(result.get(0).isOwner()).isFalse();
  }

  // -------------------------------------------------------------------------
  // Coordinates — not parsed from ad_parameters (Nominatim geocoding fills them in)
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_return_null_coordinates_always() {
    // Given — coordinates come from Kufar API v field (array), not mapped here; Nominatim handles geocoding
    var ad = buildValidAd(503L, "Квартира", 9000000L, "private");
    mockRestClientReturning(buildResponseWithAds(List.of(ad)));

    // When
    List<RawListing> result = apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback");

    // Then
    assertThat(result.get(0).latitude()).isNull();
    assertThat(result.get(0).longitude()).isNull();
  }

  // -------------------------------------------------------------------------
  // Photos
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_return_empty_photo_list_when_images_are_empty() {
    // Given
    var ad = new KufarAd(601L, "Квартира", null, 7500000L, "BYR",
        "https://re.kufar.by/vi/601", null, List.of(), List.of(), null, List.of(), "2024-01-15T10:00:00+03:00");
    mockRestClientReturning(buildResponseWithAds(List.of(ad)));

    // When
    List<RawListing> result = apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback");

    // Then
    assertThat(result.get(0).photoUrls()).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_extract_multiple_photo_urls() {
    // Given — relative paths as returned by the real Kufar API
    var images = List.of(
        new KufarImage("1", "adim1/uuid-1.jpg"),
        new KufarImage("2", "adim1/uuid-2.jpg")
    );
    var ad = new KufarAd(602L, "Квартира", null, 8500000L, "BYR",
        "https://re.kufar.by/vi/602", null, List.of(), List.of(), null, images, "2024-01-15T10:00:00+03:00");
    mockRestClientReturning(buildResponseWithAds(List.of(ad)));

    // When
    List<RawListing> result = apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback");

    // Then — CDN base URL prepended to each relative path
    assertThat(result.get(0).photoUrls()).containsExactly(
        "https://content.kufar.by/listings_thumbnails/adim1/uuid-1.jpg",
        "https://content.kufar.by/listings_thumbnails/adim1/uuid-2.jpg"
    );
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_prepend_cdn_base_url_to_relative_photo_path() {
    // Given
    var image = new KufarImage("1", "adim1/abc-uuid.jpg");
    var ad = new KufarAd(603L, "Квартира", null, 8000000L, "BYR",
        "https://re.kufar.by/vi/603", null, List.of(), List.of(), null,
        List.of(image), "2024-01-15T10:00:00+03:00");
    mockRestClientReturning(buildResponseWithAds(List.of(ad)));

    // When
    List<RawListing> result = apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback");

    // Then — full URL = cdnBase + "/" + path
    assertThat(result.get(0).photoUrls()).containsExactly(
        "https://content.kufar.by/listings_thumbnails/adim1/abc-uuid.jpg"
    );
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_not_prepend_cdn_url_when_path_is_already_absolute() {
    // Given — path already starts with https:// (defensive guard against already-migrated data)
    var image = new KufarImage("1", "https://other-cdn.kufar.by/img/1.jpg");
    var ad = new KufarAd(604L, "Квартира", null, 8000000L, "BYR",
        "https://re.kufar.by/vi/604", null, List.of(), List.of(), null,
        List.of(image), "2024-01-15T10:00:00+03:00");
    mockRestClientReturning(buildResponseWithAds(List.of(ad)));

    // When
    List<RawListing> result = apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback");

    // Then — URL used as-is, no double-prepend
    assertThat(result.get(0).photoUrls()).containsExactly("https://other-cdn.kufar.by/img/1.jpg");
  }

  // -------------------------------------------------------------------------
  // Cursor-based pagination
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_follow_cursor_and_fetch_second_page() {
    // Given — first response has a "next" cursor, second has none
    var firstResponse = buildResponseWithCursor(
        List.of(buildValidAd(701L, "Страница 1", 7000000L, "private")),
        "cursor-token-abc"
    );
    var secondResponse = buildResponseWithAds(
        List.of(buildValidAd(702L, "Страница 2", 8000000L, "private"))
    );
    when(restClient.get()).thenReturn(requestHeadersUriSpec);
    when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
    when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.body(KufarSearchResponse.class))
        .thenReturn(firstResponse)
        .thenReturn(secondResponse);

    // When
    List<RawListing> result = apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback");

    // Then
    assertThat(result).hasSize(2);
    assertThat(result).extracting(RawListing::externalId).containsExactly("701", "702");
    verify(responseSpec, times(2)).body(KufarSearchResponse.class);
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_stop_pagination_when_no_next_cursor() {
    // Given — response with no "next" cursor
    var response = buildResponseWithAds(List.of(buildValidAd(801L, "Единственная", 6000000L, "private")));
    mockRestClientReturning(response);

    // When
    List<RawListing> result = apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback");

    // Then — only one page fetched
    assertThat(result).hasSize(1);
    verify(responseSpec, times(1)).body(KufarSearchResponse.class);
  }

  // -------------------------------------------------------------------------
  // Delta fetch
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_stop_delta_when_listing_time_is_before_since() {
    // Given — two ads: one newer than since, one older
    Instant since = Instant.parse("2024-01-15T10:00:00Z");
    var newAd = new KufarAd(901L, "Новая", null, 8000000L, "BYR",
        "https://re.kufar.by/vi/901", null, List.of(), List.of(), null, List.of(), "2024-01-15T12:00:00+00:00");
    var oldAd = new KufarAd(902L, "Старая", null, 8000000L, "BYR",
        "https://re.kufar.by/vi/902", null, List.of(), List.of(), null, List.of(), "2024-01-14T08:00:00+00:00");
    mockRestClientReturning(buildResponseWithAds(List.of(newAd, oldAd)));

    // When
    List<RawListing> result = apiClient.fetchDelta(config, since, "RENT", "APARTMENT", "Fallback");

    // Then — only the new one returned
    assertThat(result).hasSize(1);
    assertThat(result.get(0).externalId()).isEqualTo("901");
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_all_listings_when_all_are_newer_than_since() {
    // Given
    Instant since = Instant.parse("2024-01-01T00:00:00Z");
    var ad1 = new KufarAd(903L, "Объявление 1", null, 8000000L, "BYR",
        "https://re.kufar.by/vi/903", null, List.of(), List.of(), null, List.of(), "2024-01-15T10:00:00+00:00");
    var ad2 = new KufarAd(904L, "Объявление 2", null, 9000000L, "BYR",
        "https://re.kufar.by/vi/904", null, List.of(), List.of(), null, List.of(), "2024-01-10T08:00:00+00:00");
    mockRestClientReturning(buildResponseWithAds(List.of(ad1, ad2)));

    // When
    List<RawListing> result = apiClient.fetchDelta(config, since, "RENT", "APARTMENT", "Fallback");

    // Then
    assertThat(result).hasSize(2);
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_empty_list_when_all_listings_are_older_than_since() {
    // Given
    Instant since = Instant.parse("2024-01-20T00:00:00Z");
    var oldAd = new KufarAd(905L, "Старая", null, 8000000L, "BYR",
        "https://re.kufar.by/vi/905", null, List.of(), List.of(), null, List.of(), "2024-01-10T10:00:00+00:00");
    mockRestClientReturning(buildResponseWithAds(List.of(oldAd)));

    // When
    List<RawListing> result = apiClient.fetchDelta(config, since, "RENT", "APARTMENT", "Fallback");

    // Then
    assertThat(result).isEmpty();
  }

  // -------------------------------------------------------------------------
  // Request parameters — AC #5 and AC #6
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_send_correct_cat_and_typ_params_in_request() {
    // Given
    mockRestClientReturning(new KufarSearchResponse(List.of(), null, 0));
    ArgumentCaptor<Function<UriBuilder, URI>> uriCaptor = ArgumentCaptor.forClass(Function.class);

    // When
    apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback");

    // Then — cat and typ params match the config values
    verify(requestHeadersUriSpec).uri(uriCaptor.capture());
    URI actualUri = uriCaptor.getValue().apply(UriComponentsBuilder.newInstance());
    assertThat(actualUri.getQuery())
        .contains("cat=1010")
        .contains("typ=let");
  }

  @Test
  void should_return_empty_list_when_category_code_is_blank() {
    // Given — config with blank categoryCode simulates misconfigured env variable
    var blankConfig = new KufarProperties.CategoryConfig("KUFAR_TEST", "BY", "", "let");

    // When
    List<RawListing> result = apiClient.fetchAll(blankConfig, "RENT", "APARTMENT", "Fallback");

    // Then — no HTTP call made, empty list returned
    assertThat(result).isEmpty();
    verify(restClient, never()).get();
  }

  // -------------------------------------------------------------------------
  // Empty and null responses
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_return_empty_list_when_ads_list_is_empty() {
    // Given
    mockRestClientReturning(new KufarSearchResponse(List.of(), null, 0));

    // When
    List<RawListing> result = apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback");

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_empty_list_when_response_is_null() {
    // Given
    mockRestClientReturning(null);

    // When / Then — no exception, graceful degradation
    assertThatNoException().isThrownBy(
        () -> apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback"));
  }

  // -------------------------------------------------------------------------
  // publishedAt mapping
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_parse_list_time_as_published_at() {
    // Given
    var ad = new KufarAd(1001L, "Квартира", null, 7500000L, "BYR",
        "https://re.kufar.by/vi/1001", null, List.of(), List.of(), null, List.of(), "2024-01-15T10:30:00+03:00");
    mockRestClientReturning(buildResponseWithAds(List.of(ad)));

    // When
    List<RawListing> result = apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback");

    // Then
    assertThat(result.get(0).publishedAt()).isEqualTo(Instant.parse("2024-01-15T07:30:00Z"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_null_published_at_when_list_time_is_null() {
    // Given
    var ad = new KufarAd(1002L, "Квартира", null, 7500000L, "BYR",
        "https://re.kufar.by/vi/1002", null, List.of(), List.of(), null, List.of(), null);
    mockRestClientReturning(buildResponseWithAds(List.of(ad)));

    // When
    List<RawListing> result = apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback");

    // Then
    assertThat(result.get(0).publishedAt()).isNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_not_propagate_exception_when_single_listing_fails() {
    // Given — two ads, first will fail (no price), second is valid
    var brokenAd = new KufarAd(1101L, "Битое", null, null, null,
        "https://re.kufar.by/vi/1101", null, List.of(), List.of(), null, List.of(), "2024-01-15T10:00:00+03:00");
    var validAd = buildValidAd(1102L, "Нормальная", 9000000L, "private");
    mockRestClientReturning(buildResponseWithAds(List.of(brokenAd, validAd)));

    // When / Then — no exception thrown, valid listing returned
    assertThatNoException().isThrownBy(
        () -> apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback"));

    List<RawListing> result = apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback");
    assertThat(result).hasSize(1);
    assertThat(result.get(0).externalId()).isEqualTo("1102");
  }

  // -------------------------------------------------------------------------
  // Fixture-based deserialization
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_correctly_deserialize_valid_apartment_fixture() throws Exception {
    // Given — loads actual Kufar API snapshot fixture
    var response = loadFixture("fixtures/kufar/valid-apartment-response.json");
    mockRestClientReturning(response);

    // When
    List<RawListing> result = apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback");

    // Then — price_byn "12000000" kopecks / 100 = 120000 BYN; ad_parameters mapped correctly
    assertThat(result).hasSize(2);
    assertThat(result.get(0).externalId()).isEqualTo("123456789");
    assertThat(result.get(0).price()).isEqualByComparingTo("120000");
    assertThat(result.get(0).currency()).isEqualTo("BYN");
    assertThat(result.get(0).rooms()).isEqualTo(2);
    assertThat(result.get(0).floorNumber()).isEqualTo(5);
    assertThat(result.get(0).floorsTotal()).isEqualTo(9);
    assertThat(result.get(0).areaTotalM2()).isEqualByComparingTo("58.5");
    assertThat(result.get(0).isOwner()).isTrue();
    assertThat(result.get(0).photoUrls()).containsExactly(
        "https://content.kufar.by/listings_thumbnails/adim1/123456789/1.jpg");
    assertThat(result.get(1).externalId()).isEqualTo("987654321");
    assertThat(result.get(1).isOwner()).isFalse();
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_empty_list_from_empty_fixture() throws Exception {
    // Given
    var response = loadFixture("fixtures/kufar/empty-response.json");
    mockRestClientReturning(response);

    // When
    List<RawListing> result = apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback");

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_skip_listing_without_price_from_fixture() throws Exception {
    // Given — fixture has ad 111111111 (no price) and ad 222222222 (valid)
    var response = loadFixture("fixtures/kufar/response-without-price.json");
    mockRestClientReturning(response);

    // When
    List<RawListing> result = apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback");

    // Then — only the valid one returned
    assertThat(result).hasSize(1);
    assertThat(result.get(0).externalId()).isEqualTo("222222222");
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_empty_list_from_broken_fixture_without_exception() throws Exception {
    // Given — JSON with unexpected structure
    var response = loadFixture("fixtures/kufar/broken-response.json");
    mockRestClientReturning(response);

    // When / Then
    assertThatNoException().isThrownBy(
        () -> apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback"));
    List<RawListing> result = apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback");
    assertThat(result).isEmpty();
  }

  // -------------------------------------------------------------------------
  // Helpers — mock setup
  // -------------------------------------------------------------------------

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void mockRestClientReturning(KufarSearchResponse response) {
    when(restClient.get()).thenReturn(requestHeadersUriSpec);
    when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersSpec);
    when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.body(KufarSearchResponse.class)).thenReturn(response);
  }

  // -------------------------------------------------------------------------
  // Helpers — fixture loading
  // -------------------------------------------------------------------------

  private KufarSearchResponse loadFixture(String path) throws Exception {
    try (var stream = getClass().getClassLoader().getResourceAsStream(path)) {
      assertThat(stream).as("fixture file not found on classpath: %s", path).isNotNull();
      var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
      return mapper.readValue(stream, KufarSearchResponse.class);
    }
  }

  // -------------------------------------------------------------------------
  // Helpers — object builders
  // -------------------------------------------------------------------------

  /**
   * Builds a valid KufarAd with no ad_parameters (rooms/floor/area will be null).
   * Use priceByn in kopecks (e.g. 10000000 = 100000 BYN).
   */
  private KufarAd buildValidAd(Long adId, String subject, Long priceByn, String accountType) {
    return new KufarAd(
        adId, subject, null, priceByn, "BYR",
        "https://re.kufar.by/vi/" + adId,
        new KufarAccount(1L, accountType),
        List.of(),
        List.of(),
        null,
        List.of(),
        "2024-01-15T10:00:00+03:00"
    );
  }

  private KufarSearchResponse buildResponseWithAds(List<KufarAd> ads) {
    return new KufarSearchResponse(ads, new KufarPagination(List.of()), ads.size());
  }

  private KufarSearchResponse buildResponseWithCursor(List<KufarAd> ads, String nextCursor) {
    var pages = List.of(
        new KufarPageLink(null, "prev"),
        new KufarPageLink(nextCursor, "next")
    );
    return new KufarSearchResponse(ads, new KufarPagination(pages), ads.size());
  }
}
