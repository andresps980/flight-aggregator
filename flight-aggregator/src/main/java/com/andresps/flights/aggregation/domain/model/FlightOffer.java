package com.andresps.flights.aggregation.domain.model;

/** An itinerary offered by one provider at a base price (before OTA markup). The base price must be positive. */
public record FlightOffer(Itinerary itinerary, ProviderId provider, Money basePrice) {

    public FlightOffer {
        InvalidFlightDataException.requireField(itinerary, "Offer itinerary");
        InvalidFlightDataException.requireField(provider, "Offer provider");
        InvalidFlightDataException.requireField(basePrice, "Offer base price");
        if (!basePrice.isPositive()) {
            throw new InvalidFlightDataException("Offer base price must be greater than zero, got " + basePrice);
        }
    }
}
