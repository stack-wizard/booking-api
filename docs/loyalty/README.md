# Loyalty (faza 1)

Booking-api je izvor istine za loyalty: članovi, knjiga noćenja, tier, pravila, audit. Sve je na ORG tenantu
(jedan profil i jedan zbroj kroz sve hotele). Program je konfiguracija – Arena Rewards je jedan skup podataka.

## Model

- `loyalty_program` → `loyalty_rule_set` (verzije s `effective_from/to`, `params` jsonb, `tier_policy`)
  → `loyalty_tier`, `loyalty_qualification_step`; `loyalty_channel_map` (source/market/channel → kvalificiran).
- `loyalty_member` (veza na `crm_contact`), `loyalty_member_identifier` (kartica, e-mailovi, telefon),
  `loyalty_consent`, `loyalty_tier_history`.
- `loyalty_ledger` – samo dodavanje (trigger); ispravak = protuzapis; jedno STAY noćenje po članu po datumu.
- `loyalty_reservation` – snimka PMS rezervacije (CSV danas, OPERA konektor kasnije), `loyalty_reservation_evaluation`
  – svaka odluka enginea (s verzijom pravila i koracima) + red čekanja (`PENDING`).
- `loyalty_import_batch` / `loyalty_import_row` – CSV importi.
- `integration_event` / `event_subscription` / `event_delivery` – outbox (vidi [events.md](events.md)).

## Parametri pravila (`loyalty_rule_set.params`)

| Ključ | Default | Značenje |
|---|---|---|
| `validity_months` | 24 | valjanost statusa od zadnjeg check-outa (ROLLING_VALIDITY) |
| `wallet_bonus_nights` | 2 | jednokratni bonus kad je kartica dodana u wallet |
| `referral_bonus_nights` | 2 | (faza 2) |
| `bonus_counts_for_tier` | true | broje li se bonus noćenja u tier |
| `opening_balance_counts_for_tier` | true | broji li se početno stanje iz migracije u tier |
| `claim_window_days` | 60 | rok za prijavu nedostajućih noćenja (u `stay.rejected`) |
| `near_next_threshold` | 3 | `tier.near_next` kad nedostaje ≤ N |
| `unknown_code_policy` | QUALIFYING | nemapirani source/market/channel kod: `QUALIFYING` / `NON_QUALIFYING` |
| `downgrade_step` | 1 | ANNUAL_CYCLE: najviše toliko razina pada godišnje |

Koraci kvalifikacije (redoslijed, uključenost, `reject_code`, `params`): `MEMBER_MATCH`, `CHANNEL`, `HOLDER`
(`require_last_name_match`), `ACTIVATION`, `WALLET`, `STAY_COMPLETED`, `EXCLUSIVITY_FLAG`, `UNIQUE_PER_DATE`.

## Importi (CSV UTF-8, `,` `;` ili tab)

1. `POST /api/loyalty/imports` `{programId, importType: MEMBERS|RESERVATIONS, propertyTenantId (za rezervacije), fileName}`
   → `uploadUrl` (presigned S3 PUT, 30 min). Klijent šalje datoteku izravno na S3.
2. `POST /api/loyalty/imports/{id}/start` `{columnMapping?, options?}` → worker (bilo koja instanca) učita i validira.
3. `GET /api/loyalty/imports/{id}` (napredak) · `GET …/errors.csv` (odbijeni redovi).
4. `POST …/apply` → primjena red po red (svaki u svojoj transakciji), checkpoint `last_applied_row`.

Kolone se prepoznaju po imenu i aliasima (`GET /api/loyalty/imports/fields?type=MEMBERS`); `columnMapping`
`{ "member_number": "Digital Card Number" }` ima prednost. Opcije: `delimiter`, `dateFormat` (npr. `dd.MM.yyyy`),
`asOf` (datum početnog stanja), `suppressEvents` (default true – migracija ne šalje welcome mailove),
`autoApply`, za rezervacije `statusMap` `{ "DUE OUT": "IN_HOUSE" }` i `externalSystem`.

**Članovi**: obavezno `member_number, first_name, last_name, email`; dodatno tier, valjanost, ukupno noćenja,
noćenja ove godine, zadnji check-out, aktivacija, wallet, kontakt, privole, interesi, preporučitelj.
Ponovni import po broju kartice ažurira; početno stanje zamjenjuje protuzapisom.

**Rezervacije (OPERA)**: obavezno `confirmation_no, status, arrival, departure`; dodatno `sub_id`
(sharer), source/market/channel, holder, `membership_no`, Radisson flag, iznos. Upsert po hotel + broj potvrde;
promjena relevantnih polja → ponovna evaluacija. Rezervacija koje nema u novom exportu se ne dira.

## Uloge (Platform `mikos_roles`)

`BOOKING_LOYALTY_OWNER` – sve osim pretplata; `BOOKING_LOYALTY_SUPPORT` i `BOOKING_STAFF` – pregled; `BOOKING_ADMIN` – sve.
Uloge `BOOKING_LOYALTY_OWNER` i `BOOKING_LOYALTY_SUPPORT` treba dodati u mikos-platform (Flyway migracija kao
`V27__booking_roles.sql`). One su **dodatne**: korisnik uz njih mora imati i osnovnu booking ulogu
(`BOOKING_STAFF`/`BOOKING_ADMIN`/`BOOKING_CASHIER`), inače booking-api vraća 403 "No booking role in token".

## Jobovi (sve instance, sigurno za više ECS taskova)

| Job | Property | Default |
|---|---|---|
| import worker | `loyalty.import.enabled`, `loyalty.import.poll-ms` | true, 5 s |
| evaluacija rezervacija | `loyalty.evaluation.enabled`, `loyalty.evaluation.poll-ms` | true, 5 s |
| dnevni istek / brojila / najave | `loyalty.expiry.enabled`, `loyalty.expiry.cron` | true, 02:15 Europe/Zagreb |
| dostava događaja | `integration.events.dispatch-enabled`, `integration.events.dispatch-ms` | true, 5 s |

## Test

`LoyaltyFlowScratchDbIT` – cijeli tok na scratch kopiji baze:
`LOYALTY_SCRATCH_DB_URL=jdbc:postgresql://localhost:5432/<scratch> ./mvnw test -Dtest=LoyaltyFlowScratchDbIT`.
