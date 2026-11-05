package com.andresps.flights.aggregation.domain.validation;

import com.andresps.flights.aggregation.domain.model.Itinerary;
import com.andresps.flights.aggregation.domain.model.Segment;
import java.util.List;
import java.util.Optional;

/** Each segment must depart from the airport where the previous one arrived. */
public final class ChainedSegmentsRule implements ItineraryRule {

    @Override
    public Optional<DiscardReason> check(Itinerary itinerary) {
        List<Segment> segments = itinerary.segments();
        for (int i = 1; i < segments.size(); i++) {
            if (!segments.get(i).origin().equals(segments.get(i - 1).destination())) {
                return Optional.of(DiscardReason.SEGMENTS_NOT_CHAINED);
            }
        }
        return Optional.empty();
    }
}
