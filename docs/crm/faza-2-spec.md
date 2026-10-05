# Implementacijska specifikacija — Faza 2 (prostori)

Migracija **V94**. Oslanja se na postojeći `resource` / `booking_calendar` / `uom` engine — ne uvodi novi availability motor.

## Što je dodano

- globalni `resource_type`: `MEETING_ROOM`, `MEETING_OFFICE`
- `uom`: `MINUTE` (HOUR/DAY već postoje od V23)
- `function_space` — 1:1 s `resource`, atributi prostora (površina, kat, svjetlo, pregradivost, min trajanje, default setup)
- `function_space_setup` — kapacitet po setup styleu (`THEATRE`, `CLASSROOM`, …)

## API

- `GET/POST /api/crm/function-spaces`
- `GET/PUT/DELETE /api/crm/function-spaces/{id}` (DELETE = soft via `active=false`)
- `GET/POST /api/crm/function-spaces/{id}/setups`
- `PUT/DELETE /api/crm/function-spaces/setups/{setupId}`
- `GET /api/crm/function-spaces/setups/by-capacity?minPax=`

`resourceId` mora pripadati tenantu i tipu `MEETING_ROOM` ili `MEETING_OFFICE`. Resource tip se ne mijenja nakon kreiranja function spacea.

## Admin

`resourceConfigs` unos `function-spaces` + stavka u sidebaru. Setup stilove trenutno upravljaj preko API-ja (nije zasebna EntityPage jer je nested).

## Svjesno izvan ove faze

Event header, reservation.event_function_id, paketi, BEO — Faza 3+.
