# Specifikacija — Katalozi i sezone (web prodaja)

**Status: faze 1–3 implementirane** (grana `feature/catalogs-seasons`). Faze 4–5 (CMS) i brisanje `product_package_listing` nisu.

## Stanje implementacije (odstupanja i dopune nacrta niže)

**Migracije**: V130 `season_calendar`, `season`, `season_operating` · V131 `sales_catalog`, `catalog_item`, `catalog_item_season`, `catalog_map` · V132 `price_profile_date.season_id`, `period_kind`, `price_list.days_of_week` · V133 podatkovna migracija · V134 `booking_promote_catalog` seli i nove lančane tablice (`season_calendar`, `season`, `season_operating`, `sales_catalog`, `catalog_item`; `TenantLevel.ORG_TABLES`).

Shema (dopune):
- `season` i `season_operating` imaju `tenant_id` (konvencija iz AGENTS.md); `season` ima i `note text` (bilješka za admina iz migracije).
- `sales_catalog.currency text not null default 'EUR'` — valuta po kojoj se biraju cijene (`GET /api/catalogs/{code}` je vraća). V133 je postavlja na najčešću valutu cjenovnih profila tenanta.
- Jedinstvenost `sales_catalog (tenant_id, property_tenant_id, code)` i `season_operating (season_id, location_node_id)` je `nulls not distinct`.
- `catalog_item` ima dodatne checkove `valid_to >= valid_from`, `max_pax >= min_pax`, `min/max_pax > 0`.

**Grupe stavki (`item_group`)** — zadane vrijednosti (V133, bulk, kreiranje stavke bez `group`): dvorane / meeting resursi (`MEETING_ROOM`, `MEETING_OFFICE` ili resurs čiji je proizvod `MEETING`) → `HALL`; paketi → `PACKAGE`; proizvodi po `salesGroup`: `F_AND_B` → `CATERING`, `AV` → `AV`, `EXTRAS` → `EXTRAS`, `ROOMS` → `ROOMS` (`MEETING` → `HALL`); beach resursi s mapa → `SPOT`; ostali resursi `ROOM`/`APARTMENT` → `ROOMS`, inače `SPOT`. Grupa je slobodan tekst koji staff može mijenjati. `/api/catalogs/{code}/items` vraća `group` i prima `?group=` kao listu odvojenu zarezom (bez obzira na velika/mala slova).

**Resolver** (`PriceListEntryResolver`): pravila prednosti točno kao niže; izjednačenje: kraće razdoblje, noviji `created_at`, zatim manji id. Kandidati s `days_of_week` koji ne sadrži dan u tjednu otpadaju. `resolve(...)` vraća cijenu i `AppliedRule {priceId, priceProfileId, priceProfile, level (PROPERTY|CHAIN), requestType, periodId, periodFrom, periodTo, periodDescription, periodKind, seasonId, season, daysOfWeek, startTime, endTime}` (null polja se ne šalju). Web dobiva skraćeno `rule: {season, periodKind, daysOfWeek}`. Postojeći pozivatelji (`findEffective…`) daju iste rezultate: na kopiji dev baze 1116 usporedbi (svaka cijena × početak/sredina/kraj razdoblja × svaki kanal) je identično starom pravilu. Ako u nekoj bazi postoje **preklapajuća razdoblja iste razine i kanala s cijenom za isti proizvod/UOM/slot**, prije je pobjeđivao manji id, a sad kraće (pa novije) razdoblje — dev baza takvih nema.

**Radno vrijeme**: `ServiceCalendarService.windowFor` traži sezonu datuma u sezonskim kalendarima hotela (`booking_calendar.tenant_id`), pa lanca; u prvoj sezoni s redovima `season_operating` vrijedi red za lokaciju `booking_calendar`a, zatim red bez lokacije; inače `booking_calendar` kao prije. `ServiceCalendarService.rulesFor` na isti način daje `grid_minutes` i min/max trajanje (vrijednost sezone ako je zadana, inače `booking_calendar`); koriste ih `AvailabilityService` (grid) i `BookingTranslationService` (grid, min/max).

**Copy year**: sezone koje **počinju** u `fromYear`. `shiftToSameWeekday=true` pomiče sve datume za isti broj cijelih tjedana (1 godina = 52 tjedna = 364 dana), pa svaki datum zadržava dan u tjednu, a sezone duljinu i razmake (npr. 15.6.2027 uto → 13.6.2028 uto). `false` = isti datum (`plusYears`). Kod: `2027` u kodu/nazivu se zamjenjuje s `toYear` (`HIGH_27` → `HIGH_28`), inače se dodaje `_2028`. Kopira `season_operating`, razdoblja vezana na sezonu (isti profil, vrsta, opis s novom godinom) i, uz `copyPrices`, cijene (s `pricePercent` od −90 do 500 i `roundTo`, s `daysOfWeek`). Preskače (u `skipped`) sezone čiji kod već postoji ili se preklapaju. Odgovor: `{dryRun, seasons, operating, pricePeriods, prices, planned:[{code, name, dateFrom, dateTo, fromSeasonId}], skipped:[…]}`.

**Migracija (V133)** — kako je napisano niže, uz:
- sezone: preklapajući rasponi root mapa istog hotela spajaju se u jednu sezonu (inače bi exclusion constraint pao); kod `SEASON_YYYYMMDD` (početak), naziv = nazivi mapa; mapa bez kraja → do kraja godine (`greatest(today, valid_from)`), mapa bez početka → od 1.1. tekuće godine; oboje dobiva `note`.
- `catalog_map` BEACH kataloga: **sve** mape tog hotela (root i podmape, sezona null), ne samo one s resursima — kao i danas availability bira mapu po valjanosti, pa i prazna mapa ostaje prikazana.
- `sales_catalog.tenant_id` = lanac hotela (`platform_tenant_mapping.parent_tenant_id`), `property_tenant_id` = hotel.
- MICE se stvara samo za tenante s `product_package_listing` (točno po nacrtu). Na dev bazi to je samo tenant 1; lanac 2 / hotel 3 (dvorane Galija, Sala…) **nema** MICE katalog — admin ga stvara kroz `POST /api/sales-catalogs` + `items/bulk`.
- `product_package_listing` se više ne čita ni ne piše (ostaje do V13x); `/api/products/{id}/package-listing` i `/api/catalog/packages` rade nad `catalog_item` (kind `PACKAGE`) lančanog kataloga `MICE`, koji se stvara (INQUIRY, EXTERNAL, EUR) pri prvom spremanju listinga ako ne postoji. `id` listinga je sada id stavke.

**Web API (m2m)** — `SecurityConfig`: `/api/catalogs` i `/api/catalogs/**` (i s prefiksom `/booking-api`) `hasRole("API")` (anonimno 401), a servis još traži `token_use=m2m` (`ApiClientAccess`, korisnički JWT 403).
- Odabir kataloga po kodu: aktivni katalog odabranog hotela (`X-Property-Id`), zatim lančani; bez hotela lančani ili jedini hotelski s tim kodom (više hotela → 400 "send X-Property-Id"); inače 404. `GET /api/catalogs` vraća aktivne kataloge lanca vidljive hotelu.
- `GET /api/catalogs/{code}` → `{code, name, saleMode, currency}`; `/seasons?from=&to=` → `[{code, name, dateFrom, dateTo, status}]` (katalog bez kalendara → `[]`, svi datumi prodajni).
- `/items?date=&dateTo=&pax=&group=`: s datumom samo stavke ponuđene **svaki** dan raspona (max 31 dan); ako ijedan dan nije prodajan → `[]`. Oblik: `{itemId, kind, group, resourceId, productId, name, description, images:[{url, isDefault}], areaSqm, naturalLight, setups:[{setupStyle, capacity}], prices:[{uom, amountGross, currency, rule}], available, displayOrder, minPax, maxPax, duration, defaultStartTime, setupStyle, pricePerPerson, components}`. Za resurs `productId` je proizvod resursa (cijena). `prices` = svi UOM-ovi proizvoda koji imaju cijenu na `date` (bez datuma: danas); paket ima jednu cijenu `uom: "PAX"` = `pricePerPerson` (za `pax`, inače `minPax`). Slike su slike proizvoda. Polja paketa su null za ostale vrste.
- `available`: null bez datuma; proizvod `true`; resurs u INSTANT katalogu po `AvailabilityService` (nije `UNAVAILABLE` ni jedan dan), u INQUIRY katalogu slobodan u prozoru otvaranje prvog – zatvaranje zadnjeg dana (`ResourceSetupCapacityService.isFree`) i, uz `pax`, ima postav s kapacitetom ≥ pax; paket uz `pax` po postojećoj provjeri prostora paketa, a bez `X-Property-Id` (nema hotela za prostore) `null`.
- Filtriranje po hotelu (`X-Property-Id` ili `property_tenant_id` kataloga): resursi samo tog hotela, lančani proizvodi po `propertyVisibility`.
- `/maps?date=` (datum obavezan): mape vezane na sezonu datuma; ako ih nema, mape bez sezone važeće na datum (root, po lokaciji ona s najkasnijim `valid_from`). `status`: `INACTIVE` za neklikabilne, inače INSTANT `AVAILABLE|PARTIAL|UNAVAILABLE` iz `AvailabilityService`, INQUIRY `AVAILABLE|UNAVAILABLE`.
- `POST /quote`: zahtjev `{dateFrom, dateTo?, attendees?, dayFormat?, items:[{itemId, quantity?}]}`. `quantity` je **po danu** za dnevne UOM-ove (`PAX`, `DAY`, `HALF_DAY`/`HALFDAY`, `HOUR`, `NIGHT`) — linija je po danu, `totalGross = unit × quantity × days`; zadano `PAX` = attendees, `HOUR` = 8 (pola dana 4), ostalo 1. Ostali UOM-ovi (`UNIT`…) su jednokratni: jedna linija na `dateFrom`, zadano 1. Paket je cijena po osobi po danu (`uom: "PAX"`), poštuje min/max pax. Spajaju se uzastopni dani s istom cijenom **i** istim pravilom. `taxPercent` = tax1 + tax2 proizvoda; `totalNet` = Σ gross × 100 / (100 + tax). 400 (s `items[i]: …`) za nepostojeću, neobjavljenu, izvan sezone/valjanosti ili neprodajni datum.
- `POST /inquiries` (samo INQUIRY katalog, inače 400): kao v1 kontakt/događaj polja, `items:[{itemId, quantity?, setupStyle?}]`, bez cijena i valute; nepoznata polja 400. Idempotentnost (`cmsInquiryId`) se provjerava prije izračuna. Odgovor `{leadId, status, quote}` (201 novi, 200 ponovljeni s `quote: null`). Lead: `attrs.inquiryType` = **kod kataloga** (za MICE katalog `MICE`, kao do sada), `attrs.catalog`, `attrs.estimatedValue` = totalGross, `attrs.inquiry.items` i `attrs.inquiry.quote`.
- `/api/catalog/inquiries`, `/api/catalog/packages`, `/api/availability`, `/api/resource-maps/periods` nepromijenjeni.

**Admin API** (korisnički JWT; čitanje: svaki prijavljeni korisnik, ne m2m; pisanje: pravilo iz `ProductPriceService`, izdvojeno u `PricingAccess` — SUPER_ADMIN/ADMIN, CRM REVENUE_MANAGER ili STAFF bez prodajne CRM uloge):
- sezone točno po tablici niže; `GET /api/season-calendars/{id}/seasons` red ima i `seasonCalendarId` i `note`; POST/PUT sezone prima i `note`; kod se sprema velikim slovima (`[A-Z0-9_-]`, max 50). PUT sezone koji bi `SUB_PERIOD`/`DATES` razdoblje ostavio izvan sezone → 409. Kreiranje kalendara/sezone vraća 201. Duplikat naziva kalendara → 409.
- `/api/price-profile-dates`: polja `seasonId`, `periodKind` (null → `SEASON`); `SUB_PERIOD` mora imati sezonu; `SUB_PERIOD`/`DATES` sa sezonom moraju biti unutar nje (400). `/api/products/price-periods` vraća `seasonId`, `periodKind`.
- `/api/products/{id}/prices`: `daysOfWeek: ["FRI","SAT"]` (null/prazno ili svih 7 = svi dani); jedinstvenost (razdoblje, UOM, slot, dani). Adjust/copy čuvaju dane.
- `GET /api/products/{id}/price-check?date=&uom=&time=&requestType=&currency=` → `{winner:{priceGross, currency, rule}, candidates:[{priceGross, rule, reasonLost}]}`; `uom` zadano default UOM proizvoda, `currency` najčešća valuta njegovih cijena, `requestType` zadano EXTERNAL, hotel = `X-Property-Id`.
- katalozi: `GET/POST /api/sales-catalogs`, `GET/PUT/DELETE /api/sales-catalogs/{id}` — tijelo `{code, name, saleMode, seasonCalendarId?, requestType?, propertyTenantId?, active?, currency?}`; odgovor je entitet (`id, tenantId, propertyTenantId, code, name, saleMode, seasonCalendarId, requestType, currency, active, createdAt`). Promjena `code` ili `propertyTenantId` → 400; duplikat koda → 409; kalendar drugog hotela → 400.
- stavke: entitet `{id, tenantId, catalogId, itemKind, resourceId, productId, group, publicName, publicDescription, displayOrder, published, validFrom, validTo, minPax, maxPax, duration, defaultStartTime, setupStyle, createdAt, updatedAt}`. `GET …/items?group=&published=` vraća isti red plus `sourceName, displayName, imageUrl, pricesToday:[…], seasonIds:[…]`. POST: duplikat → 409, `PRODUCT` za paket / `PACKAGE` za ne-paket → 400, `setupStyle` mora biti SETUP_STYLE lookup. PUT ne mijenja `itemKind/resourceId/productId` (400 ako se pošalju drukčiji); `group` izostavljen = ostaje. Bulk → `{added, skipped, items}`; `resourceMapId` uključuje i podmape. `PATCH …/items/order` → navedene stavke 0..n−1, ostale iza.
- `GET/PUT /api/sales-catalogs/{id}/maps` `[{resourceMapId, seasonId?}]`; `GET /api/sales-catalogs/{id}/preview?date=&pax=` → `{date, season, sellable, reason, items, maps}`; `GET /api/products/{id}/catalog-items` → `[{catalogId, catalogCode, catalogName, item}]`.

**Greške**: 400 `IllegalArgumentException` s porukom koja imenuje polje (`items[0]: …`, `operating[1]: …`, `seasonId: …`), 409 za preklapanje ("Season overlaps 'Pre season' (PRE_2027, …)"), duplikate i korištenje ("used by catalog(s) BEACH"), 404 za nepostojeći katalog/kalendar/sezonu/stavku. `GlobalExceptionHandler` sada vraća 400 i za nedostajući query parametar (`date is required`) i za neispravan tip (`date: invalid value '…'`) — prije 500.

**Testovi**: `PriceListEntryResolverPrecedenceTest` (matrica prednosti, primjer iz specifikacije, tie-break, pravilo, price-check, 500 nasumičnih "legacy" slučajeva = staro pravilo), `SeasonServiceTest`, `ServiceCalendarServiceSeasonTest`, `PriceProfileDateServiceTest`, `CatalogViewAndQuoteServiceTest`, `SalesCatalogServiceTest`, `PackageListingStoreTest`, `CatalogItemsInquiryServiceTest`, `CatalogWebControllerTest` (m2m / korisnik / anonimno za sve `/api/catalogs` endpointe). `CatalogMigrationScratchDbIT` se pokreće samo uz `CATALOG_SCRATCH_DB_URL` (kopija baze migrirana na V133) i opcionalno `CATALOG_SOURCE_DB_URL` (izvor, samo čitanje): ispisuje što je migracija stvorila, uspoređuje resolver sa starim pravilom za sve cijene, provjerava `resource_map`/`periods` i poziva web API.

Migracije od **V130** naviše. Ovisi o Fazi 4 (paketi, `product_package_listing`, V101) i o `/api/catalog/inquiries` (V129).

## Cilj i načela

1. **booking-api je jedini izvor istine** za sve što se prodaje: resursi, proizvodi, paketi, kombinacije, cijene, PDV, dostupnost, raspored (mape) i sezone. Uređuje se u **booking-adminu**.
2. **booking-cms je samo prikaz.** Ne drži podatke o ponudi, ne računa cijene i ne odlučuje što je prodajno. Šalje *koji katalog*, *datum* i *odabir*, a prikazuje ono što booking-api vrati.
3. **Katalog** odvaja ponude koje se ne miješaju (plaža, MICE, vjenčanja…). Svaki prikaz na webu (lista, mapa, planer) traži jedan katalog.
4. **Sezona je eksplicitna.** Određuje je li se prodaje, raspored (mapu), radno vrijeme, ponudu i osnovnu cijenu. **Cijena unutar sezone može varirati** po pravilima s jasnim redoslijedom prednosti.
5. **Migracija bez promjene ponašanja.** Nakon migracije beach prodaje iste datume, iste resurse i po istim cijenama kao prije.

## Pojmovi

| Pojam | Značenje |
|---|---|
| **Katalog** (`sales_catalog`) | Skup onoga što se prodaje zajedno na jednom mjestu (npr. `BEACH`, `MICE`). Ima način prodaje i sezonski kalendar. |
| **Stavka kataloga** (`catalog_item`) | Resurs (ležaljka, dvorana, kombinacija), proizvod (catering, AV) ili paket objavljen u katalogu, s javnim nazivom i opisom. |
| **Način prodaje** | `INSTANT` (odabir → rezervacija → plaćanje) ili `INQUIRY` (odabir → upit → ponuda salesa). Svojstvo kataloga. |
| **Sezonski kalendar** (`season_calendar`) | Imenovani skup sezona za property (npr. "Plaža", "Hotel"). Više kataloga može dijeliti kalendar. |
| **Sezona** (`season`) | Datumski raspon u kalendaru sa statusom `OPEN` / `CLOSED`. |
| **Cjenovno razdoblje** | Postojeći `price_profile_date`, koji se veže na sezonu i dobiva vrstu (sezona / podrazdoblje / specifični datumi). |
| **Kanal** | Ostaje **tip rezervacijskog zahtjeva** (`ReservationRequest.Type`: INTERNAL / EXTERNAL / WALKIN / INHOUSE), kao i do sada. Katalog nije kanal. Web prodaja je kanal `EXTERNAL`. |

## Današnje stanje (provjereno u kodu)

- **Cijene**:
  - `price_profile` (tenant ili property, `currency`, `reservation_request_type`) → `price_profile_date` (`date_from`–`date_to`, opis) → `price_list` (proizvod, `uom`, `price`, opcionalni `start_time`/`end_time`).
  - `PriceListEntryResolver` uzima kandidate kojima razdoblje sadrži datum i bira **najspecifičniji** po ključu *proizvod + UOM + valuta + slot*:
    1. cijena propertyja prije cijene lanca
    2. unutar iste razine, odgovarajući tip zahtjeva prije ostalih
  - Kad se razdoblja istog profila preklapaju, pravila prednosti nema.
- **Raspored**: `resource_map.valid_from`/`valid_to`. Beach datumi u koje se može rezervirati su unija valjanosti root mapa (`GET /api/resource-maps/periods`), a `AvailabilityService` za datum bira važeću mapu.
- **Radno vrijeme**: `booking_calendar` po lokaciji (`open_time`, `close_time`, `grid_minutes`, `min/max_duration_minutes`) **nema datume**. Isto vrijedi za svaki dan (`ServiceCalendarService.windowFor`).
- **Web objava**: postoji samo za pakete (`product_package_listing`, V101, `/api/catalog/packages`). Dvorane i usluge nemaju objavu za web.
- **Ulazi za web**: `/api/availability?date=` (beach), `/api/resource-maps/periods`, `/api/catalog/packages`, `/api/catalog/inquiries`.

**Posljedica:** sezona postoji implicitno na tri mjesta (razdoblja cijena, valjanost mapa, nigdje za radno vrijeme), koja se ne poznaju međusobno. Cijena je tvrdo vezana uz razdoblje, a unutar razdoblja ne može varirati osim po vremenskom slotu.

## Model

### Sezonski kalendar i sezone

```sql
create table season_calendar (
  id bigserial primary key,
  tenant_id bigint not null,               -- org
  property_tenant_id bigint,               -- null = cijeli lanac
  name text not null,
  created_at timestamptz not null default now()
);

create table season (
  id bigserial primary key,
  season_calendar_id bigint not null references season_calendar(id) on delete cascade,
  code text not null,                      -- npr. PRE_2027, HIGH_2027
  name text not null,                      -- prikazni naziv
  date_from date not null,
  date_to date not null check (date_to >= date_from),
  status text not null default 'OPEN' check (status in ('OPEN','CLOSED')),
  display_order integer not null default 0,
  created_at timestamptz not null default now(),
  unique (season_calendar_id, code),
  exclude using gist (season_calendar_id with =, daterange(date_from, date_to, '[]') with &&)  -- btree_gist je već u V1
);
```

- Sezone u istom kalendaru **se ne preklapaju**. Svaki datum pripada najviše jednoj sezoni.
- Datum bez sezone ili u sezoni `CLOSED` **nije prodajan** u katalozima koji koriste taj kalendar.
- **Kopiranje godine**: admin akcija `POST /api/season-calendars/{id}/copy-year {fromYear, toYear, shiftToSameWeekday}`. Kopira sezone, radno vrijeme sezona i cjenovna razdoblja vezana uz sezone. Cijene se kopiraju kao kod današnjeg *Copy to period* (opcionalno uz %).

### Radno vrijeme po sezoni

```sql
create table season_operating (
  id bigserial primary key,
  season_id bigint not null references season(id) on delete cascade,
  location_node_id bigint references location_node(id) on delete cascade,  -- null = sve lokacije
  open_time time not null,
  close_time time not null check (close_time > open_time),
  grid_minutes int not null check (grid_minutes > 0),
  min_duration_minutes int not null default 0,
  max_duration_minutes int not null default 0,
  unique (season_id, location_node_id)
);
```

- Za datum vrijedi **`season_operating` sezone** (najprije za lokaciju, zatim bez lokacije), inače **`booking_calendar`** kao danas.
- `ServiceCalendarService.windowFor(calendar, date)` postaje svjestan sezone. Ostali pozivatelji se ne mijenjaju.

### Katalozi

```sql
create table sales_catalog (
  id bigserial primary key,
  tenant_id bigint not null,
  property_tenant_id bigint,
  code text not null,                       -- BEACH, MICE, …
  name text not null,
  sale_mode text not null check (sale_mode in ('INSTANT','INQUIRY')),
  season_calendar_id bigint references season_calendar(id),
  request_type text not null default 'EXTERNAL',  -- kanal po kojem se biraju cijene
  active boolean not null default true,
  created_at timestamptz not null default now(),
  unique (tenant_id, property_tenant_id, code)
);

create table catalog_item (
  id bigserial primary key,
  tenant_id bigint not null,
  catalog_id bigint not null references sales_catalog(id) on delete cascade,
  item_kind text not null check (item_kind in ('RESOURCE','PRODUCT','PACKAGE')),
  resource_id bigint references resource(id) on delete cascade,
  product_id bigint references product(id) on delete cascade,
  item_group text,                          -- npr. HALL, CATERING, AV (prikazna grupa unutar kataloga)
  public_name text,
  public_description text,
  display_order integer not null default 0,
  published boolean not null default false,
  valid_from date,
  valid_to date,
  -- polja paketa (prenesena iz product_package_listing)
  min_pax integer, max_pax integer,
  duration text check (duration in ('FULL_DAY','HALF_DAY','CUSTOM')),
  default_start_time time,
  setup_style text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check ((item_kind = 'RESOURCE') = (resource_id is not null)),
  check ((item_kind in ('PRODUCT','PACKAGE')) = (product_id is not null))
);
create unique index uq_catalog_item_resource on catalog_item (catalog_id, resource_id) where resource_id is not null;
create unique index uq_catalog_item_product  on catalog_item (catalog_id, product_id)  where product_id is not null;

create table catalog_item_season (          -- prazno = sve sezone
  catalog_item_id bigint not null references catalog_item(id) on delete cascade,
  season_id bigint not null references season(id) on delete cascade,
  primary key (catalog_item_id, season_id)
);

create table catalog_map (
  catalog_id bigint not null references sales_catalog(id) on delete cascade,
  resource_map_id bigint not null references resource_map(id) on delete cascade,
  season_id bigint references season(id) on delete cascade,  -- null = po valjanosti mape
  primary key (catalog_id, resource_map_id)
);
```

- **Isti proizvod ili resurs može biti u više kataloga**, svaki put sa svojim javnim nazivom i opisom.
- **Resurs-stavka** (dvorana, ležaljka, kombinacija) nosi cijenu kroz proizvod vezan na resurs (`resource.product`), kao danas. Kombinacije ostaju kakve jesu (`resource_composition`); kombinacija je samo još jedan resurs u katalogu.
- **Mapa** ostaje uz lokaciju. Katalog kaže koje mape koristi, a po želji i za koju sezonu. **Klikabilno je samo ono što je objavljena stavka kataloga**; ostali resursi na mapi vide se kao neaktivni.
- **Način prodaje je na razini kataloga.** Miješani slučaj (male sale po satu izravno, velike na upit) rješava se s dva kataloga.

### Cjenovna razdoblja i pravila unutar sezone

```sql
alter table price_profile_date
  add column season_id bigint references season(id) on delete set null,
  add column period_kind text not null default 'SEASON'
    check (period_kind in ('SEASON','SUB_PERIOD','DATES'));

alter table price_list
  add column days_of_week smallint check (days_of_week between 1 and 127);  -- bitmask pon=1 … ned=64; null = svi dani
```

- `SEASON`: osnovne cijene sezone (razdoblje = sezona ili njezin dio kad sezona nije vezana).
- `SUB_PERIOD`: dio sezone s drugačijom cijenom (npr. vrh sezone 15.7.–15.8.). Mora biti unutar `season_id` sezone.
- `DATES`: kratko razdoblje za specifične datume (blagdan, događaj).
- `days_of_week` na pojedinoj cijeni omogućuje npr. skuplji vikend unutar istog razdoblja.

**Pravilo odabira cijene** (proširenje `PriceListEntryResolver`). Ključ ostaje *proizvod + UOM + valuta + slot*, a od kandidata kojima razdoblje sadrži datum i kojima `days_of_week` (ako je zadan) sadrži dan u tjednu pobjeđuje onaj s najmanjim zbrojem specifičnosti, redom važnosti:

1. **razina**: cijena propertyja prije cijene lanca *(kao danas)*
2. **kanal**: odgovarajući `reservation_request_type` prije ostalih *(kao danas)*
3. **vrsta razdoblja**: `DATES` prije `SUB_PERIOD` prije `SEASON`
4. **dan u tjednu**: cijena s `days_of_week` prije cijene bez ograničenja
5. **izjednačenje**: kraće razdoblje, zatim noviji `created_at`

Primjer: u sezoni *Ljeto* osnovna cijena ležaljke je 30 €, vikendom 35 € (ista sezona, `days_of_week` = sub+ned), u *Vrhu sezone* 40 €, a 15.8. (`DATES`) 50 €. Za subotu unutar vrha sezone cijena je 40 €: vrh sezone (`SUB_PERIOD`) pobjeđuje vikend cijenu osnovne sezone, jer je vrsta razdoblja važnija od dana u tjednu. Ako vrh sezone ima i vlastitu vikend cijenu, ona pobjeđuje 40 €.

Resolver uz cijenu vraća i **primijenjeno pravilo** (profil, razdoblje, vrsta, sezona, dani), kako bi admin, ponuda i web znali zašto je cijena takva.

**Višednevni odabir** (MICE): cijena se računa **po danu**. Uzastopni dani s istom cijenom spajaju se u jednu liniju, a različiti dani (npr. petak i subota) daju zasebne linije.

## Kako se razrješava jedan datum u katalogu

1. **Sezona**: datum → sezona iz kalendara kataloga. Ako sezone nema ili je `CLOSED`, datum nije prodajan.
2. **Ponuda**: objavljene stavke kataloga kojima `valid_from`/`valid_to` sadrži datum i koje su u toj sezoni (ili bez ograničenja sezone). Paketi dodatno moraju zadovoljiti `min/max_pax`.
3. **Raspored**: mapa iz `catalog_map` za tu sezonu, inače mapa kataloga važeća na datum (`resource_map.valid_from/to`, kao danas).
4. **Radno vrijeme**: `season_operating` za lokaciju, zatim bez lokacije, inače `booking_calendar`.
5. **Dostupnost**: postojeći enginei, bez promjene: `AvailabilityService` za INSTANT (slotovi, alokacije), `ResourceSetupCapacityService.searchSpaces` / `crm/spaces` za dvorane.
6. **Cijena**: resolver iz prethodnog poglavlja, kanal = `sales_catalog.request_type`.

## API za web (m2m)

Svi endpointi traže JWT sa scopeom `booking.api` (`ROLE_API`) i `X-Tenant-Id` (+ opcionalni `X-Property-Id`), kao `/api/catalog/inquiries`. Tenant se uvijek uzima iz konteksta, nikad iz parametra.

| Metoda | Putanja | Opis |
|---|---|---|
| GET | `/api/catalogs` | aktivni katalozi tenanta: `[{code, name, saleMode}]` (za odabir kataloga u CMS-u) |
| GET | `/api/catalogs/{code}` | `{code, name, saleMode, currency}` |
| GET | `/api/catalogs/{code}/seasons?from=&to=` | `[{code, name, dateFrom, dateTo, status}]`. Zamjenjuje `/api/resource-maps/periods` za web. |
| GET | `/api/catalogs/{code}/items?date=&dateTo=&pax=&group=` | stavke za datum, vidi ispod |
| GET | `/api/catalogs/{code}/maps?date=` | `[{mapId, name, imageUrl, svgOverlay, positions:[{resourceId, polygon, label, status, selectable}]}]` |
| POST | `/api/catalogs/{code}/quote` | okvirna cijena odabira |
| POST | `/api/catalogs/{code}/inquiries` | samo `INQUIRY` katalozi; zamjenjuje `/api/catalog/inquiries` |

`INSTANT` katalozi (beach) **u prvoj fazi zadržavaju postojeći rezervacijski tok** (`/api/reservation-requests`, `/api/reservations/book`, Monri). Prelazak na `/api/catalogs/BEACH/...` dolazi tek kad CMS beach pređe na kataloge.

### Stavke

```json
[{
  "itemId": 41, "kind": "RESOURCE", "group": "HALL",
  "resourceId": 1386, "productId": 110,
  "name": "Galija", "description": "…",
  "images": [{"url": "…", "isDefault": true}],
  "areaSqm": 520, "naturalLight": true,
  "setups": [{"setupStyle": "THEATER", "capacity": 80}],
  "prices": [{"uom": "DAY", "amountGross": 1800.00, "currency": "EUR", "rule": {"season": "HIGH_2027", "periodKind": "SEASON"}}],
  "available": true
}]
```

Za proizvode i pakete `resourceId`, `areaSqm` i `setups` su null. Paket dodatno vraća `pricePerPerson` i komponente, kao danas `/api/catalog/packages`. `available` je null kad nije zadan datum.

### Quote

Zahtjev:

```json
{ "dateFrom": "2027-05-13", "dateTo": "2027-05-14", "attendees": 60, "dayFormat": "FULL_DAY",
  "items": [{"itemId": 41}, {"itemId": 57, "quantity": 120}] }
```

Odgovor:

```json
{ "currency": "EUR",
  "lines": [
    {"itemId": 41, "name": "Galija", "date": "2027-05-13", "days": 1, "quantity": 1, "uom": "DAY",
     "unitPriceGross": 1800.00, "totalGross": 1800.00, "taxPercent": 25, "rule": {"season": "PRE_2027", "periodKind": "SEASON"}},
    {"itemId": 41, "name": "Galija", "date": "2027-05-14", "days": 1, "quantity": 1, "uom": "DAY",
     "unitPriceGross": 2000.00, "totalGross": 2000.00, "taxPercent": 25, "rule": {"season": "PRE_2027", "periodKind": "SEASON", "daysOfWeek": "FRI,SAT"}}
  ],
  "totalGross": 3800.00, "totalNet": 3040.00, "hasItemsOnRequest": false }
```

Pravila:
- **Zadane količine** dolaze iz UOM-a, ako nisu zadane: `PAX` → gosti × dani, `DAY` / `HALF_DAY` → po danu, `UNIT` → 1. Za `HALF_DAY` format dvorana koristi UOM `HALF_DAY`, a ako ga nema, `DAY`.
- **Neobjavljene stavke** i stavke izvan sezone vraćaju 400.
- **Stavka bez cijene**: `unitPriceGross` je null, a `hasItemsOnRequest` = true.
- **Okvirna cijena**: web je prikazuje kao okvirnu. Konačna cijena, popusti i uvjeti ostaju u sales ponudi (`SalesQuote`), koja koristi isti resolver.

### Upit (INQUIRY)

Kao današnji `/api/catalog/inquiries` (V129), ali s `items` umjesto `spaces`/`services` i **bez cijena od klijenta**. booking-api sam izračuna quote i spremi ga u lead (`description` i `attrs.inquiry`). Idempotentnost preko `cmsInquiryId` ostaje.

## Admin API (korisnički JWT)

Pravo pisanja ima SUPER_ADMIN/ADMIN i CRM REVENUE_MANAGER, kao za cijene (`ProductPriceService`). Čitati mogu `MANAGEMENT_ROLES` i CRM uloge. Tenant se uzima iz konteksta.

**Sezone**

| Metoda | Putanja | Opis |
|---|---|---|
| GET | `/api/season-calendars` | `[{id, name, propertyTenantId, seasonCount, usedByCatalogs:[code]}]` |
| POST | `/api/season-calendars` | `{name, propertyTenantId?}` |
| PUT | `/api/season-calendars/{id}` | `{name}` |
| DELETE | `/api/season-calendars/{id}` | 409 ako ga koristi katalog |
| GET | `/api/season-calendars/{id}/seasons?year=` | `[{id, code, name, dateFrom, dateTo, status, displayOrder, operating:[…], pricePeriodCount}]` |
| POST | `/api/season-calendars/{id}/seasons` | `{code, name, dateFrom, dateTo, status, displayOrder}`; preklapanje → 409 s nazivom sezone s kojom se preklapa |
| PUT / DELETE | `/api/seasons/{id}` | brisanje odvezuje cjenovna razdoblja (`season_id = null`) |
| GET / PUT | `/api/seasons/{id}/operating` | cijela lista `[{locationNodeId?, openTime, closeTime, gridMinutes, minDurationMinutes, maxDurationMinutes}]` |
| POST | `/api/season-calendars/{id}/copy-year` | `{fromYear, toYear, shiftToSameWeekday=true, copyPrices=true, pricePercent?, roundTo?, dryRun?}` → `{seasons, operating, pricePeriods, prices, skipped:[…]}` |

**Cjenovna razdoblja i cijene** (proširenje postojećih)

- `/api/price-profile-dates` i `/api/products/price-periods` dobivaju polja `seasonId` i `periodKind`. Validacija: `SUB_PERIOD` i `DATES` moraju biti unutar svoje sezone.
- `/api/products/{id}/prices` (upsert) dobiva polje `daysOfWeek` (lista `MON…SUN` u JSON-u, bitmaska u bazi). Jedna cijena po (razdoblje, UOM, slot, dani).
- `GET /api/products/{id}/price-check?date=&uom=&time=&requestType=` → `{winner:{priceGross, currency, rule}, candidates:[{priceGross, rule, reasonLost}]}` za provjeru "zašto je ova cijena".

**Katalozi**

| Metoda | Putanja | Opis |
|---|---|---|
| GET / POST | `/api/sales-catalogs` | `{code, name, saleMode, seasonCalendarId?, requestType, propertyTenantId?, active}` |
| PUT / DELETE | `/api/sales-catalogs/{id}` | kod se ne mijenja nakon kreiranja (CMS ga koristi) |
| GET | `/api/sales-catalogs/{id}/items?group=&published=` | stavke s nazivom resursa/proizvoda, slikom i cijenom danas |
| POST | `/api/sales-catalogs/{id}/items` | `{itemKind, resourceId? / productId?, group?, publicName?, publicDescription?, …}`; duplikat → 409 |
| POST | `/api/sales-catalogs/{id}/items/bulk` | `{resourceMapId?}` ili `{resourceIds:[]}` ili `{productIds:[]}` → dodaje sve odjednom (neobjavljeno) |
| PUT / DELETE | `/api/catalog-items/{id}` | |
| PUT | `/api/catalog-items/{id}/seasons` | `[seasonId]` (prazno = sve sezone) |
| PATCH | `/api/sales-catalogs/{id}/items/order` | `[itemId]` redoslijed (drag & drop) |
| GET / PUT | `/api/sales-catalogs/{id}/maps` | `[{resourceMapId, seasonId?}]` |
| GET | `/api/sales-catalogs/{id}/preview?date=&pax=` | isto što bi web dobio za datum (`/items` + `/maps`), za provjeru prije objave |
| GET | `/api/products/{id}/catalog-items` | u kojim je katalozima proizvod (kartica na proizvodu) |

Paketi: `/api/products/{id}/package-listing` ostaje i interno čita i piše `catalog_item` (kind `PACKAGE`) kataloga `MICE`.

## booking-admin (ekrani)

- **Katalozi**:
  - popis kataloga (kod, naziv, način prodaje, kalendar, kanal)
  - stavke kataloga: dodavanje resursa, proizvoda i paketa, javni naziv i opis, grupa, redoslijed, objava, valjanost, sezone
  - mape kataloga
- **Sezone**: kalendari, sezone (vremenska traka po godini), status, radno vrijeme po sezoni i lokaciji, *Copy year*.
- **Pricing / Products → Prices**:
  - cjenovno razdoblje dobiva sezonu i vrstu (`SEASON` / `SUB_PERIOD` / `DATES`)
  - cijena dobiva dane u tjednu
  - *Price check* za datum prikazuje pobjedničku cijenu i primijenjeno pravilo
- **Products**: kartica *Web listing* (paketi) zamjenjuje se karticom *Katalozi* (u kojim je katalozima proizvod i s kojim tekstom).

## Migracija

Redoslijed migracija (brojevi orijentacijski):

- **V130** `season_calendar`, `season`, `season_operating`
- **V131** `sales_catalog`, `catalog_item`, `catalog_item_season`, `catalog_map`
- **V132** `price_profile_date.season_id`, `period_kind`; `price_list.days_of_week`
- **V133** podatkovna migracija (ispod)
- **V13x** (zasebno izdanje, nakon prelaska CMS-a) brisanje `product_package_listing`

Podatkovna migracija (V133), **bez promjene ponašanja**:

1. **Cijene**: postojeća `price_profile_date` dobivaju `period_kind = 'SEASON'` i `season_id = null`, a `price_list.days_of_week = null`. Resolver s novim pravilima tada daje **iste cijene** kao danas.
2. **Kalendari**: za svaki property s root resource mapama nastaje kalendar "Default", a za svaki različit raspon valjanosti root mapa sezona `OPEN`. Mape s otvorenim krajem dobivaju sezonu do kraja tekuće godine i bilješku za admina.
3. **Katalog `BEACH`**:
   - nastaje za svaki property koji ima mape s resursima
   - `sale_mode = INSTANT`, `request_type = EXTERNAL`
   - stavke: svi resursi s mapa (`resource_map_resource`), `published = true`
   - `catalog_map`: te mape
4. **Katalog `MICE`**:
   - nastaje za svaki tenant koji ima `product_package_listing`
   - `sale_mode = INQUIRY`
   - stavke `PACKAGE` iz listinga, s prenesenim poljima i stanjem objave
   - dvorane (`MEETING` proizvodi vezani na resurse) dodaju se kao **neobjavljene**, da ih admin svjesno objavi
5. **Kompatibilnost**: `/api/catalog/packages`, `/api/catalog/inquiries`, `/api/availability` i `/api/resource-maps/periods` ostaju i rade kao danas dok CMS ne pređe. Paketi se interno čitaju iz `catalog_item`.

**Provjera migracije** (test): prije i poslije V133 resolver za uzorak (proizvod, UOM, datum, slot, kanal) iz svih postojećih cijena vraća **isti iznos**, a `/api/resource-maps/periods` vraća iste raspone.

## booking-cms (za kontekst)

- CMS ne drži ni dvorane, ni usluge, ni upite. Kolekcije `venues`, `eventServices` i `eventInquiries` se brišu.
- Blokovi *Catalog list*, *Catalog map* i *Planner* imaju samo `catalog` (kod), način prikaza i način odabira. Akcija ovisi o `saleMode` koji vrati API.
- Datumski izbornik koristi `/seasons`. Cijene, dostupnost i raspored dolaze iz `/items`, `/maps` i `/quote`.
- Beach prelazi na `/api/catalogs/BEACH/...` i zajednički modul mape **zadnji**, uz pikselsku provjeru produkcijskog izgleda.

## Faze

1. **Sezone** (V130, V132 stupci, resolver s novim pravilima): migracija bez promjene ponašanja, admin za sezone i razdoblja.
2. **Katalozi** (V131, V133): admin za kataloge i objavu, kompatibilnost paketa.
3. **Web API** (`/api/catalogs/...`, quote po danu, upit s itemima).
4. **CMS MICE** na katalozima, uz brisanje CMS kolekcija.
5. **CMS beach** na katalozima i zajedničkoj mapi.

## Testovi

- Resolver: sve kombinacije redoslijeda prednosti (razina, kanal, vrsta razdoblja, dan u tjednu, izjednačenje) i provjera iste cijene nakon migracije.
- Sezone: zabrana preklapanja, `CLOSED` datum nije prodajan, *Copy year* (pomak na isti dan u tjednu, kopiranje cijena).
- Katalog: neobjavljena stavka ne izlazi; stavka izvan sezone ne izlazi; ista stavka u dva kataloga s različitim tekstom; mapa označava neaktivne resurse.
- Quote: cijena po danu s različitim pravilima, zadane količine po UOM-u, stavka bez cijene, 400 za neobjavljeno.
- Sigurnost: svi `/api/catalogs/**` samo za m2m; korisnički JWT 403, anonimno 401.

## Odluke (umjesto otvorenih pitanja)

1. **Redoslijed prednosti**: razina (property / lanac) i kanal ostaju prvi, kao danas, pa se postojeće cijene ne mijenjaju. Vrsta razdoblja i dan u tjednu dolaze nakon njih.
2. **Kopiranje godine**: zadano se pomiče na isti dan u tjednu (`shiftToSameWeekday = true`), a može i na isti datum. Postoji `dryRun` za pregled prije kopiranja.
3. **Sezona bez mape** u INSTANT katalogu je dopuštena: prodaja samo listom.
4. **Radno vrijeme po sezoni** pokriva otvaranje, zatvaranje, `grid_minutes` i min/max trajanje.
5. **Dan u tjednu** ulazi odmah (V132).
6. **Katalog** može biti na razini lanca. `X-Property-Id` tada filtrira stavke na resurse i proizvode tog hotela (`propertyVisibility`).
