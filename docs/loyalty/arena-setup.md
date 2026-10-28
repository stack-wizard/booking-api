# Arena Rewards – konfiguracija (test)

Pozivi admin API-ja (ORG tenant Arene, korisnik s `BOOKING_ADMIN` ili `BOOKING_LOYALTY_OWNER`).

1. Program
```json
POST /api/loyalty/programs
{ "code": "ARENA_REWARDS", "name": "Arena Rewards", "accrualUnit": "NIGHT", "anonymousPricing": "APPLY_ENTRY_TIER_ON_JOIN" }
```
Broj kartice: nakon importa članova `member_number_next` se sam postavi iznad najvećeg uvezenog broja.

2. Pravila v1 (vrijedi od 2019-01-01, stari boravci)
```json
POST /api/loyalty/programs/{programId}/rule-sets
{ "effectiveFrom": "2019-01-01", "tierPolicy": "ROLLING_VALIDITY", "note": "Arena Rewards – pravila 2026",
  "params": { "validity_months": 24, "wallet_bonus_nights": 2, "referral_bonus_nights": 2, "claim_window_days": 60,
              "near_next_threshold": 3, "bonus_counts_for_tier": true, "opening_balance_counts_for_tier": true,
              "unknown_code_policy": "QUALIFYING" } }

PUT /api/loyalty/rule-sets/{ruleSetId}
{ "tiers": [
    { "code": "LIGHT", "name": "Light", "rank": 0, "threshold": 0 },
    { "code": "SILVER", "name": "Silver", "rank": 1, "threshold": 10 },
    { "code": "GOLD", "name": "Gold", "rank": 2, "threshold": 31 },
    { "code": "PLATINUM", "name": "Platinum", "rank": 3, "threshold": 61 } ],
  "steps": [
    { "stepOrder": 1, "ruleCode": "MEMBER_MATCH", "rejectCode": "NO_MATCH" },
    { "stepOrder": 2, "ruleCode": "CHANNEL", "rejectCode": "OTA_CHANNEL" },
    { "stepOrder": 3, "ruleCode": "HOLDER", "rejectCode": "NOT_HOLDER" },
    { "stepOrder": 4, "ruleCode": "ACTIVATION", "rejectCode": "PRE_ACTIVATION" },
    { "stepOrder": 5, "ruleCode": "WALLET", "rejectCode": "NO_WALLET" },
    { "stepOrder": 6, "ruleCode": "STAY_COMPLETED", "rejectCode": "NOT_STAYED" },
    { "stepOrder": 7, "ruleCode": "EXCLUSIVITY_FLAG", "rejectCode": "RADISSON" },
    { "stepOrder": 8, "ruleCode": "UNIQUE_PER_DATE", "rejectCode": "DUPLICATE" } ] }

POST /api/loyalty/rule-sets/{ruleSetId}/activate
```
Light s pragom 0 = ulazna razina (i cijena za neprijavljene).

3. Kanali – kad Arena pošalje OPERA source/market kodove:
```json
PUT /api/loyalty/programs/{programId}/channel-map
[ { "codeType": "SOURCE", "code": "WEB", "qualifying": true },
  { "codeType": "SOURCE", "code": "CC", "qualifying": true },
  { "codeType": "SOURCE", "code": "FD", "qualifying": true },
  { "codeType": "SOURCE", "code": "BOOKING", "qualifying": false } ]
```
Dok mapa nije potpuna, `unknown_code_policy` odlučuje (za test QUALIFYING, za produkciju NON_QUALIFYING).

4. Pretplata za e-mail servis drugog developera: vidi [events.md](events.md); `tenant_config.email_delivery = 'EXTERNAL_EVENTS'`.

5. Import članova (export Arene), zatim rezervacija po hotelu – vidi [README.md](README.md#importi-csv-utf-8--ili-tab).

Prijedlog FR-02.5 (godišnji ciklus 10/25/40): nova verzija pravila (`copyFromId`, `tierPolicy: ANNUAL_CYCLE`,
`effectiveFrom: 2027-01-01`, novi pragovi) – bez razvoja.
