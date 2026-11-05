package com.andresps.gdsmock.inventory.domain.generator;

import com.andresps.gdsmock.inventory.domain.model.Horizon;
import com.andresps.gdsmock.shared.domain.InvalidRequestException;
import java.time.LocalDate;

/** A requested day that is not one of the horizon days. */
public class DateOutsideHorizonException extends InvalidRequestException {

    public DateOutsideHorizonException(LocalDate day, Horizon horizon) {
        super("Date %s is outside the horizon %s".formatted(day, horizon));
    }
}
