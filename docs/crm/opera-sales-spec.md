# Sales ↔ OPERA Cloud

Status: lokalni model je implementiran (booking, request po hotelu, room blockovi, fit-finder, statusi, mapiranje kodova). Opera-led tok je implementiran (V125): setup iz Opere, blok po hotelu, statusi i prijelazi provjereni i spušteni u Operu, functioni kao Opera eventi (Simple Events), catering i najam postirani na PM bloka. Primanje promjena iz Opere još nije.

## Opera-led tok (Simple Events)

Pretpostavka: hotel ima OPERA Simple Events licencu, dakle Opera nema catering — catering vodimo mi i postiramo ga kao chargeove na PM bloka.

1. **Setup** `POST /api/opera/hotels/{id}/setup` (admin: "Setup from OPERA"):
   uvoz kodova → sync tipova soba (POOL `ROOM` + ROOM_TYPE map) → FUNCTION_SPACE kodovi postaju `MEETING_ROOM` EXACT resursi s mapom → EVENT_TYPE i SETUP_STYLE se poravnaju u `crm_lookup` lanca (samo Opera kodovi ostaju aktivni) → uvoz statusa bloka i prijelaza. Ponovni poziv osvježava. Upozorenja: nema cashiera, nema default charge mappinga, `space_inventory = LOCAL`.
2. **Statusi** su Opera statusi. Nakon uvoza svaki vlastiti status mora imati `opera_code` iste faze; prijelaz smije postojati samo ako ga Opera dopušta (`OperaStatusGraph`). Status s Opera kodom bez prijelaza je završni. Osvježavanje (`alignWithOpera`) deaktivira statuse bez živog Opera koda i briše prijelaze koje Opera ne dopušta.
3. **Blok** nastaje pri prvoj smještenoj potrebi u hotelu (room block na tipu sobe ili function sa spaceom), u najbližem početnom Opera statusu prema ciljnom. Raspon bloka = datumi bookinga proširen noćima i datumima functiona; kasnije se samo širi.
4. **Prijelaz statusa** bookinga: prvo `PUT .../status` s `verificationOnly = true` za svaki blok (Opera odbije → 409, lokalno se ništa ne mijenja), zatim lokalna promjena, pa se blok vodi Opera putanjom (BFS kroz `next`) do ciljnog koda. Status koji vraća inventar šalje `cancelAllPMReservations`.
5. **Room grid** se šalje od faze nakon INQUIRY; `allocationType` je `Actual` kad status skida inventar (DeductInv/Actual), inače `Initial`.
6. **Eventi**: `booking_function` sa spaceom → `POST /evm/v1/events` na blok (`opera_event_id`), promjena → `PUT`, brisanje ili micanje spacea → `DELETE`. `event_type` je obavezan (EVENT_TYPE kod), setup style opcionalan.
7. **PM posting** `POST /api/bookings/{id}/opera/post-charges` (tab OPERA na bookingu): samo za hotele s `billing = OPERA` i blok u statusu koji dopušta pickup. PM se otvara/stvara ako ne postoji. Šalje se razlika po stavci cateringa i po najmu functiona prema već postiranom; obrisana stavka dobiva storno. Trx kod iz `opera_fiscal_charge_mapping`; bez njega se ne postira ništa. Svaki redak se pamti u `booking_opera_posting` (POSTED/FAILED).

## Pojmovi

| OPERA | Kod nas |
| --- | --- |
| Block (`blockId`, `blockCode`), jedan hotel | `booking` (može obuhvatiti više hotela lanca); u svakom hotelu jedan `reservation_request` s `booking_id` |
| Event na bloku (space × vrijeme), Simple Events ili Catering Events, isti `/evm` API | `booking_function` |
| Room grid bloka (tip sobe × datum × broj, cijena) | `booking_room_block` + `booking_room_block_night` (kvota po noći) |
| Ćelija room grida (tip sobe × noć) | jedan `reservation` redak s `units` = kvota, na POOL resursu tipa sobe |
| Rezervacije soba na bloku (pickup, rooming list) | nema, samo zrcalo `opera_reservation_ref` |
| Posting Master (PM) rezervacija bloka | cilj postinga stavki bookinga |
| Block status (konfigurabilan, globalno za enterprise; tip Inquiry / Non-deduct / Deduct / Lost / Cancel) | faza `Booking.Status` + konfigurabilni status (`booking_status`) |
| Rate code | oznaka; iznos šaljemo mi |

OHIP pozivi koji su nam potrebni:

- `POST /blk/v1/hotels/{h}/block`
- `PUT .../blocks/{id}/allocationRange`
- `PUT .../blocks/{id}/status` (prvo s `verificationOnly`)
- `getNextBlockStatus`
- `blk/config/v1/blockStatusCodes`
- `getBlockPMReservations` i `postBlockPostingMaster` (preko API-ja se PM ne kreira automatski)
- `/evm/v1/events`, `getAvailableSpaces`
- `chargesAndPayments`, koji već koristimo
- Streaming API (business eventi) ili polling za promjene iz Opere

## Odluke

1. **Mod po hotelu**: na `opera_hotel` idu tri zastavice:
   - `space_inventory` `LOCAL|OPERA`: kod `OPERA` space se ne zauzima u `allocation`, nego u Operi. Semantika `allocation` se ne mijenja. Resurs se tada ne smije bukirati lokalno.
   - `room_inventory` `LOCAL|OPERA`: kod `OPERA` room block ne piše lokalni allocation; `OperaBlockSyncService` šalje blok + room grid (`booking_opera_block`).
   - `billing` `LOCAL|OPERA`.
2. **Opera je nositelj računa** (`billing = OPERA`).
   - Mi izdajemo ponudu i BEO, Opera izdaje račun i fiskalizira.
   - Za te bookinge se ne pokreću naš `InvoiceService` ni fiskalizacija.
   - Depoziti i plaćanja vode se u Operi.
3. **Cijenu određujemo mi.**
   - Rate code u Operi dopušta našu cijenu (`fixedRate: true`).
   - `price_profile` ↔ rate code mapiranje (po hotelu) određuje samo koji kod šaljemo.
   - Iznos dolazi iz našeg cjenika ili iz dogovorene cijene na `booking_room_block.rate_price_profile_id`.
   - Najam spacea iz cjenika ide kao iznos najma na Opera event.
   - Za razliku od check-ina, ovdje se šalje stvarni iznos, ne `0` uz `suppressRate`.
4. **Jedan blok po hotelu** = jedan `reservation_request` bookinga u tom hotelu (V121, unique `(booking_id, tenant_id)`).
   - Request nastaje automatski s prvom smještenom potrebom u hotelu i briše se kad zadnja ode drugamo. Tip je `INTERNAL`, bez isteka i bez plaćanja.
   - Status requesta prati fazu: `DRAFT` do DEFINITE, `FINALIZED` od DEFINITE, `CANCELLED` kad se booking izgubi.
   - Guest tokovi (plaćanje, istek, finalize, brisanje, otkaz, check-in s RR ekrana) odbijaju booking-requestove s 409.
   - Hotel s `space_inventory = OPERA`: retci nastaju bez lokalnog `allocation`a, a request dobiva `opera_sync_status = PENDING` (V122). Sam blok sync je sljedeći korak: `booking_opera_block(booking_id, opera_hotel_id, block_id, block_code, status_code, pm_reservation_id, sync_status, last_error, synced_at)`.
   - Booking s functionima ili sobama u više hotela dobiva jedan blok u svakom od tih hotela.
5. **Jedan PM po bloku.**
   - Stavka ide na PM bloka u hotelu svog functiona (`booking_function.tenant_id`).
   - Isto vrijedi za najam functiona.
   - Room grid ide na blok u hotelu room blocka (`booking_room_block.property_tenant_id`).
6. **Posting** se pokreće na tri načina:
   - ručno, gumbom "Post to Opera" na bookingu
   - ulaskom opportunityja u stage s `posts_to_opera = true`
   - prelaskom bookinga u `ACTUAL`, ako stage to tako definira
   - Postira se samo ono što još nije postirano.
   - Ako se stavka promijeni nakon postinga, šalje se razlika kao nova charge stavka. Ništa se ne briše.
   - Praćenje: `booking_opera_posting(booking_function_id, booking_function_item_id null, kind RENTAL|ITEM, opera_hotel_id, pm_reservation_id, amount, trx_code, status, posted_at, request, response, error)`.
   - Transaction code dolazi iz postojećeg `opera_fiscal_charge_mapping`.
7. **Stage može postaviti status bookinga**: `crm_pipeline_stage.booking_status_code` (nullable) uz `posts_to_opera`.
8. **Statusi bookinga** imaju dva sloja:
   - Faza je fiksni enum, o njoj ovisi logika.
   - Status je konfigurabilan, na razini lanca: `booking_status(code, label, color, phase, is_start, active, opera_code)` i `booking_status_flow(from, to)` (V117, preimenovano u V120). Zauzimanje kapaciteta ide po fazi, status ga ne mijenja.
   - `booking.status_code` drži status; `booking_status_history` pamti i `from_code` / `to_code`.
   - Svaki prijelaz mora poštovati pravila faza. Status s konfiguriranim prijelazima dopušta samo njih; status bez njih (stari zapisi) pada na pravila faza.
   - Provjere faze (decision date, barem jedna smještena potreba, prihvaćena ponuda, actualPax, razlog) vrijede samo kad se faza mijenja. Smještena potreba je function sa spaceom ili room block na tipu sobe.
   - Tenant s Operom uvozi statuse iz `blockStatusCodes/nextStatusCodes`. Fazu predložimo po `cateringStatusType` (inače `roomStatusType`), prijelaze preuzmemo iz Opere gdje ih faze dopuštaju.
   - Tenant bez Opere dobiva sedam zadanih statusa (kod = ime faze), lijeno pri prvom čitanju.
   - Stage s `booking_status_code` (samo INQUIRY–ACTUAL faze) pomiče otvorene bookinge deala; nedopušten prijelaz vraća 409 i stage se ne mijenja.
9. **Vrsta bookinga se izvodi iz potreba**, nema stupca `kind`: functioni = event, room blockovi = grupa, oboje = konferencija s noćenjem, jedan function bez cateringa = najam. Novi booking bira predložak (Event, Group accommodation, Space rental, Blank) koji samo doda početne potrebe. `reservation_request.type` ostaje kanal i ne dira se.
   - Room block: kvota po tipu sobe i noći. Smješten je kad ima `room_resource_id` (POOL resurs tipa `ROOM` ili `APARTMENT` s `pool_total_units`). Svaka noć je jedan `reservation` redak (15:00 → 11:00 sutradan, `qty 1`, `units` = kvota); promjena kvote mijenja `units` istog retka, kvota 0 ga otpušta.
   - Fit-finder `GET /api/bookings/{id}/fit` za svaki hotel lanca i svaku potrebu daje kandidate (sale po pax i setupu, sobe po slobodnim jedinicama po noći); vlastiti retci bookinga se ne broje kao zauzeće.
   - Dostupnost preko lanca: `GET /api/bookings/space-grid?scope=chain` i `GET /api/bookings/room-grid?scope=hotel|chain`.
10. **Mapiranje kodova**: `opera_code_map(tenant_id, opera_hotel_id, kind, local_ref, opera_code)`.
    - Mapiranje (V118, V122): FUNCTION_SPACE (resource), SETUP_STYLE i EVENT_TYPE (`crm_lookup`), RATE_CODE (price profile), ROOM_TYPE (POOL resurs tipa sobe). MARKET, SOURCE, RESERVATION_TYPE dolaze s blok syncom.
    - Opera kodovi se uvoze iz config API-ja u `opera_code` (BLOCK_STATUS, EVENT_TYPE, SETUP_STYLE, FUNCTION_SPACE, MARKET, SOURCE, GUARANTEE, ROOM_TYPE, RATE_CODE); kod kojeg više nema postaje neaktivan.
    - `opera_hotel.space_inventory` i `billing` (LOCAL | OPERA) biraju tko vodi spaceove i račun.
    - Uz to idu `crm_account.opera_profile_id` i `crm_contact.opera_profile_id`.

## Smjer podataka

- **Naš sustav → Opera**: blok, functioni (Opera eventi), room grid, statusi, posting. CRM (account, opportunity, ponuda, stavke, cijene) je kod nas.
- **Opera → naš sustav**: inventar, pickup rezervacija, promjene napravljene u Operi.
  - Promjenu spacea ili statusa iz Opere prikazujemo kao upozorenje. Ne pregazimo podatke tiho.
  - Razliku u cijeni rezervacije prema našem iznosu označimo.

## Redoslijed

1. ✅ Proširiti OHIP klijent (config), dodati `opera_code_map` s uvozom kodova i admin ekranom, zastavice na `opera_hotel` i profile.
2. ✅ Konfigurabilni statusi bookinga (`booking_status`, flow, uvoz iz Opere).
3. ✅ Lokalni model: booking = blok, request po hotelu, room blockovi, fit-finder, dostupnost preko lanca (V119–V122).
4. ✅ Sinkronizacija bloka, statusa i functiona (Opera eventi), room grid prema Operi (V125). Zrcalo `opera_reservation_ref` još nije.
5. ✅ Ručni posting na PM. Posting po stageu još nije.
6. Primanje promjena iz Opere i dnevno usklađivanje.

## Kasnije

- **Računi iz Opere**: povući broj, iznos i status računa s PM-a i foliova bloka za prikaz u CRM-u (revenue po accountu i bookingu).
- Cancel statusi: šalje se samo tekst otkaza, ne i reason code (ako ga hotel traži, prijelaz pada).
- Combo function spaceovi (npr. GR) uvoze se kao obične sale, bez veze na dijelove.
- Block code predložak na DH je `YYMMAAAAAA` (godina, mjesec, 6 slova).
- Setup style mora biti dopušten na toj sali u Operi (npr. SALA1 prima USHAPE, ne THEATER); pri odbijanju se retry bez setupa.
- Function-space rental na eventu je `opera_hotel.default_rental_code` (Setup stavlja `CUSTOM`) + `rentalAmount`, ne room `RATE_CODE` (inače EVM01033).
- Setup from OPERA također mapira EVENT_TYPE/SETUP_STYLE 1:1 i nemapirane price profile na RATE1 (ili prvi RACK).
- `applyChangesToCateringSatus` je `false` (Simple Events, inače BLK01136).
- Produkti i combo produkti za catering menije.
- **Pickup gosta**: rooming list i grupni check-in. Gost dobiva vlastiti redak (i po potrebi vlastiti request), a `units` noći bloka se smanjuje.
- **Auto-opportunity** za booking stvoren bez opportunityja.
- **Cijena sobe iz `rate_price_profile_id`**: danas se noć cijeni iz INTERNAL cjenika proizvoda tipa sobe, ili 0 dok cijena nije dogovorena.
- Vrijeme check-ina i check-outa po hotelu (danas fiksno 15:00 / 11:00).
- Vrsta bookinga (izvedena) na panelu opportunityja.
- Razdvajanje stavki po folio windowima (F&B, najam, sobe) umjesto jednog PM-a.
- Opera Event Resources (meni i artikli) umjesto chargeova, ako zatreba.
- Master i sub blokovi za bookinge u više hotela.

## Otvoreno s klijentom

- Opera edition (SEM Essentials ili Standard) i kontrola "Use Single Block Status". Treba i stvarna lista statusa.
- Smije li se blok ili event mijenjati direktno u Operi?
- Koji rate code (ili kodovi) za grupe, i je li porez uključen u iznos (`amountBeforeTax` ili `amountAfterTax`)?
- Rate code na zaglavlju bloka uz naše grid cijene (provjeriti na sandboxu) ili blok bez rate codea i kod na rezervacijama.
- Odobrenje Streaming API pretplate i popis business eventa za blokove.
- Izlaže li OHIP Simple Events kroz isti `/evm` API (provjeriti na sandboxu).
