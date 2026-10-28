package com.stackwizard.booking_api.service.loyalty.imports;

import java.util.List;

/** Columns without which an import cannot start at all (checked against the header, before any row). */
public interface RequiredFields {
    List<String> requiredFields();
}
