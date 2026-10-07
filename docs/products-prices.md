# Proizvodi i cijene (jedinstveni ekran)

Admin `/products` objedinjuje pojedinačne proizvode i pakete (`/packages` preusmjerava na `/products?type=PACKAGE`).
Vidljiv je i back-office ulogama (`MANAGEMENT_ROLES`) i CRM ulogama.

Kartice odabranog proizvoda:

- **Details**: naziv, tip, default i dodatni UOM, porezi, redoslijed, opis, galerija slika (`PUT /api/products/{id}`, `POST /api/products`).
- **Prices**: cijena danas po UOM-u i cjenik grupiran po razdoblju cjenovnog profila (`price_profile_date`).
  Uređivanje u retku (dodaj / promijeni / obriši), "Adjust %" i "Copy to period".
- **Package / Make package**: komponente, provjera cijene paketa i web listing (postojeći endpointi paketa).

Profili i razdoblja i dalje se održavaju na ekranu **Pricing**; ekran proizvoda samo veže cijene na postojeća razdoblja.

## API

| Metoda | Putanja | Opis |
|---|---|---|
| GET | `/api/products/price-periods` | razdoblja svih profila tenanta |
| GET | `/api/products/{id}/prices` | cijene proizvoda s razdobljem |
| PUT | `/api/products/{id}/prices` | `{upserts: [...], deleteIds: [...]}` |
| POST | `/api/products/{id}/prices/adjust` | `{percent, priceProfileDateIds?, uom?, roundTo?}` |
| POST | `/api/products/{id}/prices/copy` | `{fromPeriodId, toPeriodId, percent?, roundTo?}` |

Pravila (`ProductPriceService`):

- `uom` mora biti default ili dodatni UOM proizvoda; cijena ≥ 0, zaokružena na 2 decimale.
- Vremenski slot: ili oba `startTime`/`endTime` ili nijedan, i `start < end`.
- Jedna cijena po (razdoblje, UOM, slot) — duplikat je 400.
- `price_profile_id` se uvijek postavlja iz razdoblja (cjenovni engine spaja oba).
- Promjena %: od -90 do +500, zaokruživanje HALF_UP na `roundTo`.
- Kopiranje preskače slotove koji u ciljnom razdoblju već postoje; ako nema ničega za kopirati → 409.
- Pisanje: SUPER_ADMIN/ADMIN, CRM REVENUE_MANAGER ili STAFF bez prodajne CRM uloge; ostali dobivaju 403.

Dev seed (`scripts/dev/seed-events.sql`) dodaje prazno razdoblje `EVENTS 2028` za kopiranje sezone.
