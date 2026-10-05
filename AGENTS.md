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

## Što ne raditi

- ne dirati `allocation` semantiku ni njezina ograničenja — to je registar kapaciteta i jedino mjesto gdje se termin zauzima
- ne dodavati vrijednosti u `reservation_request.type` — to je **kanal** (`INTERNAL`, `EXTERNAL`, `WALKIN`, `INHOUSE`), ne prodajni motiv, i `price_profile.reservation_request_type` ovisi o njemu
- ne uvoditi novi availability engine — `booking_calendar` već nosi `grid_minutes`, `min_duration_minutes`, `max_duration_minutes`
- ne tvrdo kodirati `uom` vrijednosti — `BookingUom` ima samo `normalize(String)`, ostalo su podaci
