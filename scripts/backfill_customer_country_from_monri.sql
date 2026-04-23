-- Backfill reservation_request.customer_country from Monri payment_event payloads.
-- Convention: issuer is bank_code-country_code; ISO 3166-1 alpha-2 is the suffix after the last hyphen (e.g. off-us -> US).
--
-- Run the SELECT first to preview. Then run UPDATE in a transaction and COMMIT if counts look right.
-- Optional: restrict by tenant_id via payment_intent.tenant_id = :tenant_id

-- 1) Preview: what would be applied (latest Monri event per payment intent, prefer PAID intents)
WITH issuer_raw AS (
    SELECT
        pe.id AS payment_event_id,
        pi.id AS payment_intent_id,
        pi.tenant_id,
        pi.reservation_request_id,
        pi.status AS intent_status,
        pe.event_type,
        pe.created_at AS event_created_at,
        COALESCE(
            pe.payload #>> '{payload,issuer}',
            pe.payload ->> 'issuer'
        ) AS issuer
    FROM payment_event pe
    INNER JOIN payment_intent pi ON pi.id = pe.payment_intent_id
    WHERE pe.provider = 'MONRI'
      AND pe.payment_intent_id IS NOT NULL
),
parsed AS (
    SELECT
        *,
        upper(substring(issuer FROM '-([a-zA-Z]{2})$')) AS country_code
    FROM issuer_raw
    WHERE issuer IS NOT NULL
      AND issuer <> ''
),
ranked AS (
    SELECT
        *,
        ROW_NUMBER() OVER (
            PARTITION BY reservation_request_id
            ORDER BY
                CASE WHEN intent_status = 'PAID' THEN 0 ELSE 1 END,
                event_created_at DESC,
                payment_event_id DESC
        ) AS rn
    FROM parsed
    WHERE country_code IS NOT NULL
      AND length(trim(country_code)) = 2
)
SELECT
    r.id AS reservation_request_id,
    r.tenant_id,
    r.customer_country AS current_country,
    x.country_code AS new_country,
    x.issuer,
    x.intent_status,
    x.event_type,
    x.event_created_at
FROM ranked x
JOIN reservation_request r ON r.id = x.reservation_request_id
WHERE x.rn = 1
  -- Optional: only backfill empty country
  AND (r.customer_country IS NULL OR trim(r.customer_country) = '')
  -- Optional: only codes present in reference table (requires V74 country seed)
  AND EXISTS (SELECT 1 FROM country c WHERE c.code = x.country_code)
ORDER BY r.tenant_id, r.id;

-- 2) Apply updates (same logic)
-- BEGIN;
--
-- WITH issuer_raw AS (
--     SELECT
--         pe.id AS payment_event_id,
--         pi.id AS payment_intent_id,
--         pi.reservation_request_id,
--         pi.status AS intent_status,
--         pe.created_at AS event_created_at,
--         COALESCE(
--             pe.payload #>> '{payload,issuer}',
--             pe.payload ->> 'issuer'
--         ) AS issuer
--     FROM payment_event pe
--     INNER JOIN payment_intent pi ON pi.id = pe.payment_intent_id
--     WHERE pe.provider = 'MONRI'
--       AND pe.payment_intent_id IS NOT NULL
-- ),
-- parsed AS (
--     SELECT
--         reservation_request_id,
--         upper(substring(issuer FROM '-([a-zA-Z]{2})$')) AS country_code,
--         ROW_NUMBER() OVER (
--             PARTITION BY reservation_request_id
--             ORDER BY
--                 CASE WHEN intent_status = 'PAID' THEN 0 ELSE 1 END,
--                 event_created_at DESC,
--                 payment_event_id DESC
--         ) AS rn
--     FROM issuer_raw
--     WHERE issuer IS NOT NULL
--       AND issuer <> ''
--       AND upper(substring(issuer FROM '-([a-zA-Z]{2})$')) IS NOT NULL
--       AND length(trim(upper(substring(issuer FROM '-([a-zA-Z]{2})$')))) = 2
-- )
-- UPDATE reservation_request rr
-- SET customer_country = p.country_code
-- FROM parsed p
-- WHERE rr.id = p.reservation_request_id
--   AND p.rn = 1
--   AND (rr.customer_country IS NULL OR trim(rr.customer_country) = '')
--   AND EXISTS (SELECT 1 FROM country c WHERE c.code = p.country_code);
--
-- COMMIT;
