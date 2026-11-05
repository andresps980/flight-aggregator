package com.andresps.flights.ingestion.infrastructure.adapter.out.provider;

import static com.andresps.flights.ingestion.infrastructure.adapter.out.provider.ParserTestSupport.parse;
import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.flights.aggregation.domain.model.FlightOffer;
import com.andresps.flights.aggregation.domain.model.ItineraryKey;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.alpha.AlphaJsonParser;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.beta.BetaXmlParser;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.gamma.GammaCsvParser;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AC-2/AC-3: the providers.md example (itinerary 20261106-000001 from the deterministic mock: VY1 DUB → EZE + VY8227 EZE
 * → FRA) written in the three formats, with three time representations and three flight-number styles, is one
 * itinerary.
 */
class CrossProviderNormalisationTest {

    private static final String ALPHA = """
            [{"offerRef":"ALP-20261106-000001-0","cxr":"VY","totFare":433.51,"cur":"EUR",
              "legs":[{"fltNum":"VY1","org":"DUB","dst":"EZE","depTs":1793934300000,"arrTs":1793972400000},
                      {"fltNum":"VY8227","org":"EZE","dst":"FRA","depTs":1793989500000,"arrTs":1794009300000}]}]""";

    private static final String BETA = """
            <?xml version="1.0" encoding="UTF-8"?>
            <response provider="mock-beta"><itineraries><itinerary id="BET-20261106-000001-0">
            <pricing><totalAmount currency="EUR">428.41</totalAmount></pricing>
            <originDestinationOptions><option><segments>
            <segment><carrier>VY</carrier><flightNumber>1</flightNumber>
              <departure airport="DUB" dateTime="2026-11-06T03:05:00+00:00"/>
              <arrival airport="EZE" dateTime="2026-11-06T10:40:00-03:00"/></segment>
            <segment><carrier>VY</carrier><flightNumber>8227</flightNumber>
              <departure airport="EZE" dateTime="2026-11-06T15:25:00-03:00"/>
              <arrival airport="FRA" dateTime="2026-11-07T00:55:00+01:00"/></segment>
            </segments></option></originDestinationOptions></itinerary></itineraries></response>""";

    private static final String GAMMA = """
            REF,PRICE_EUR,SEGMENTS
            GAM-20261106-000001-0,459.15,VY0001~DUB~2026-11-06T03:05~Europe/Dublin~EZE~2026-11-06T10:40~America/Argentina/Buenos_Aires|VY8227~EZE~2026-11-06T15:25~America/Argentina/Buenos_Aires~FRA~2026-11-07T00:55~Europe/Berlin
            """;

    @Test
    @DisplayName("AC-2/AC-3: the same itinerary from the three providers has one key, equal segments and its own price")
    void sameItineraryFromThreeProviders() {
        List<FlightOffer> offers = Stream.of(
                        parse(new AlphaJsonParser(), ALPHA),
                        parse(new BetaXmlParser(), BETA),
                        parse(new GammaCsvParser(), GAMMA))
                .map(buffer -> buffer.offers().getFirst())
                .toList();

        List<ItineraryKey> keys =
                offers.stream().map(o -> o.itinerary().key()).distinct().toList();
        assertThat(keys).hasSize(1);
        assertThat(keys.getFirst().value())
                .isEqualTo("VY1:DUB-EZE@2026-11-06T03:05:00Z|VY8227:EZE-FRA@2026-11-06T18:25:00Z");
        assertThat(offers)
                .extracting(FlightOffer::itinerary)
                .containsOnly(offers.getFirst().itinerary());
        assertThat(offers)
                .extracting(o -> o.basePrice().amount().toPlainString())
                .containsExactly("433.51", "428.41", "459.15");
    }
}
