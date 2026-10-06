# Implementacijska specifikacija — Faza 5 (quote to cash) i Faza 6 (portal, alerti, analitika)

Migracije **V102–V105**. Ovise o Fazama 3 i 4.

## Faza 5

### V102 — ponuda

- `product.sales_group` (MEETING / F_AND_B / AV / EXTRAS) — sekcija na ponudi; null = automatski (catering tip funkcije → F_AND_B, inače EXTRAS, najam → MEETING).
- `sales_quote`: DRAFT → (PENDING_APPROVAL → APPROVED) → SENT → ACCEPTED / REJECTED / EXPIRED; SUPERSEDED kad se napravi nova ponuda za isti event. Broj `Q{godina}-{0000}`.
- `sales_quote_line`: grupa, list rate (`base_rate`) i ponuđena cijena (`offered_rate`), PDV; DISCOUNT linije su uvijek negativne.
- `sales_quote_version`: snapshot (jsonb) svakog slanja — klijent i PDF vide točno poslanu verziju.
- `sales_quote_approval`: zahtjev i odluka za popust iznad praga.

Pravila (`SalesQuoteService`):

- nova ponuda se gradi iz najma i stavki eventa; ponuđena cijena = bruto / qty
- uređivati se može DRAFT i APPROVED; izmjena APPROVED vraća u DRAFT
- popust = 1 − ponuđeno / list; iznad `crm.quotes.approval-threshold-percent` (10) DRAFT se ne može poslati bez odobrenja; odobrava korisnik s `QUOTE_APPROVE` (SALES_MANAGER, ADMIN)
- slanje piše verziju, postavlja `valid_until` (`crm.quotes.validity-days`, 14) i otvara portal link
- `@Scheduled` istek SENT ponuda nakon `valid_until` → EXPIRED + alert
- **DEFINITE traži prihvaćenu ponudu** (`crm.events.definite-requires-accepted-quote`, zadano `true`)

### V103 — ugovor i plan plaćanja

- `sales_contract` (DRAFT / SENT / SIGNED / CANCELLED), najviše jedan otvoren po eventu, nastaje samo iz ACCEPTED ponude; broj `C{godina}-{0000}`.
- `sales_contract_document`: potpisani ugovor, aneksi (S3 preko `MediaStorageService`, prefiks `sales-contracts`).
- `sales_payment_milestone`: DEPOSIT / INTERIM / FINAL. Zadano: depozit `crm.contracts.deposit-percent` (30 %) s rokom za 7 dana (ili dan prije eventa) i FINAL na `date_to`. Točno jedan FINAL, zbroj = iznos ugovora, FINAL uzima ostatak; plan se ne mijenja nakon prvog fakturiranja.
- Fakturiranje rate (ugovor SENT ili SIGNED):
  - DEPOSIT / INTERIM → DEPOSIT račun s proizvodom tipa `DEPOSIT` tenanta (**mora postojati**; dev seed ga dodaje)
  - FINAL → INVOICE iz linija ponude, popusti raspoređeni kao jednoliki `discountPercent`
  - račun ima referencu `sales_payment_milestone`

### V104 — troškovi

- `crm_cost_item` na eventu ili opportunityju (VENUE, F_AND_B, AV, STAFF, EXTERNAL, TRANSPORT, OTHER).
- Profitabilnost: prihod = prihvaćena ponuda, inače linije eventa; marža = prihod − trošak proizvoda − ostali troškovi. Za opportunity zbroj po eventima.

### API

- `/api/events/{id}/quotes`, `/api/quotes/{id}` (+ `lines`, `request-approval`, `approve`, `decline-approval`, `send`, `revise`, `accept`, `reject`, `pdf`), `/api/quotes/pending-approval`
- `/api/events/{id}/contracts`, `/api/contracts/{id}` (+ `milestones`, `send`, `sign`, `cancel`, `pdf`, `documents`), `/api/contracts/milestones/{id}/invoice`
- `/api/events/{id}/costs`, `/api/crm/opportunities/{id}/costs`, `/api/costs/{id}`, `/api/events/{id}/profitability`, `/api/crm/opportunities/{id}/profitability`
- `/api/events/{id}/portal-link` (GET / POST / DELETE)

### Admin

Event detalj dobiva tabove *Quote*, *Contract & billing*, *Costs & margin*. Na `/packages` odabir *Quote group*.

## Faza 6

### V105 — portal i alerti

- `portal_access_token`: 32 slučajna bajta (base64url) po eventu, istječe `max(date_to + 30 d, sada + 30 d)`, opoziv po eventu.
- `crm_alert`: DECISION_DATE_DUE, GUARANTEE_DUE, BEO_NOT_ISSUED, QUOTE_EXPIRED, MILESTONE_DUE, QUOTE_DECIDED; deduplikacija `(tenant_id, dedupe_key)`.

### Portal

- **Link s tokenom** (bez prijave): `/api/public/portal/{token}` (+ `quotes/{id}/accept|reject|pdf`, `contracts/{id}/pdf`, `documents/{id}`, `invoices/{id}/pdf`). Jedini novi permitAll put; token je vjerodajnica, nije API token za integracije.
- **Platform korisnik**: `/api/portal/events…` — kontakt s `crm_contact.platform_user_id` vidi evente svojih kontakata i accounta. Nema lokalnog logina.
- Klijent vidi samo poslane verzije ponuda, SENT/SIGNED ugovor i izdane račune. Prihvat traži ime.
- Admin ruta `/portal/:token` izvan prijave; `crm.portal.base-url` određuje link (lokalno `http://127.0.0.1:5173/booking-admin/portal/`).

### Alerti

`CrmAlertScanService` (`@Scheduled`, `crm.alerts.*`): rok opcije, rok garancije, DEFINITE bez BEO-a pred početak, rata koja dospijeva; istek ponude i odluka klijenta dolaze iz `SalesQuoteService`. Alert ide vlasniku eventa; `GET /api/crm/alerts`, `POST /api/crm/alerts/{id}/ack`. Admin: *Sales Inbox* (`/crm/inbox`) s alertima i popustima koji čekaju odobrenje.

### Analitika

- `conversion` dobiva `byPipeline`
- `GET /api/crm/reports/revenue?from&to` — DEFINITE / ACTUAL eventi, prihod, trošak i marža po accountu i segmentu
- `GET /api/crm/reports/space-utilisation?from&to` — sati iz aktivnih `allocation` naspram radnog vremena `booking_calendar` (bez kalendara 24 h)

**Ograničenje:** iskorištenost broji alokacije na samom resursu. Kompozicija (npr. Main Hall = Hall A + Hall B) pokazuje 0 ako su zauzeti samo članovi.

## Testovi

`SalesQuoteServiceTest`, `SalesContractServiceTest`, `CrmCostServiceTest`, `CrmAlertScanServiceTest`, `PortalServiceTest`, `PackageCatalogServiceTest`, `EventServiceTest.definiteRequiresAcceptedQuote`. E2E `crm-flow.spec.ts`: ponuda s popustom → odobrenje → slanje → prihvat na portalu → DEFINITE → ugovor, faktura depozita, potpis → trošak → izvještaji → inbox.
