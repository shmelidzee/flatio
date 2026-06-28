package com.flatio.integration.kufar.client;

import com.flatio.integration.core.RawListing;
import com.flatio.integration.kufar.config.KufarProperties;
import com.flatio.integration.kufar.dto.KufarAccount;
import com.flatio.integration.kufar.dto.KufarAd;
import com.flatio.integration.kufar.dto.KufarAdParameter;
import com.flatio.integration.kufar.dto.KufarImage;
import com.flatio.integration.kufar.dto.KufarPageLink;
import com.flatio.integration.kufar.dto.KufarPagination;
import com.flatio.integration.kufar.dto.KufarSearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
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
        new KufarProperties.CategoryConfig("KUFAR_APARTMENT_RENT", "BY", "30010"),
        new KufarProperties.CategoryConfig("KUFAR_APARTMENT_SALE", "BY", "30020"),
        new KufarProperties.CategoryConfig("KUFAR_ROOM_RENT", "BY", "30030"),
        new KufarProperties.CategoryConfig("KUFAR_ROOM_SALE", "BY", "30040"),
        new KufarProperties.CategoryConfig("KUFAR_HOUSE_RENT", "BY", "30050"),
        new KufarProperties.CategoryConfig("KUFAR_HOUSE_SALE", "BY", "30060")
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
    // Given
    var response = buildResponseWithAds(List.of(buildValidAd(123L, "Квартира в центре", 120000L, "private")));
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
    // Given
    var params = List.of(
        new KufarAdParameter("roomsCount", "2"),
        new KufarAdParameter("floor", "5"),
        new KufarAdParameter("totalFloors", "9"),
        new KufarAdParameter("area", "58.5"),
        new KufarAdParameter("geopoint", "53.9045;27.5615")
    );
    var image = new KufarImage("1", "https://content.kufar.by/img/1.jpg");
    var ad = new KufarAd(
        456L, "Двушка на Немиге", "Описание",
        90000L, "BYR", "https://www.kufar.by/item/456",
        new KufarAccount(100L, "private"), params,
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
    assertThat(listing.latitude()).isEqualByComparingTo("53.9045");
    assertThat(listing.longitude()).isEqualByComparingTo("27.5615");
    assertThat(listing.sourceUrl()).isEqualTo("https://www.kufar.by/item/456");
    assertThat(listing.photoUrls()).containsExactly("https://content.kufar.by/img/1.jpg");
    assertThat(listing.isOwner()).isTrue();
    assertThat(listing.publishedAt()).isNotNull();
  }

  // -------------------------------------------------------------------------
  // Price handling
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_skip_listing_when_price_is_missing_and_return_others() {
    // Given — ad without price and a valid ad
    var noPriceAd = new KufarAd(111L, "Без цены", null, null, null,
        "https://www.kufar.by/item/111", null, List.of(), List.of(), "2024-01-15T10:00:00+03:00");
    var validAd = buildValidAd(222L, "Нормальная квартира", 80000L, "private");
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
    var ad = new KufarAd(333L, "   ", null, 70000L, "BYR",
        "https://www.kufar.by/item/333", new KufarAccount(1L, "private"),
        List.of(), List.of(), "2024-01-15T10:00:00+03:00");
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
    var ad = new KufarAd(334L, null, null, 60000L, "BYR",
        "https://www.kufar.by/item/334", null, List.of(), List.of(), "2024-01-15T10:00:00+03:00");
    mockRestClientReturning(buildResponseWithAds(List.of(ad)));

    // When
    List<RawListing> result = apiClient.fetchAll(config, "SELL", "ROOM", "Комната (Kufar.by)");

    // Then
    assertThat(result).hasSize(1);
    assertThat(result.get(0).title()).isEqualTo("Комната (Kufar.by)");
  }

  // -------------------------------------------------------------------------
  // isOwner mapping
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_set_is_owner_true_when_account_type_is_private() {
    // Given
    var ad = buildValidAd(401L, "Квартира", 100000L, "private");
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
    var ad = buildValidAd(402L, "Квартира", 100000L, "company");
    mockRestClientReturning(buildResponseWithAds(List.of(ad)));

    // When
    List<RawListing> result = apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback");

    // Then
    assertThat(result.get(0).isOwner()).isFalse();
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_null_is_owner_when_account_is_absent() {
    // Given
    var ad = new KufarAd(403L, "Квартира", null, 100000L, "BYR",
        "https://www.kufar.by/item/403", null, List.of(), List.of(), "2024-01-15T10:00:00+03:00");
    mockRestClientReturning(buildResponseWithAds(List.of(ad)));

    // When
    List<RawListing> result = apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback");

    // Then — null, not false
    assertThat(result.get(0).isOwner()).isNull();
  }

  // -------------------------------------------------------------------------
  // Geopoint parsing
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_parse_geopoint_when_format_is_valid() {
    // Given
    var params = List.of(new KufarAdParameter("geopoint", "53.9045;27.5615"));
    var ad = new KufarAd(501L, "Квартира", null, 80000L, "BYR",
        "https://www.kufar.by/item/501", new KufarAccount(1L, "private"),
        params, List.of(), "2024-01-15T10:00:00+03:00");
    mockRestClientReturning(buildResponseWithAds(List.of(ad)));

    // When
    List<RawListing> result = apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback");

    // Then
    assertThat(result.get(0).latitude()).isEqualByComparingTo("53.9045");
    assertThat(result.get(0).longitude()).isEqualByComparingTo("27.5615");
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_null_coordinates_when_geopoint_is_malformed() {
    // Given
    var params = List.of(new KufarAdParameter("geopoint", "not_valid_coords"));
    var ad = new KufarAd(502L, "Квартира", null, 80000L, "BYR",
        "https://www.kufar.by/item/502", new KufarAccount(1L, "private"),
        params, List.of(), "2024-01-15T10:00:00+03:00");
    mockRestClientReturning(buildResponseWithAds(List.of(ad)));

    // When
    List<RawListing> result = apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback");

    // Then — listing still returned with null coordinates
    assertThat(result).hasSize(1);
    assertThat(result.get(0).latitude()).isNull();
    assertThat(result.get(0).longitude()).isNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_return_null_coordinates_when_geopoint_is_absent() {
    // Given — no geopoint param
    var ad = buildValidAd(503L, "Квартира", 90000L, "private");
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
    var ad = new KufarAd(601L, "Квартира", null, 75000L, "BYR",
        "https://www.kufar.by/item/601", null, List.of(), List.of(), "2024-01-15T10:00:00+03:00");
    mockRestClientReturning(buildResponseWithAds(List.of(ad)));

    // When
    List<RawListing> result = apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback");

    // Then
    assertThat(result.get(0).photoUrls()).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void should_extract_multiple_photo_urls() {
    // Given
    var images = List.of(
        new KufarImage("1", "https://cdn.kufar.by/img/1.jpg"),
        new KufarImage("2", "https://cdn.kufar.by/img/2.jpg")
    );
    var ad = new KufarAd(602L, "Квартира", null, 85000L, "BYR",
        "https://www.kufar.by/item/602", null, List.of(), images, "2024-01-15T10:00:00+03:00");
    mockRestClientReturning(buildResponseWithAds(List.of(ad)));

    // When
    List<RawListing> result = apiClient.fetchAll(config, "RENT", "APARTMENT", "Fallback");

    // Then
    assertThat(result.get(0).photoUrls()).containsExactly(
        "https://cdn.kufar.by/img/1.jpg",
        "https://cdn.kufar.by/img/2.jpg"
    );
  }

  // -------------------------------------------------------------------------
  // Cursor-based pagination
  // -------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void should_follow_cursor_and_fetch_second_page() {
    // Given — first response has a "next" cursor, second has none
    var firstResponse = buildResponseWithCursor(
        List.of(buildValidAd(701L, "Страница 1", 70000L, "private")),
        "cursor-token-abc"
    );
    var secondResponse = buildResponseWithAds(
        List.of(buildValidAd(702L, "Страница 2", 80000L, "private"))
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
    var response = buildResponseWithAds(List.of(buildValidAd(801L, "Единственная", 60000L, "private")));
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
    var newAd = new KufarAd(901L, "Новая", null, 80000L, "BYR",
        "https://www.kufar.by/item/901", null, List.of(), List.of(), "2024-01-15T12:00:00+00:00");
    var oldAd = new KufarAd(902L, "Старая", null, 80000L, "BYR",
        "https://www.kufar.by/item/902", null, List.of(), List.of(), "2024-01-14T08:00:00+00:00");
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
    var ad1 = new KufarAd(903L, "Объявление 1", null, 80000L, "BYR",
        "https://www.kufar.by/item/903", null, List.of(), List.of(), "2024-01-15T10:00:00+00:00");
    var ad2 = new KufarAd(904L, "Объявление 2", null, 90000L, "BYR",
        "https://www.kufar.by/item/904", null, List.of(), List.of(), "2024-01-10T08:00:00+00:00");
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
    var oldAd = new KufarAd(905L, "Старая", null, 80000L, "BYR",
        "https://www.kufar.by/item/905", null, List.of(), List.of(), "2024-01-10T10:00:00+00:00");
    mockRestClientReturning(buildResponseWithAds(List.of(oldAd)));

    // When
    List<RawListing> result = apiClient.fetchDelta(config, since, "RENT", "APARTMENT", "Fallback");

    // Then
    assertThat(result).isEmpty();
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
    var ad = new KufarAd(1001L, "Квартира", null, 75000L, "BYR",
        "https://www.kufar.by/item/1001", null, List.of(), List.of(), "2024-01-15T10:30:00+03:00");
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
    var ad = new KufarAd(1002L, "Квартира", null, 75000L, "BYR",
        "https://www.kufar.by/item/1002", null, List.of(), List.of(), null);
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
        "https://www.kufar.by/item/1101", null, List.of(), List.of(), "2024-01-15T10:00:00+03:00");
    var validAd = buildValidAd(1102L, "Нормальная", 90000L, "private");
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

    // Then — all snake_case JSON fields correctly mapped
    assertThat(result).hasSize(2);
    assertThat(result.get(0).externalId()).isEqualTo("123456789");
    assertThat(result.get(0).price()).isEqualByComparingTo("120000");
    assertThat(result.get(0).currency()).isEqualTo("BYN");
    assertThat(result.get(0).rooms()).isEqualTo(2);
    assertThat(result.get(0).floorNumber()).isEqualTo(5);
    assertThat(result.get(0).floorsTotal()).isEqualTo(9);
    assertThat(result.get(0).areaTotalM2()).isEqualByComparingTo("58.5");
    assertThat(result.get(0).latitude()).isEqualByComparingTo("53.9045");
    assertThat(result.get(0).isOwner()).isTrue();
    assertThat(result.get(0).photoUrls()).hasSize(1);
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

  private KufarAd buildValidAd(Long adId, String subject, Long priceByn, String accountType) {
    return new KufarAd(
        adId, subject, null, priceByn, "BYR",
        "https://www.kufar.by/item/" + adId,
        new KufarAccount(1L, accountType),
        List.of(),
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
