CREATE TABLE reservation_request_amendment (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                BIGINT NOT NULL,
    reservation_request_id   BIGINT NOT NULL REFERENCES reservation_request (id),
    status                   VARCHAR(32) NOT NULL,
    request_payload          JSONB NOT NULL,
    failure_reason           TEXT,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_rr_amendment_request ON reservation_request_amendment (reservation_request_id);
CREATE INDEX idx_rr_amendment_tenant ON reservation_request_amendment (tenant_id);
