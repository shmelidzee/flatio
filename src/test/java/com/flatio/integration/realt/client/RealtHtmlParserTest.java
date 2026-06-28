package com.flatio.integration.realt.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flatio.integration.core.RawListing;
import com.flatio.service.CurrencyRateService;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RealtHtmlParserTest {

  @Mock
  private CurrencyRateService currencyRateService;

  private RealtHtmlParser parser;

  private static final RealtPageContext APARTMENT_RENT_CONTEXT = new RealtPageContext(
      "https://realt.by", "/rent-flat-for-long/object/", "REALT",
      "RENT", "APARTMENT", "Квартира на Realt.by"
  );

  private static final RealtPageContext ROOM_RENT_CONTEXT = new RealtPageContext(
      "https://realt.by", "/rent-room-for-long/object/", "REALT_ROOM",
      "RENT", "ROOM", "Комната на Realt.by"
  );

  private static final RealtPageContext HOUSE_SALE_CONTEXT = new RealtPageContext(
      "https://realt.by", "/sale-house/object/", "REALT_HOUSE_SALE",
      "SELL", "HOUSE", "Дом на Realt.by"
  );

  @BeforeEach
  void setUp() {
    parser = new RealtHtmlParser(new ObjectMapper(), currencyRateService);
  }

  // -------------------------------------------------------------------------
  // Happy path
  // -------------------------------------------------------------------------

  @Test
  void should_return_listings_when_valid_html_fixture_provided() throws IOException {
    // Given
    Document doc = parseFixture("fixtures/realt/valid-listing-page.html");

    // When
    List<RawListing> result = parser.parseListings(doc, APARTMENT_RENT_CONTEXT);

    // Then
    assertThat(result).hasSize(2);
    assertThat(result.get(0).externalId()).isEqualTo("12345678");
    assertThat(result.get(0).dealType()).isEqualTo("RENT");
    assertThat(result.get(0).propertyType()).isEqualTo("APARTMENT");
    assertThat(result.get(1).externalId()).isEqualTo("87654321");
  }

  @Test
  void should_use_deal_type_and_property_type_from_context() throws IOException {
    // Given
    Document doc = parseFixture("fixtures/realt/valid-listing-page.html");

    // When
    List<RawListing> roomResult = parser.parseListings(doc, ROOM_RENT_CONTEXT);
    List<RawListing> houseResult = parser.parseListings(doc, HOUSE_SALE_CONTEXT);

    // Then — dealType and propertyType come from context, not hardcoded
    assertThat(roomResult).isNotEmpty();
    assertThat(roomResult.get(0).dealType()).isEqualTo("RENT");
    assertThat(roomResult.get(0).propertyType()).isEqualTo("ROOM");

    assertThat(houseResult).isNotEmpty();
    assertThat(houseResult.get(0).dealType()).isEqualTo("SELL");
    assertThat(houseResult.get(0).propertyType()).isEqualTo("HOUSE");
  }

  @Test
  void should_map_all_required_fields_when_valid_card_parsed() throws IOException {
    // Given
    Document doc = parseFixture("fixtures/realt/valid-listing-page.html");

    // When
    List<RawListing> result = parser.parseListings(doc, APARTMENT_RENT_CONTEXT);
    RawListing first = result.get(0);

    // Then
    assertThat(first.externalId()).isEqualTo("12345678");
    assertThat(first.title()).isEqualTo("Снять 2-комнатную квартиру г. Минск, пр-т Независимости, 72");
    assertThat(first.dealType()).isEqualTo("RENT");
    assertThat(first.propertyType()).isEqualTo("APARTMENT");
    assertThat(first.currency()).isEqualTo("USD");
    assertThat(first.price()).isEqualByComparingTo(new BigDecimal("2500"));
    assertThat(first.priceUsd()).isNull();
    assertThat(first.address()).isEqualTo("г. Минск, пр-т Независимости, 72");
    assertThat(first.sourceUrl()).isEqualTo("https://realt.by/rent-flat-for-long/object/12345678/");
    assertThat(first.photoUrls()).hasSize(1);
    assertThat(first.rooms()).isEqualTo(2);
    assertThat(first.floorNumber()).isEqualTo(5);
    assertThat(first.floorsTotal()).isEqualTo(9);
    assertThat(first.areaTotalM2()).isEqualByComparingTo(new BigDecimal("58"));
    assertThat(first.city()).isEqualTo("Минск");
    assertThat(first.isOwner()).isTrue();
    assertThat(first.publishedAt()).isNotNull();
  }

  @Test
  void should_use_context_object_path_in_source_url() throws IOException {
    // Given — room context uses a different object path prefix
    Document doc = parseFixture("fixtures/realt/valid-listing-page.html");

    // When
    List<RawListing> result = parser.parseListings(doc, ROOM_RENT_CONTEXT);

    // Then — source URL uses the room-specific path from context
    assertThat(result.get(0).sourceUrl())
        .isEqualTo("https://realt.by/rent-room-for-long/object/12345678/");
  }

  // -------------------------------------------------------------------------
  // Currency handling
  // -------------------------------------------------------------------------

  @Test
  void should_set_currency_to_usd_and_price_usd_to_null_when_price_currency_is_840() throws IOException {
    // Given — realt.by stores prices in USD (priceCurrency=840, ISO 4217: 840=USD)
    Document doc = parseFixture("fixtures/realt/valid-listing-page.html");

    // When
    List<RawListing> result = parser.parseListings(doc, APARTMENT_RENT_CONTEXT);

    // Then
    for (RawListing listing : result) {
      assertThat(listing.currency()).isEqualTo("USD");
      assertThat(listing.priceUsd()).isNull();
    }
  }

  @Test
  void should_compute_price_byn_when_usd_rate_is_available() {
    // Given
    String html = buildPageWithSingleListing(
        "{\"code\":88800001,\"title\":\"Rate test\",\"price\":650,\"priceCurrency\":840,\"images\":[]}");
    Document doc = Jsoup.parse(html, "https://realt.by");
    when(currencyRateService.getUsdToByn()).thenReturn(Optional.of(new BigDecimal("2.82")));

    // When
    List<RawListing> result = parser.parseListings(doc, APARTMENT_RENT_CONTEXT);

    // Then — priceByn = 650 * 2.82 = 1833.00
    assertThat(result).hasSize(1);
    assertThat(result.get(0).priceByn()).isEqualByComparingTo(new BigDecimal("1833.00"));
  }

  @Test
  void should_set_price_byn_to_null_when_rate_service_returns_empty() {
    // Given
    String html = buildPageWithSingleListing(
        "{\"code\":88800002,\"title\":\"No rate\",\"price\":650,\"priceCurrency\":840,\"images\":[]}");
    Document doc = Jsoup.parse(html, "https://realt.by");
    when(currencyRateService.getUsdToByn()).thenReturn(Optional.empty());

    // When
    List<RawListing> result = parser.parseListings(doc, APARTMENT_RENT_CONTEXT);

    // Then
    assertThat(result).hasSize(1);
    assertThat(result.get(0).priceByn()).isNull();
    assertThat(result.get(0).currency()).isEqualTo("USD");
  }

  @Test
  void should_set_price_byn_to_null_when_listing_currency_is_byn() {
    // Given — priceCurrency=933 (BYN)
    String html = buildPageWithSingleListing(
        "{\"code\":88800003,\"title\":\"BYN listing\",\"price\":1500,\"priceCurrency\":933,\"images\":[]}");
    Document doc = Jsoup.parse(html, "https://realt.by");

    // When
    List<RawListing> result = parser.parseListings(doc, APARTMENT_RENT_CONTEXT);

    // Then
    assertThat(result).hasSize(1);
    assertThat(result.get(0).currency()).isEqualTo("BYN");
    assertThat(result.get(0).priceByn()).isNull();
  }

  // -------------------------------------------------------------------------
  // Empty and missing data
  // -------------------------------------------------------------------------

  @Test
  void should_return_empty_list_when_page_has_no_listing_objects() throws IOException {
    // Given
    Document doc = parseFixture("fixtures/realt/empty-listing-page.html");

    // When
    List<RawListing> result = parser.parseListings(doc, APARTMENT_RENT_CONTEXT);

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_return_empty_list_when_html_has_no_next_data_script() {
    // Given
    Document doc = Jsoup.parse("<html><body>Service unavailable</body></html>", "https://realt.by");

    // When
    List<RawListing> result = parser.parseListings(doc, APARTMENT_RENT_CONTEXT);

    // Then
    assertThat(result).isEmpty();
  }

  @Test
  void should_return_empty_list_when_next_data_exceeds_size_limit() {
    // Given — payload larger than MAX_NEXT_DATA_SIZE (5 MB)
    String oversizedJson = "{\"props\":{\"pageProps\":{\"objects\":[{\"x\":\""
        + "a".repeat(6 * 1024 * 1024)
        + "\"}]}}}";
    String html = "<html><body><script id=\"__NEXT_DATA__\" type=\"application/json\">"
        + oversizedJson + "</script></body></html>";
    Document doc = Jsoup.parse(html, "https://realt.by");

    // When
    List<RawListing> result = parser.parseListings(doc, APARTMENT_RENT_CONTEXT);

    // Then
    assertThat(result).isEmpty();
  }

  // -------------------------------------------------------------------------
  // Error isolation — broken cards
  // -------------------------------------------------------------------------

  @Test
  void should_skip_listing_without_price_and_return_valid_ones() throws IOException {
    // Given
    Document doc = parseFixture("fixtures/realt/listing-page-without-price.html");

    // When
    List<RawListing> result = parser.parseListings(doc, APARTMENT_RENT_CONTEXT);

    // Then
    assertThat(result).hasSize(1);
    assertThat(result.get(0).externalId()).isEqualTo("11111111");
  }

  @Test
  void should_skip_card_without_external_id_and_return_valid_ones() throws IOException {
    // Given
    Document doc = parseFixture("fixtures/realt/listing-page-with-broken-card.html");

    // When
    List<RawListing> result = parser.parseListings(doc, APARTMENT_RENT_CONTEXT);

    // Then
    assertThat(result).hasSize(1);
    assertThat(result.get(0).externalId()).isEqualTo("33333333");
  }

  @Test
  void should_not_throw_exception_when_single_card_is_broken() throws IOException {
    // Given
    Document doc = parseFixture("fixtures/realt/listing-page-with-broken-card.html");

    // When / Then
    assertThatNoException().isThrownBy(() -> parser.parseListings(doc, APARTMENT_RENT_CONTEXT));
  }

  @Test
  void should_use_fallback_title_from_context_when_title_and_headline_are_null() throws IOException {
    // Given — second listing in fixture has null title and null headline
    Document doc = parseFixture("fixtures/realt/valid-listing-page.html");

    // When — apartment context uses "Квартира на Realt.by", room context uses "Комната на Realt.by"
    List<RawListing> aptResult = parser.parseListings(doc, APARTMENT_RENT_CONTEXT);
    List<RawListing> roomResult = parser.parseListings(doc, ROOM_RENT_CONTEXT);

    // Then — fallback title comes from context
    assertThat(aptResult.get(1).title()).isEqualTo("Квартира на Realt.by");
    assertThat(roomResult.get(1).title()).isEqualTo("Комната на Realt.by");
  }

  // -------------------------------------------------------------------------
  // isOwner extraction
  // -------------------------------------------------------------------------

  @Test
  void should_return_null_is_owner_when_company_uuid_field_is_absent() {
    // Given
    String html = buildPageWithSingleListing(
        "{\"code\":55555555,\"title\":\"Test\",\"price\":500,\"priceCurrency\":840,\"images\":[]}");
    Document doc = Jsoup.parse(html, "https://realt.by");

    // When
    List<RawListing> result = parser.parseListings(doc, APARTMENT_RENT_CONTEXT);

    // Then
    assertThat(result).hasSize(1);
    assertThat(result.get(0).isOwner()).isNull();
  }

  @Test
  void should_return_false_is_owner_when_company_uuid_is_present() {
    // Given
    String html = buildPageWithSingleListing(
        "{\"code\":66666666,\"title\":\"Agency flat\",\"price\":600,\"priceCurrency\":840,"
            + "\"companyUuid\":\"abc-123-def-456\",\"images\":[]}");
    Document doc = Jsoup.parse(html, "https://realt.by");

    // When
    List<RawListing> result = parser.parseListings(doc, APARTMENT_RENT_CONTEXT);

    // Then
    assertThat(result).hasSize(1);
    assertThat(result.get(0).isOwner()).isFalse();
  }

  // -------------------------------------------------------------------------
  // Photo URL security — SSRF guard
  // -------------------------------------------------------------------------

  @Test
  void should_filter_out_non_https_photo_urls() {
    // Given
    String html = buildPageWithSingleListing(
        "{\"code\":77777777,\"title\":\"Test\",\"price\":500,\"priceCurrency\":840,"
            + "\"companyUuid\":null,"
            + "\"images\":["
            + "  \"https://cdn.realt.by/img/valid.jpg\","
            + "  \"http://cdn.realt.by/img/insecure.jpg\","
            + "  \"javascript:alert(1)\","
            + "  \"data:image/png;base64,abc\""
            + "]}");
    Document doc = Jsoup.parse(html, "https://realt.by");

    // When
    List<RawListing> result = parser.parseListings(doc, APARTMENT_RENT_CONTEXT);

    // Then
    assertThat(result).hasSize(1);
    assertThat(result.get(0).photoUrls()).containsExactly("https://cdn.realt.by/img/valid.jpg");
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private String buildPageWithSingleListing(String listingJson) {
    return "<html><body><script id=\"__NEXT_DATA__\" type=\"application/json\">"
        + "{\"props\":{\"pageProps\":{\"objects\":[" + listingJson + "]}}}"
        + "</script></body></html>";
  }

  private Document parseFixture(String path) throws IOException {
    InputStream stream = getClass().getClassLoader().getResourceAsStream(path);
    assertThat(stream).as("fixture file not found on classpath: %s", path).isNotNull();
    String html = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    return Jsoup.parse(html, "https://realt.by");
  }
}
