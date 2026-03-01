package com.stackwizard.booking_api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payments")
public class PaymentsProperties {
    private Monri monri = new Monri();

    public Monri getMonri() {
        return monri;
    }

    public void setMonri(Monri monri) {
        this.monri = monri;
    }

    public static class Monri {
        private boolean enabled = false;
        private String baseUrl = "https://ipgtest.monri.com";
        private String oauthPath = "/v2/oauth";
        private String paymentNewPath = "/v2/payment/new";
        private String clientId;
        private String clientSecret;
        private String authenticityToken;
        private String callbackAuthToken;
        private Integer connectTimeoutMs = 10000;
        private Integer readTimeoutMs = 15000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getOauthPath() {
            return oauthPath;
        }

        public void setOauthPath(String oauthPath) {
            this.oauthPath = oauthPath;
        }

        public String getPaymentNewPath() {
            return paymentNewPath;
        }

        public void setPaymentNewPath(String paymentNewPath) {
            this.paymentNewPath = paymentNewPath;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getAuthenticityToken() {
            return authenticityToken;
        }

        public void setAuthenticityToken(String authenticityToken) {
            this.authenticityToken = authenticityToken;
        }

        public String getCallbackAuthToken() {
            return callbackAuthToken;
        }

        public void setCallbackAuthToken(String callbackAuthToken) {
            this.callbackAuthToken = callbackAuthToken;
        }

        public Integer getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(Integer connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public Integer getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(Integer readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }
    }
}
