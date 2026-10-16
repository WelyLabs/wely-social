package com.calendar.social.domain.models;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationReportTest {

    @Test
    void plus_shouldAddEachCount() {
        assertThat(new ReconciliationReport(3, 1, 1).plus(new ReconciliationReport(2, 2, 0)))
                .isEqualTo(new ReconciliationReport(5, 3, 1));
    }

    @Test
    void plus_shouldChangeNothing_whenAddingNone() {
        ReconciliationReport report = new ReconciliationReport(4, 2, 1);

        assertThat(report.plus(ReconciliationReport.NONE)).isEqualTo(report);
        assertThat(ReconciliationReport.NONE.plus(report)).isEqualTo(report);
    }
}
