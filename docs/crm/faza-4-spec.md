# Implementacijska specifikacija — Faza 4 (paketi)

Migracija **V100**. Ovisi o Fazi 3 (`event_function`, `event_function_item`).

## Stanje implementacije (odstupanja od nacrta niže)

- `product_component.qty` je `integer`; `function_type` ima check na iste vrijednosti kao `event_function`.
- **Cijena paketa je po osobi.** `fixed_amount` i udio su iznos po osobi za cijelu komponentu (ne množe se s `qty`); razlika = Σ uključenih po osobi − cijena paketa po osobi.
- Servis je `PackageService` (`define`, `quote`), endpointi `GET /api/products/{id}/components`, `PUT /api/products/{id}/package {packagePricing, components}` (null način = običan proizvod), `GET /api/products/{id}/package-price?date=&pax=&currency=`. `packagePricing` se postavlja samo kroz taj endpoint, generički product PUT ga ne dira.
- **Primjena** (`EventPackageService.apply`): komponenta s `function_type` generira funkciju, a njezina stavka ide na tu funkciju; ostale stavke idu na funkciju koja pokriva `serve_at`, inače na prvu. Plenarna / breakout / izložbena funkcija dobiva ime paketa, obroci i pauze ime komponente. Prostor iz zahtjeva dobiva samo funkcija koja se ne preklapa s ranije generiranom. Bez ijedne komponente s tipom nastaje jedna PLENARY funkcija.
- Funkcije paketa imaju **najam 0** (linija postoji radi kapaciteta), a cijenu nosi stavka komponente — nema dvostruke naplate prostora.
- `PER_PAX` s qty 1 → `PER_GUARANTEED_PAX`; ostalo `FIXED` s qty iz quotea.
- Admin: zasebna stranica `/packages` (popis proizvoda, način cijene, komponente, *Price check* s razlikom), ne tab na Products.

Paket je `product`, ne nova tablica. `product.product_type` je fiskalna klasifikacija (SEALABLE_PRODUCT / DEPOSIT / DEPOSIT_STORNO / PENALTY) po kojoj se mapiraju Opera charge kodovi — paket ne ide tamo.

## V100

```sql
alter table product add column package_pricing text
  check (package_pricing in ('SUM','SPLIT_PERCENT','SPLIT_FIXED'));

create table product_component (
  id bigserial primary key,
  tenant_id bigint not null,
  package_product_id bigint not null references product(id) on delete cascade,
  component_product_id bigint not null references product(id) on delete restrict,
  qty numeric(10,2) not null default 1 check (qty > 0),
  qty_basis text not null default 'PER_PAX' check (qty_basis in ('FIXED','PER_PAX')),
  included boolean not null default true,
  share_percent numeric(5,2) check (share_percent >= 0 and share_percent <= 100),
  fixed_amount numeric(12,2) check (fixed_amount >= 0),
  function_type text,
  start_offset_minutes integer,
  duration_minutes integer check (duration_minutes > 0),
  display_order integer not null default 0,
  created_at timestamptz not null default now(),
  check (package_product_id <> component_product_id)
);
create index idx_product_component_package on product_component (package_product_id);
create index idx_product_component_component on product_component (component_product_id);
create index idx_product_component_tenant on product_component (tenant_id);
```

`package_pricing` null = običan proizvod.

## Načini cijene

- **SUM** — paket nema vlastitu cijenu; cijena = Σ (cijena komponente iz `price_list` × qty). `share_percent` i `fixed_amount` moraju biti null.
- **SPLIT_PERCENT** — paket ima cijenu u `price_list`; uključene komponente nose `share_percent`, zbroj = 100. Iznos komponente = cijena paketa × udio.
- **SPLIT_FIXED** — paket ima cijenu u `price_list`; uključene komponente nose `fixed_amount`. Servis vraća razliku Σ komponenti − cijena paketa; razlika ≠ 0 je upozorenje u adminu i blokira primjenu paketa na event (409).

Pravila (`PackageService`):

- komponenta ne smije biti paket (nema ugniježđenja)
- `included = false` je nadoplata: ne ulazi u raspodjelu, cijena iz vlastitog `price_list`
- uom paketa je podatak (npr. `UNIT` po osobi) — ništa se ne tvrdo kodira
- `PackagePricingService.price(packageId, date, pax)` vraća breakdown `{componentProductId, qty, unitPrice, amount, tax1Percent, tax2Percent}`

## Primjena na event

`POST /api/events/{id}/apply-package {packageProductId, date, resourceId, startsAt, pax}`:

- komponente s `function_type` generiraju `event_function` (prostor iz zahtjeva, vrijeme iz `start_offset_minutes` i `duration_minutes`)
- ostale komponente postaju `event_function_item` s cijenom iz breakdowna i `qty_basis` `PER_PAX` → `PER_GUARANTEED_PAX`
- generirano pamti `package_product_id` samo za izvještavanje; paket je predložak, ne živa veza — daljnje izmjene su obične
- na računu eventa svaka komponenta je zasebna linija s vlastitim PDV-om i Opera charge mappingom

## API i admin

- `GET/PUT /api/products/{id}/components`, `GET /api/products/{id}/package-price?date=&pax=`
- Products: tab *Package* — način cijene, komponente, živi zbroj i razlika naspram cijene paketa
- Event: *Apply package* s pregledom breakdowna prije primjene
- dev seed: *Day Delegate Rate* (SPLIT_PERCENT), *Half-day DDR* (SUM)

## Testovi

Tri načina cijene, validacija zbroja postotaka i fiksnih iznosa, zabrana ugniježđenja, nadoplate, generiranje funkcija i stavki, linije računa po komponenti.
