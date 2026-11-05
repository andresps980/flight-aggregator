package com.andresps.gdsmock.inventory.domain.generator;

import com.andresps.gdsmock.inventory.domain.model.Airport;
import java.time.ZoneId;
import java.util.List;

/** Airports (with their IANA time zones) and airlines the generator draws from. */
final class Catalog {

    static final int MAX_FLIGHT_NUMBER = 9_999;

    static final List<String> AIRLINES = List.of(
            "IB", "VY", "UX", "BA", "AF", "KL", "LH", "AZ", "TP", "EI", "LX", "OS", "SK", "TK", "AA", "DL", "UA", "AM",
            "LA", "AV", "EK", "QR", "SQ", "NH");

    static final List<Airport> AIRPORTS = List.of(
            airport("MAD", "Europe/Madrid"),
            airport("BCN", "Europe/Madrid"),
            airport("LHR", "Europe/London"),
            airport("LGW", "Europe/London"),
            airport("CDG", "Europe/Paris"),
            airport("ORY", "Europe/Paris"),
            airport("FRA", "Europe/Berlin"),
            airport("MUC", "Europe/Berlin"),
            airport("AMS", "Europe/Amsterdam"),
            airport("FCO", "Europe/Rome"),
            airport("MXP", "Europe/Rome"),
            airport("LIS", "Europe/Lisbon"),
            airport("OPO", "Europe/Lisbon"),
            airport("DUB", "Europe/Dublin"),
            airport("ZRH", "Europe/Zurich"),
            airport("VIE", "Europe/Vienna"),
            airport("CPH", "Europe/Copenhagen"),
            airport("IST", "Europe/Istanbul"),
            airport("JFK", "America/New_York"),
            airport("BOS", "America/New_York"),
            airport("MIA", "America/New_York"),
            airport("ORD", "America/Chicago"),
            airport("LAX", "America/Los_Angeles"),
            airport("MEX", "America/Mexico_City"),
            airport("BOG", "America/Bogota"),
            airport("GRU", "America/Sao_Paulo"),
            airport("EZE", "America/Argentina/Buenos_Aires"),
            airport("CMN", "Africa/Casablanca"),
            airport("JNB", "Africa/Johannesburg"),
            airport("DXB", "Asia/Dubai"),
            airport("DOH", "Asia/Qatar"),
            airport("SIN", "Asia/Singapore"),
            airport("HND", "Asia/Tokyo"),
            airport("SYD", "Australia/Sydney"));

    private Catalog() {}

    private static Airport airport(String code, String zone) {
        return new Airport(code, ZoneId.of(zone));
    }
}
