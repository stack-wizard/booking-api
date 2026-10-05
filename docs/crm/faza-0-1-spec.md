# Implementacijska specifikacija — Faza 0 i Faza 1

Opseg: temelji (audit, custom fields, timovi, autorizacija) i CRM core (accounti, kontakti, konfigurabilni pipeline, lead, opportunity, aktivnosti, funnel).

Migracije **V89–V93**. Zadnja postojeća je V88.

Prije početka pročitaj [AGENTS.md](../../AGENTS.md) u ovom repou i u `booking-admin`. Sve konvencije odande vrijede i ovdje se ne ponavljaju.

Radi **korak po korak**, redom. Svaki korak ima kriterij prihvaćanja; ne kreći na sljedeći dok prethodni ne prolazi.

## Testovi i provjera

Repo ima 32 testne klase, sve **JUnit 5 + Mockito, bez baze** — nema Testcontainersa ni integracijskih testova. Ne uvoditi ih u ovim fazama.

Za svaki korak piši **unit testove servisa s mockanim repozitorijima**, po uzoru na `CancellationPolicyServiceTest` i `ReservationRequestServiceTest`. Pokrij osobito: validaciju `crm_stage_requirement`, obvezan razlog pri zatvaranju, idempotentnost konverzije leada i izbor scopea po roli.

Kriteriji koji ovise o bazi (unique indeksi, CHECK ograničenja, kaskade) **ne idu u testove** — provjeri ih ručno nakon `./mvnw spring-boot:run`, pozivom endpointa.

Build mora proći: `./mvnw -q verify`.

---

## Korak 1 — V89: audit, timovi, custom fields

### Odstupanje od postojeće konvencije, namjerno

Postojeće tablice imaju `created_at` kao `insertable = false` s `default now()` u bazi. Za **nove `crm_*` i kasnije `event_*` / `sales_*` tablice** vlasnik vremena je JPA auditing, jer trebamo i `updated_at` i tko je mijenjao. DB default ostaje kao zaštita.

### Migracija `V89__crm_foundations.sql`

```sql
create table crm_team (
  id             bigserial primary key,
  tenant_id      bigint not null,
  name           text not null,
  parent_team_id bigint null references crm_team(id) on delete set null,
  active         boolean not null default true,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  created_by     bigint null,
  updated_by     bigint null
);
create unique index uq_crm_team_tenant_name on crm_team (tenant_id, lower(name));
create index idx_crm_team_parent on crm_team (parent_team_id);

create table crm_team_member (
  id          bigserial primary key,
  tenant_id   bigint not null,
  team_id     bigint not null references crm_team(id) on delete cascade,
  app_user_id bigint not null references app_user(id) on delete cascade,
  team_lead   boolean not null default false,
  created_at  timestamptz not null default now()
);
create unique index uq_crm_team_member on crm_team_member (team_id, app_user_id);
create index idx_crm_team_member_user on crm_team_member (tenant_id, app_user_id);

create table crm_custom_field_definition (
  id            bigserial primary key,
  tenant_id     bigint not null,
  entity        text not null
                check (entity in ('ACCOUNT','CONTACT','LEAD','OPPORTUNITY','EVENT')),
  field_key     text not null,
  label         text not null,
  field_type    text not null
                check (field_type in ('TEXT','NUMBER','BOOLEAN','DATE','SELECT','MULTISELECT')),
  options       jsonb not null default '[]'::jsonb,
  required      boolean not null default false,
  display_order int not null default 0,
  active        boolean not null default true,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  created_by    bigint null,
  updated_by    bigint null
);
create unique index uq_crm_cfd on crm_custom_field_definition (tenant_id, entity, field_key);
```

### Java

`config/JpaAuditingConfig.java`

```java
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {
    @Bean
    public AuditorAware<Long> auditorAware(AuthUserAccessor authUserAccessor) {
        return () -> authUserAccessor.currentAppUser().map(AppUser::getId);
    }
}
```

`AuthUserAccessor.currentAppUser()` već vraća `Optional<AppUser>` i **ne baca** iznimku — prazan je za m2m i anonimne pozive te za scheduled jobove. Ne dodavati nove metode na tu klasu.

`model/CrmAuditableEntity.java`

```java
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Data
public abstract class CrmAuditableEntity {
    @CreatedDate   @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @LastModifiedDate @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @CreatedBy     @Column(name = "created_by", updatable = false)
    private Long createdBy;
    @LastModifiedBy @Column(name = "updated_by")
    private Long updatedBy;
}
```

Sve nove `crm_*` tablice koje imaju te četiri kolone nasljeđuju ovu klasu. `@Data` na podklasi uz nasljeđivanje traži `@EqualsAndHashCode(callSuper = true)` — dodaj ga da Lombok ne upozorava.

Entiteti, repozitoriji i servisi za `CrmTeam`, `CrmTeamMember`, `CrmCustomFieldDefinition` po standardnom obrascu.

### Kriterij prihvaćanja

- aplikacija se diže, Flyway primijeni V89
- POST na `/api/crm/teams` stvori red u kojem su `created_at`, `updated_at` i `created_by` popunjeni
- PUT na isti red promijeni `updated_at` i `updated_by`, a `created_*` ostavi netaknutima

---

## Korak 2 — Autorizacija

### Problem

`PlatformRoleMapper.mapHighest(Collection<String>)` kolabira sve `mikos_roles` u **jednu** `AppUser.Role`. CRM-u treba više istovremenih rola — prodavač može biti i koordinator eventa.

### Nove Platform role

`BOOKING_SALES_REP`, `BOOKING_SALES_MANAGER`, `BOOKING_EVENT_COORDINATOR`, `BOOKING_REVENUE_MANAGER`.

**Ne diraj `AppUser.Role`** ni `app_user.role` kolonu — booking strana ostaje kakva jest. CRM role su izvedene iz JWT claima po zahtjevu i **ne spremaju se**.

### Java

`security/CrmRole.java` — enum `SALES_REP`, `SALES_MANAGER`, `EVENT_COORDINATOR`, `REVENUE_MANAGER`.

`security/PlatformRoleMapper.java` — **dodaj** metodu, postojeću `mapHighest` ostavi netaknutu:

```java
public Set<CrmRole> mapCrmRoles(Collection<String> mikosRoles) { ... }
```

`security/CrmPermission.java` — enum, npr. `ACCOUNT_READ`, `ACCOUNT_WRITE`, `OPPORTUNITY_READ`, `OPPORTUNITY_WRITE`, `OPPORTUNITY_CLOSE`, `PIPELINE_CONFIG`, `ACTIVITY_WRITE`, `REPORT_READ`.

`security/CrmScope.java` — enum `OWN`, `TEAM`, `ALL`.

`security/CrmPermissionCatalog.java` — **u kodu, ne u bazi**. Statična mapa `CrmRole → Set<CrmPermission>` i `CrmRole → CrmScope`:

- `SALES_REP` → čitanje i pisanje accounta, opportunityja i aktivnosti, scope `OWN`
- `SALES_MANAGER` → sve od repa plus `OPPORTUNITY_CLOSE`, `PIPELINE_CONFIG`, `REPORT_READ`, scope `TEAM`
- `EVENT_COORDINATOR` → čitanje accounta i opportunityja, pisanje aktivnosti, scope `TEAM`
- `REVENUE_MANAGER` → čitanje svega plus `REPORT_READ`, scope `ALL`
- `SUPER_ADMIN` i `ADMIN` iz postojećeg mapiranja dobivaju sve dozvole i scope `ALL`

Kad korisnik ima više rola, dozvole su **unija**, a scope **najširi**.

`security/CrmAccessContext.java` — request-scoped, nudi `Set<CrmPermission>`, `CrmScope`, `currentUserId()`, `teamUserIds()`.

`security/RequiresCrmPermission.java` + aspekt — anotacija na servisnoj metodi, baca `ResponseStatusException(FORBIDDEN)` kad dozvole nema.

Filtriranje po scopeu ide u repozitorij, ne u memoriju:

- `OWN` → `owner_user_id = :currentUserId`
- `TEAM` → `owner_user_id in (:teamUserIds)`
- `ALL` → bez dodatnog uvjeta

### Promjena na `/api/auth/me`

Odgovor dobiva novo polje `roles: string[]` (sve mapirane role). Postojeće polje `role` **ostaje** radi kompatibilnosti.

### Kriterij prihvaćanja

- korisnik s `BOOKING_SALES_REP` u `mikos_roles` vidi samo accounte kojima je vlasnik
- korisnik s `BOOKING_SALES_MANAGER` vidi accounte cijelog tima
- poziv bez potrebne dozvole vraća 403
- `/api/auth/me` vraća popunjen `roles`

---

## Korak 3 — V90: accounti i kontakti

```sql
create table crm_account (
  id                bigserial primary key,
  tenant_id         bigint not null,
  name              text not null,
  legal_name        text null,
  account_type      text not null default 'COMPANY'
                    check (account_type in ('COMPANY','AGENCY','ASSOCIATION','PERSON')),
  segment           text null,
  vat_id            text null,
  parent_account_id bigint null references crm_account(id) on delete set null,
  owner_user_id     bigint null references app_user(id) on delete set null,
  team_id           bigint null references crm_team(id) on delete set null,
  email             text null,
  phone             text null,
  website           text null,
  address_line      text null,
  city              text null,
  postal_code       text null,
  country           char(2) null,
  active            boolean not null default true,
  attrs             jsonb not null default '{}'::jsonb,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),
  created_by        bigint null,
  updated_by        bigint null
);
create index idx_crm_account_tenant on crm_account (tenant_id);
create index idx_crm_account_owner on crm_account (tenant_id, owner_user_id);
create index idx_crm_account_parent on crm_account (parent_account_id);
create unique index uq_crm_account_vat
  on crm_account (tenant_id, vat_id) where vat_id is not null;

create table crm_contact (
  id               bigserial primary key,
  tenant_id        bigint not null,
  account_id       bigint null references crm_account(id) on delete set null,
  first_name       text not null,
  last_name        text not null,
  email            text null,
  phone            text null,
  job_title        text null,
  platform_user_id uuid null,
  active           boolean not null default true,
  attrs            jsonb not null default '{}'::jsonb,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  created_by       bigint null,
  updated_by       bigint null
);
create index idx_crm_contact_tenant on crm_contact (tenant_id);
create index idx_crm_contact_account on crm_contact (account_id);
create unique index uq_crm_contact_email
  on crm_contact (tenant_id, lower(email)) where email is not null;

create table crm_account_contact_role (
  id         bigserial primary key,
  tenant_id  bigint not null,
  account_id bigint not null references crm_account(id) on delete cascade,
  contact_id bigint not null references crm_contact(id) on delete cascade,
  role       text not null
             check (role in ('DECISION_MAKER','BILLING','ON_SITE','TECHNICAL','OTHER')),
  primary_contact boolean not null default false,
  created_at timestamptz not null default now()
);
create unique index uq_crm_acr on crm_account_contact_role (account_id, contact_id, role);
```

`platform_user_id` je priprema za B2B portal iz Faze 6 — sada se samo sprema.

### Endpointi

- `GET /api/crm/accounts` — stranicenje (`page`, `size`, `sort`), filtri `search` (po `name` i `vat_id`), `accountType`, `segment`, `ownerUserId`, `active`. Rezultat filtriran po scopeu.
- `GET /api/crm/accounts/{id}` — 404 kad ne postoji ili je izvan scopea
- `POST /api/crm/accounts`, `PUT /api/crm/accounts/{id}`, `DELETE /api/crm/accounts/{id}` (soft delete preko `active = false`)
- isto za `/api/crm/contacts`
- `GET /api/crm/accounts/{id}/contacts`
- `POST /api/crm/accounts/{id}/contacts/{contactId}/roles`, `DELETE .../roles/{roleId}`

`tenant_id` se **nikad** ne čita iz tijela zahtjeva — uvijek `TenantResolver.requireTenantId()`.

### Kriterij prihvaćanja

- dva accounta s istim `vat_id` u istom tenantu → 400 iz `DataIntegrityViolationException`
- isti `vat_id` u različitim tenantima → prolazi
- account iz tuđeg tenanta nije vidljiv ni na listi ni na `GET /{id}`

---

## Korak 4 — V91: konfigurabilni pipeline

```sql
create table crm_pipeline (
  id          bigserial primary key,
  tenant_id   bigint not null,
  code        text not null,
  name        text not null,
  description text null,
  is_default  boolean not null default false,
  active      boolean not null default true,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  created_by  bigint null,
  updated_by  bigint null
);
create unique index uq_crm_pipeline_code on crm_pipeline (tenant_id, upper(code));
create unique index uq_crm_pipeline_default
  on crm_pipeline (tenant_id) where is_default;

create table crm_pipeline_stage (
  id            bigserial primary key,
  tenant_id     bigint not null,
  pipeline_id   bigint not null references crm_pipeline(id) on delete cascade,
  code          text not null,
  name          text not null,
  display_order int not null,
  probability   numeric(5,2) not null default 0 check (probability between 0 and 100),
  stage_kind    text not null default 'OPEN'
                check (stage_kind in ('OPEN','WON','LOST')),
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  created_by    bigint null,
  updated_by    bigint null
);
create unique index uq_crm_stage_code on crm_pipeline_stage (pipeline_id, upper(code));
create unique index uq_crm_stage_order on crm_pipeline_stage (pipeline_id, display_order);

create table crm_stage_requirement (
  id            bigserial primary key,
  tenant_id     bigint not null,
  stage_id      bigint not null references crm_pipeline_stage(id) on delete cascade,
  field_path    text not null,
  requirement   text not null default 'REQUIRED'
                check (requirement in ('REQUIRED','MIN_VALUE','MAX_VALUE')),
  value_spec    text null,
  message       text not null,
  created_at    timestamptz not null default now()
);
create index idx_crm_stage_req_stage on crm_stage_requirement (stage_id);

create table crm_outcome_reason (
  id            bigserial primary key,
  tenant_id     bigint not null,
  kind          text not null
                check (kind in ('WON','LOST','TURNED_DOWN','CANCELLED')),
  code          text not null,
  name          text not null,
  display_order int not null default 0,
  active        boolean not null default true,
  created_at    timestamptz not null default now()
);
create unique index uq_crm_outcome_reason on crm_outcome_reason (tenant_id, kind, upper(code));
```

`field_path` je ime polja na opportunityju (`amount`, `expectedCloseDate`, `contactId`) ili ključ u `attrs` s prefiksom `attrs.`. Validacija se izvodi pri **prijelazu u** tu fazu; neuspjeh baca `IllegalStateException` → 409 s porukom iz `message`.

Tri negativna ishoda su **različita** i imaju različite razloge: `TURNED_DOWN` (mi odbili), `LOST` (klijent otišao drugdje), `CANCELLED` (bio dogovoren pa otkazan). Ne spajati ih.

### Endpointi

Standardni CRUD pod `/api/crm/pipelines`, `/api/crm/pipelines/{id}/stages`, `/api/crm/stages/{id}/requirements`, `/api/crm/outcome-reasons`. Sve pisanje traži dozvolu `PIPELINE_CONFIG`.

### Kriterij prihvaćanja

- drugi pipeline s `is_default = true` u istom tenantu → 400
- dvije faze s istim `display_order` unutar pipelinea → 400

---

## Korak 5 — V92: lead, opportunity, prijelazi

```sql
create table crm_lead (
  id              bigserial primary key,
  tenant_id       bigint not null,
  company_name    text null,
  first_name      text null,
  last_name       text null,
  email           text null,
  phone           text null,
  source          text null,
  status          text not null default 'NEW'
                  check (status in ('NEW','WORKING','QUALIFIED','DISQUALIFIED','CONVERTED')),
  owner_user_id   bigint null references app_user(id) on delete set null,
  description     text null,
  disqualify_reason_id bigint null references crm_outcome_reason(id) on delete set null,
  converted_account_id     bigint null references crm_account(id) on delete set null,
  converted_contact_id     bigint null references crm_contact(id) on delete set null,
  converted_opportunity_id bigint null,
  converted_at    timestamptz null,
  attrs           jsonb not null default '{}'::jsonb,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  created_by      bigint null,
  updated_by      bigint null
);
create index idx_crm_lead_tenant_status on crm_lead (tenant_id, status);
create index idx_crm_lead_owner on crm_lead (tenant_id, owner_user_id);

create table crm_opportunity (
  id                  bigserial primary key,
  tenant_id           bigint not null,
  name                text not null,
  account_id          bigint not null references crm_account(id) on delete restrict,
  primary_contact_id  bigint null references crm_contact(id) on delete set null,
  pipeline_id         bigint not null references crm_pipeline(id) on delete restrict,
  stage_id            bigint not null references crm_pipeline_stage(id) on delete restrict,
  owner_user_id       bigint null references app_user(id) on delete set null,
  team_id             bigint null references crm_team(id) on delete set null,
  amount              numeric(14,2) null,
  currency            text not null default 'EUR',
  expected_close_date date null,
  source              text null,
  status              text not null default 'OPEN'
                      check (status in ('OPEN','WON','LOST','TURNED_DOWN','CANCELLED')),
  outcome_reason_id   bigint null references crm_outcome_reason(id) on delete set null,
  outcome_note        text null,
  closed_at           timestamptz null,
  attrs               jsonb not null default '{}'::jsonb,
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now(),
  created_by          bigint null,
  updated_by          bigint null
);
create index idx_crm_opp_tenant_status on crm_opportunity (tenant_id, status);
create index idx_crm_opp_account on crm_opportunity (account_id);
create index idx_crm_opp_stage on crm_opportunity (stage_id);
create index idx_crm_opp_owner on crm_opportunity (tenant_id, owner_user_id);

alter table crm_lead
  add constraint crm_lead_converted_opportunity_fkey
  foreign key (converted_opportunity_id) references crm_opportunity(id) on delete set null;

create table crm_stage_transition (
  id             bigserial primary key,
  tenant_id      bigint not null,
  opportunity_id bigint not null references crm_opportunity(id) on delete cascade,
  from_stage_id  bigint null references crm_pipeline_stage(id) on delete set null,
  to_stage_id    bigint not null references crm_pipeline_stage(id) on delete restrict,
  changed_by     bigint null references app_user(id) on delete set null,
  changed_at     timestamptz not null default now(),
  note           text null
);
create index idx_crm_transition_opp on crm_stage_transition (opportunity_id, changed_at);
create index idx_crm_transition_tenant on crm_stage_transition (tenant_id, changed_at);
```

`crm_stage_transition` je **append-only**. Nikad `update` ni `delete`. To je jedini izvor za funnel i za vrijeme provedeno u fazi.

### Pravila u servisu

**Promjena faze** (`PUT /api/crm/opportunities/{id}/stage`):

1. učitaj opportunity uz provjeru tenanta i scopea
2. provjeri da ciljna faza pripada istom pipelineu, inače `IllegalArgumentException`
3. izvrši `crm_stage_requirement` za ciljnu fazu; prvi neuspjeh → `IllegalStateException` s porukom iz `message`
4. ako je `stage_kind` ciljne faze `WON` ili `LOST`, traži `outcomeReasonId` i to onaj čiji `kind` odgovara; postavi `status` i `closed_at`
5. upiši `crm_stage_transition`
6. sve u jednoj `@Transactional` metodi

**Konverzija leada** (`POST /api/crm/leads/{id}/convert`), jedna transakcija:

1. lead mora biti u statusu različitom od `CONVERTED` i `DISQUALIFIED`, inače `IllegalStateException`
2. account: iskoristi `accountId` iz tijela ako je poslan, inače stvori novi iz `company_name`
3. kontakt: isto, iz imena i emaila leada
4. opportunity: stvori u prvoj fazi zadanog pipelinea (`is_default`), vlasnika preuzmi s leada
5. upiši prvi `crm_stage_transition` s `from_stage_id = null`
6. na leadu postavi `status = 'CONVERTED'`, tri `converted_*` ključa i `converted_at`

Konverzija je **idempotentna po leadu** — drugi poziv vraća 409.

### Endpointi

- CRUD `/api/crm/leads`, `/api/crm/opportunities`
- `POST /api/crm/leads/{id}/convert`
- `PUT /api/crm/opportunities/{id}/stage`
- `GET /api/crm/opportunities/{id}/transitions`

### Kriterij prihvaćanja

- prijelaz u fazu s nezadovoljenim zahtjevom vraća 409 s porukom iz `crm_stage_requirement.message`
- prijelaz u `WON` fazu bez `outcomeReasonId` vraća 400
- konverzija leada stvori account, kontakt i opportunity te ostavi točno jedan redak u `crm_stage_transition`
- ponovljena konverzija vraća 409

---

## Korak 6 — V93: aktivnosti i privici

```sql
create table crm_activity (
  id             bigserial primary key,
  tenant_id      bigint not null,
  activity_type  text not null
                 check (activity_type in ('CALL','EMAIL','MEETING','TASK','NOTE')),
  subject        text not null,
  body           text null,
  account_id     bigint null references crm_account(id) on delete cascade,
  contact_id     bigint null references crm_contact(id) on delete set null,
  opportunity_id bigint null references crm_opportunity(id) on delete cascade,
  lead_id        bigint null references crm_lead(id) on delete cascade,
  assigned_to    bigint null references app_user(id) on delete set null,
  due_at         timestamptz null,
  done_at        timestamptz null,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  created_by     bigint null,
  updated_by     bigint null,
  constraint crm_activity_has_parent check (
    account_id is not null or opportunity_id is not null or lead_id is not null
  )
);
create index idx_crm_activity_account on crm_activity (account_id, created_at desc);
create index idx_crm_activity_opp on crm_activity (opportunity_id, created_at desc);
create index idx_crm_activity_open
  on crm_activity (tenant_id, assigned_to, due_at) where done_at is null;

create table crm_attachment (
  id             bigserial primary key,
  tenant_id      bigint not null,
  account_id     bigint null references crm_account(id) on delete cascade,
  opportunity_id bigint null references crm_opportunity(id) on delete cascade,
  file_name      text not null,
  content_type   text null,
  size_bytes     bigint null,
  storage_key    text not null,
  created_at     timestamptz not null default now(),
  created_by     bigint null,
  constraint crm_attachment_has_parent check (
    account_id is not null or opportunity_id is not null
  )
);
create index idx_crm_attachment_account on crm_attachment (account_id);
create index idx_crm_attachment_opp on crm_attachment (opportunity_id);
```

Privici idu preko **postojećeg** S3/MinIO servisa koji se koristi za slike proizvoda — ne uvoditi novi.

`crm_activity` je i log i zadatak: zadatak je redak s popunjenim `due_at`, dovršen je onaj s `done_at`.

### Endpointi

- CRUD `/api/crm/activities`, filtri `accountId`, `opportunityId`, `leadId`, `assignedTo`, `openOnly`
- `POST /api/crm/activities/{id}/complete`
- `POST /api/crm/accounts/{id}/attachments` (multipart), `GET`, `DELETE`

---

## Korak 7 — Funnel i konverzije

Read-only endpointi, traže dozvolu `REPORT_READ`, računaju se iz `crm_stage_transition` i `crm_opportunity`.

- `GET /api/crm/reports/funnel?pipelineId&from&to&ownerUserId` — po fazi: broj opportunityja koji su ušli, broj koji su prošli dalje, broj zapelih
- `GET /api/crm/reports/conversion?from&to` — win rate kao `WON / (WON + LOST + TURNED_DOWN)`, odvojeno po vlasniku i segmentu accounta
- `GET /api/crm/reports/stage-duration?pipelineId&from&to` — prosječno i medijan vrijeme po fazi iz razlike uzastopnih `changed_at`

Native SQL je ovdje u redu, po uzoru na `ManagementStayDashboardRepository`.

**Tri negativna ishoda prijavljuj odvojeno.** Spajanje `LOST` i `TURNED_DOWN` čini win rate besmislenim.

---

## Korak 8 — booking-admin

### Deklarativno, bez novih stranica

U `src/pages/crud/config.ts` dodaj unose u `resourceConfigs` za: `crm-teams`, `crm-custom-fields`, `crm-accounts`, `crm-contacts`, `crm-pipelines`, `crm-pipeline-stages`, `crm-outcome-reasons`, `crm-activities`.

Ruta, lista i forma nastaju automatski iz `App.tsx`.

### Vlastite stranice

Samo tri, jer nisu tablica s formom:

- **OpportunitiesPage** — kanban po fazama zadanog pipelinea, drag-and-drop zove `PUT /api/crm/opportunities/{id}/stage`; 409 prikaži kao poruku iz odgovora, ne kao generičku grešku (`skipGlobalError: true`)
- **LeadsPage** — lista s radnjom "Convert" koja otvara dijalog za izbor postojećeg accounta ili stvaranje novog
- **CrmReportsPage** — tri grafa iz Koraka 7

### Promjena rola, povezana s Korakom 2

`authStore` sada drži **više rola**:

- `AuthMeResponse` dobiva `roles: string[]`
- `AuthState` dobiva `roles: string[]`; postojeći `role` ostaje
- `RequireRole` provjerava **presjek** `roles` i dopuštenih umjesto jednakosti; ponašanje za postojeće ekrane mora ostati identično
- u `src/constants` dodaj `CRM_ROLES`
- nove rute zaštiti s `<RequireRole roles={CRM_ROLES} />`

### API moduli

`src/api/crm/` s modulima `accounts.ts`, `contacts.ts`, `pipelines.ts`, `leads.ts`, `opportunities.ts`, `activities.ts`, `reports.ts`. Tipovi s opcionalnim poljima, pozivi preko `apiClient`.

### Kriterij prihvaćanja

- `npm run lint` i `npm run build` prolaze
- postojeći ekrani rade jednako nakon prelaska na `roles`
- kanban premješta opportunity i prikazuje poruku zahtjeva kad prijelaz padne

---

## Ono što se u ovim fazama ne dira

- `allocation`, `reservation`, `reservation_request` i bilo što u sunbed putu
- `AppUser.Role` i `app_user.role`
- `PlatformRoleMapper.mapHighest` — samo se dodaje nova metoda
- postojeće migracije

Event management, prostori, paketi, ponude i ugovori dolaze u Fazama 2 do 5 i nisu dio ove specifikacije.
