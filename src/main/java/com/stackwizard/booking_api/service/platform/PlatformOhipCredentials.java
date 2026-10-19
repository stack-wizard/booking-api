package com.stackwizard.booking_api.service.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Decrypted OHIP connection details as returned by Mikos Platform. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlatformOhipCredentials(
        String hostname,
        String tokenPath,
        String xAppKey,
        String clientId,
        String clientSecret,
        String enterpriseId,
        String scope,
        String username,
        String password) {
}
