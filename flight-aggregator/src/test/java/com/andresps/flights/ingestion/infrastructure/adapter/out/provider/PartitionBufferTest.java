package com.andresps.flights.ingestion.infrastructure.adapter.out.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.andresps.flights.ingestion.domain.MalformedReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-0015: one attempt's buffer, bounded by max-records-per-partition. */
class PartitionBufferTest {

    @Test
    @DisplayName(
            "ADR-0015: records (offers and malformed alike) beyond the limit fail the attempt as a malformed document")
    void enforcesRecordLimit() {
        PartitionBuffer buffer = new PartitionBuffer(2);
        buffer.addMalformed(MalformedReason.BAD_AIRPORT_CODE);
        buffer.addMalformed(MalformedReason.INVALID_RECORD);

        assertThatThrownBy(() -> buffer.addMalformed(MalformedReason.INVALID_RECORD))
                .isInstanceOf(MalformedDocumentException.class)
                .hasMessageContaining("more than 2 records");
        assertThat(buffer.recordsReceived()).isEqualTo(2);
    }

    @Test
    @DisplayName("ADR-0015: the limit must be positive")
    void rejectsNonPositiveLimit() {
        assertThatThrownBy(() -> new PartitionBuffer(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
