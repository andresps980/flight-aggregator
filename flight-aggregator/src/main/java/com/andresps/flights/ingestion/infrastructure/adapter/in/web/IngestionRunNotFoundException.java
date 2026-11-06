package com.andresps.flights.ingestion.infrastructure.adapter.in.web;

import com.andresps.flights.shared.domain.ResourceNotFoundException;

/** No ingestion run matches the request (spec 004, AC-23). */
class IngestionRunNotFoundException extends ResourceNotFoundException {

    IngestionRunNotFoundException(String detail) {
        super("Ingestion not found", detail);
    }
}
