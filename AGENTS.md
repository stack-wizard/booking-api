# booking-api — konvencije

Spring Boot 4.0.1, Java 21, Maven, PostgreSQL 16, Flyway, Spring Data JPA, springdoc-openapi.

Paket: `com.stackwizard.booking_api`. Podpaketi: `booking`, `config`, `controller`, `dto`, `exception`, `model`, `repository`, `security`, `service`, `support`.

## Migracije

- `src/main/resources/db/migration/V{n}__snake_case_opis.sql`, `{n}` je sljedeći slobodan cijeli broj
- **nikad ne mijenjaj postojeću migraciju** — Flyway checksum puca na svakom okruženju
- `btree_gist` je već instaliran (V1)
- svaka nova tablica dobiva: `id bigserial primary key`, `tenant_id bigint not null`, `created_at timestamptz not null default now()`
- enumi se u bazi pišu kao `text` s `check (col in (...))`, nikad kao PostgreSQL enum tip
- strani ključevi uvijek imaju eksplicitan `on delete` (`cascade`, `restrict` ili `set null`)
- indeks na svaki FK i na `tenant_id` gdje se filtrira

## Multitenancy

Gotovo svaka tablica ima `tenant_id bigint not null`. Tenant **nije** izveden iz entiteta — vadi se iz zahtjeva:

```java
Long tenantId = TenantResolver.requireTenantId();
```

Klijent šalje Platform tenant UUID u `X-Tenant-Id`, a `platform_tenant_mapping` (V86) ga prevodi u lokalni `tenant_id`.

**Svaki** upit koji dohvaća podatke mora filtrirati po `tenant_id`. Svaki insert ga mora postaviti.

## Sigurnost

Identitet je federiran na Mikos Platformu. `oauth2ResourceServer().jwt()` plus `PlatformAuthFilter` nakon `BearerTokenAuthenticationFilter`. JWT `sub` je `app_user.platform_user_id`, role stižu u `mikos_roles` claimu i mapira ih `PlatformRoleMapper`.

Nema lokalnog logina ni API tokena — ne dodavaj ih.

## Entiteti

Lombok, polja, bez logike:

```java
@Entity
@Table(name = "crm_account")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CrmAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false)
    private AccountType accountType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attrs", columnDefinition = "jsonb")
    private JsonNode attrs;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
```

- enumi su **ugniježđeni u entitet** (`ReservationRequest.Type`, `ReservationRequest.Status`) i uvijek `@Enumerated(EnumType.STRING)`
- veze su `@ManyToOne(fetch = FetchType.LAZY)`; za liste koristi `join fetch` u upitu
- JSONB ide preko `@JdbcTypeCode(SqlTypes.JSON)` s tipom `JsonNode`
- `created_at` je `insertable = false, updatable = false` — bazu puni `default now()`
- vremena: `OffsetDateTime` za audit i rokove, `LocalDateTime` za booking prozore (`reservation.starts_at`)

## Servisi

Konstruktorska injekcija, **bez** Lomboka, bez `@Autowired` na poljima:

```java
@Service
public class ResourceTypeService {
    private final ResourceTypeRepository repo;

    public ResourceTypeService(ResourceTypeRepository repo) {
        this.repo = repo;
    }
}
```

`@Transactional` na metodama koje pišu. Poslovna pravila idu u servis, nikad u kontroler ni entitet.

## Kontroleri

```java
@RestController
@RequestMapping("/api/crm/accounts")
public class CrmAccountController {
    private final CrmAccountService service;

    public CrmAccountController(CrmAccountService service) { this.service = service; }
}
```

- jednostavni CRUD vraća entitet izravno; DTO samo kad oblik odstupa od entiteta
- `ResponseEntity<T>` kad postoji 404 grana, inače goli tip
- `Optional` → `.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build())`
- za sortiranje po imenu polja vidi mapiranje u `InvoiceController` i `PaymentController`

## Greške

Ne hvataj iznimke u kontroleru. `GlobalExceptionHandler` već mapira:

- `IllegalArgumentException` → **400**
- `IllegalStateException` → **409** (i logira)
- `DataIntegrityViolationException` → **400**
- `ResponseStatusException` → vlastiti status
- sve ostalo → **500**

Tijelo je `ApiError(timestamp, status, error, message, path)`. Bacaj `IllegalArgumentException` za neispravan ulaz i `IllegalStateException` za sukob stanja.

## Repozitoriji

`JpaRepository`, imenovane metode ili `@Query` s JPQL-om. Native SQL samo kad JPQL ne može (vidi `ManagementStayDashboardRepository`).

## Booking i kapacitet

- `booking` je B2B blok na razini lanca (ORG), u Operi **block**; `booking_function` je Opera **event** (space × vrijeme), a `booking_room_block` + `booking_room_block_night` su room grid (kvota po tipu sobe i noći)
- svaki hotel u kojem booking nešto drži ima točno jedan `reservation_request` s `booking_id` (`INTERNAL`, `expires_at null`); stvara ga i briše `BookingReservationSync`, status prati fazu bookinga. Guest tokovi (plaćanje, istek, finalize, delete, otkaz) takve requestove odbijaju kroz `ReservationRequestService.requireNotBookingManaged`
- svaki `reservation` redak ima `request_id` (nema više retka bez requesta); `booking_function_id` / `booking_room_block_id` su samo veza na potrebu
- `allocation.units` / `reservation.units` = koliko jedinica resursa redak drži; `qty` ostaje cjenovna količina, bruto = `unit_price × qty × units`
- POOL resurs: kapacitet je `pool_total_units`, zauzeće je zbroj `units` na poolu i na EXACT članovima (`parent_pool_id`); EXACT član je slobodan samo kad je slobodan on **i** pool ima slobodnu jedinicu. Provjera ide isključivo kroz `AllocationCapacityService`
- tip sobe = POOL resurs tipa `ROOM` ili `APARTMENT`; noć room blocka je jedan redak 15:00 → 11:00 s `units` = kvota
- `opera_hotel.space_inventory` i `room_inventory` (`LOCAL|OPERA`) odvojeno biraju tko vodi sale i sobe; `billing` tko izdaje račun
- hotel s `room_inventory = OPERA` (ili `space_inventory = OPERA`) za te potrebe ne piše lokalni allocation; request dobiva `opera_sync_status = PENDING/SYNCED/FAILED`, a `OperaBlockSyncService` šalje OPERA blok + room grid (`booking_opera_block`)
- uvoz ROOM_TYPE kodova (`Import codes`) zatim `Sync room types` stvara lokalne POOL resurse tipa `ROOM` i mapira ih
- `OperaSetupService` (`POST /api/opera/hotels/{id}/setup`) radi cijeli setup: kodovi, tipovi soba, function spaceovi (`MEETING_ROOM`), EVENT_TYPE/SETUP_STYLE lookupi i 1:1 mape, price profile → RATE_CODE, `default_rental_code=CUSTOM`, statusi bloka
- kad lanac ima uvezene Opera statuse, svaki `booking_status` mora imati `opera_code` iste faze, a `booking_status_flow` smije sadržavati samo prijelaze koje Opera dopušta (`OperaStatusGraph`)
- `BookingService.transition` prvo zove `verifyOperaTransition` (Opera `verificationOnly`), tek onda mijenja status; `OperaBlockSyncService` vodi blok Opera putanjom do ciljnog koda
- function sa spaceom u hotelu sa `space_inventory = OPERA` je Opera event (`booking_function.opera_event_id`, obavezan `event_type`); brisanje ili premještanje prvo briše event
- catering i najam idu na Posting Master bloka kroz `OperaPmPostingService`: šalje se samo razlika prema `booking_opera_posting` (POSTED), ništa se ne briše
- račun (i iz ugovora i iz BEO-a) izdaje hotel odabran u `X-Property-Id` (`SalesContractService.invoicingHotel`); broj računa je jedinstven po tenantu (V123)

## Što ne raditi

- ne dirati `allocation` semantiku ni njezina ograničenja — to je registar kapaciteta i jedino mjesto gdje se termin zauzima
- ne dodavati vrijednosti u `reservation_request.type` — to je **kanal** (`INTERNAL`, `EXTERNAL`, `WALKIN`, `INHOUSE`), ne prodajni motiv, i `price_profile.reservation_request_type` ovisi o njemu
- ne uvoditi novi availability engine — `booking_calendar` već nosi `grid_minutes`, `min_duration_minutes`, `max_duration_minutes`
- ne tvrdo kodirati `uom` vrijednosti — `BookingUom` ima samo `normalize(String)`, ostalo su podaci
