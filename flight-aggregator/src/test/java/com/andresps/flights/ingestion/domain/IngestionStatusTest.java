package com.andresps.flights.ingestion.domain;

import static com.andresps.flights.ingestion.domain.IngestionStatus.COMPLETED;
import static com.andresps.flights.ingestion.domain.IngestionStatus.FAILED;
import static com.andresps.flights.ingestion.domain.IngestionStatus.PARTIAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class IngestionStatusTest {

    @ParameterizedTest(name = "{0} succeeded, {1} failed -> {2}")
    @CsvSource({"7,0,COMPLETED", "6,1,PARTIAL", "1,6,PARTIAL", "0,7,FAILED"})
    @DisplayName("AC-19: a provider is COMPLETED if all partitions succeeded, PARTIAL if some did, FAILED if none did")
    void providerStatusFromPartitions(int succeeded, int failed, IngestionStatus expected) {
        assertThat(IngestionStatus.ofPartitions(succeeded, failed)).isEqualTo(expected);
    }

    @Test
    @DisplayName("AC-19: a provider with no partitions requested is a configuration error, not a status")
    void noPartitionsIsRejected() {
        assertThatThrownBy(() -> IngestionStatus.ofPartitions(0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("AC-19: the ingestion is COMPLETED only if every provider completed")
    void overallCompleted() {
        assertThat(IngestionStatus.overall(List.of(COMPLETED, COMPLETED, COMPLETED)))
                .isEqualTo(COMPLETED);
    }

    @Test
    @DisplayName("AC-19: the ingestion is FAILED only if every provider failed")
    void overallFailed() {
        assertThat(IngestionStatus.overall(List.of(FAILED, FAILED, FAILED))).isEqualTo(FAILED);
    }

    @Test
    @DisplayName("AC-19: any other mix is PARTIAL (one provider down, or every provider partial)")
    void overallPartial() {
        assertThat(IngestionStatus.overall(List.of(COMPLETED, FAILED, COMPLETED)))
                .isEqualTo(PARTIAL);
        assertThat(IngestionStatus.overall(List.of(PARTIAL, PARTIAL, PARTIAL))).isEqualTo(PARTIAL);
        assertThat(IngestionStatus.overall(List.of(FAILED, PARTIAL, FAILED))).isEqualTo(PARTIAL);
    }
}
