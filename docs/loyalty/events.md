# Integracijski događaji (outbox) – katalog v1

Booking-api upisuje događaj u `integration_event` **u istoj transakciji** kao poslovnu promjenu i dostavlja ga
pretplatnicima (webhook) **najmanje jednom**. Događaji su na razini lanca (ORG tenant); hotel je u payloadu.

## Pretplata

`POST /api/integration/subscriptions` `{ "name": "mail-service", "endpointUrl": "https://…", "eventTypes": ["loyalty.*", "reservation.*"] }`
→ odgovor sadrži `secret` (rotacija: `POST /api/integration/subscriptions/{id}/rotate-secret`).
Filter: točan tip, `*` ili prefiks `loyalty.*`.

## Dostava

`POST <endpointUrl>`, `Content-Type: application/json`, headeri:

| Header | Značenje |
|---|---|
| `X-Event-Id` | UUID događaja – **ključ za deduplikaciju** (ista poruka može stići više puta) |
| `X-Event-Type` | npr. `loyalty.tier.upgraded` |
| `X-Signature` | `sha256=<hex HMAC-SHA256(secret, raw body)>` – provjeriti prije obrade |

Odgovor 2xx = isporučeno. Inače ponovni pokušaj nakon 1 min, 5 min, 30 min, 2 h, 12 h; zatim `DEAD`
(vidljivo i ponovljivo: `GET /api/integration/events`, `POST /api/integration/events/{id}/replay`).
Redoslijed nije zajamčen; za stanje koristiti `occurredAt` i snapshot u payloadu.

## Omotnica

```json
{
  "eventId": "4b1f…", "type": "loyalty.stay.posted", "version": 1,
  "occurredAt": "2026-10-28T10:15:00+01:00", "tenantId": 2,
  "aggregateType": "loyalty_member", "aggregateId": "123",
  "data": { "...": "specifično za tip", "member": { "…snapshot…" } }
}
```

`data.member` (svi `loyalty.*` događaji): `id, number, status, firstName, lastName, email, phone, language,
tier, tierName, tierValidUntil, nightsTotal, nightsCurrentYear, walletAdded, consents {email, sms, push}`.
Privole šaljemo uvijek; **filtriranje po privolama radi konzument**.

## Tipovi

| Tip | Okidač | `data` (osim `member`) |
|---|---|---|
| `loyalty.member.registered` | novi član (admin, web, booking; **ne** migracija) | `source` |
| `loyalty.member.updated` | promjena profila / privola | – |
| `loyalty.member.deactivated` | deaktivacija | `reason` |
| `loyalty.member.wallet_added` | kartica dodana u wallet (jednom) | `bonusNights` |
| `loyalty.stay.posted` | noćenja pripisana | `reservationId, propertyTenantId, confirmationNo, arrival, departure, nights` |
| `loyalty.stay.rejected` | odjavljeni boravak nije kvalificiran (jednom po razlogu) | isto + `rejectCode, claimWindowDays, claimDeadline` |
| `loyalty.stay.reversed` | storno / no-show / izmjena nakon pripisa | isto + `nights, reason` |
| `loyalty.tier.upgraded` | viši tier | `fromTier, toTier, reason` |
| `loyalty.tier.downgraded` | istek statusa ili korekcija | `fromTier, toTier, reason, expiredOn?` |
| `loyalty.tier.expiring` | 90 i 30 dana prije isteka | `daysLeft, expiresOn` |
| `loyalty.tier.near_next` | do idućeg tiera ≤ N noćenja (jednom po tieru) | `nextTier, nightsMissing` |
| `loyalty.adjustment.created` | ručna korekcija | `adjustmentType, qty, reason, exception` |
| `reservation.confirmed` | booking-api rezervacija finalizirana | `reservationRequestId, propertyTenantId, hotelCode, hotelName, status, channel, confirmationCode, confirmedAt, guest {name, email, phone, country}` |
| `reservation.amended` | izmjena | isto |
| `reservation.cancelled` | otkaz | isto + `cancellationRequestId` |

Kodovi odbijanja (`rejectCode`) dolaze iz konfiguracije pravila; Arena: `NO_MATCH, OTA_CHANNEL, NOT_HOLDER,
PRE_ACTIVATION, NO_WALLET, NOT_STAYED, RADISSON, DUPLICATE`.

## E-mail iz booking-apija

`tenant_config.email_delivery = EXTERNAL_EVENTS` (na lancu ili hotelu) gasi vlastite guest e-mailove
booking-apija (potvrda, izmjena, otkaz); tada ih šalje vanjski servis iz gornjih događaja.

Planirano (nije u v1): `loyalty.member.inactive_activation`, `loyalty.member.lapsed`, `loyalty.member.birthday`,
`loyalty.reward.*`, `loyalty.referral.completed`.
