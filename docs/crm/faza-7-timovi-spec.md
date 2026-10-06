# Faza 7 — prodajni timovi, vidljivost, dodjela leadova, relacije accounta

Migracije: V106 (`crm_team_scope`), V107 (`crm_segment_assignment`), V108 (`crm_account_relation`).

## Uloge i vidljivost

| Tko | Kako se dobiva | Vidi |
| --- | --- | --- |
| Sales rep | Platform rola `SALES_REP` / `SALES_MANAGER` | samo zapise kojima je vlasnik |
| Team lead | `crm_team_member.team_lead = true` na aktivnom članstvu | svoje + sve zapise vlasnika iz timova koje vodi **i podtimova**, + zapise s `team_id` tih timova (i one bez vlasnika — red čekanja) |
| Event coordinator | rola, katalog scope `TEAM` | kao lead, ali za timove u kojima je član |
| Admin / Revenue manager | rola | sve |

`SALES_MANAGER` više ne daje automatski TEAM scope — leader je onaj tko ima `team_lead` u timu. Time jedan čovjek može voditi
jedan tim, a u drugom biti običan član.

Pravilo vrijedi za leadove, accounte, opportunityje i evente (`findScoped` / `search`) te za alerte (Sales Inbox).
`CrmAccessContext` računa vidljivost jednom po zahtjevu (`CrmTeamTree.resolve`).

## Članstvo kroz vrijeme

`crm_team_member.valid_from` (uključivo) / `valid_to` (isključivo). Aktivno = `valid_from <= danas < valid_to`.

- dodavanje: `valid_from` default danas; najviše jedno otvoreno članstvo po (tim, korisnik)
- "End membership" (`DELETE /api/crm/teams/members/{id}`) postavlja `valid_to = danas`; povijest ostaje.
  Ako članstvo još nije počelo, red se briše.
- `PUT /api/crm/teams/members/{id}` mijenja `teamLead`, `validFrom`, `validTo`
- novi zaposlenik: dodaj ga u tim s datumom početka; odmah ulazi u round robin / least loaded

## Vlasnik i tim na zapisu

Svaki lead / account / opportunity / event nosi `owner_user_id` i `team_id`.

- kreiranje bez `teamId`: primarni (najstariji aktivni) tim vlasnika; account dodatno pada na `default_team_id` segmenta
- opportunity bez tima: tim vlasnika, inače tim accounta; event: tim opportunityja, inače vlasnika, inače accounta
- konverzija leada: account i opportunity nasljeđuju vlasnika, tim, segment i državu leada
- promjena vlasnika bez `teamId`: tim novog vlasnika
- tko smije dodijeliti: rep samo sebi (u svom timu); team lead članovima i timovima koje vodi; ALL scope bilo kome (inače 403)

## Segmenti

`crm_segment (code, name, default_team_id, active, display_order)`, API `/api/crm/segments` (čitanje `ACCOUNT_READ`,
pisanje `PIPELINE_CONFIG`). Dok tenant nema segmenata, `segment` je slobodan tekst (stari podaci rade). Čim postoji
aktivan segment, account/lead/pravilo mora koristiti postojeći kod; vrijednost se normalizira na kanonski kod.
Kod se ne mijenja nakon kreiranja (accounti ga referenciraju tekstom).

## Dodjela novih leadova

Kad lead stigne **bez vlasnika i tima** (`POST /api/crm/leads`), `CrmLeadAssignmentService`:

1. **postojeći klijent** — kontakt s istim emailom ili aktivan account s istim imenom → vlasnik i tim accounta
   (ako vlasnik više nije ni u jednom timu: least loaded u timu accounta)
2. **pravila** `crm_assignment_rule` po `priority` (manji prvi); prazni kriteriji = bilo što.
   Kriteriji: `segment`, `country` (ISO 2), `source`, `inquiry_type` (`attrs.inquiryType`), `event_type`
   (`attrs.eventType`), `min_pax`/`max_pax` (`attrs.pax`). Prvo pravilo koje se poklopi bira tim i strategiju:
   - `ROUND_ROBIN` — sljedeći aktivni član po redu (zaključava pravilo, pamti `last_assigned_user_id`)
   - `LEAST_LOADED` — član s najmanje otvorenih leadova (NEW/WORKING/QUALIFIED)
   - `FIXED_USER` — uvijek isti rep
   - `QUEUE` — bez vlasnika; lead čeka u redu tima, team lead ga dodjeljuje
   Team leadovi ulaze u rotaciju samo ako tim nema drugih članova.
3. **segment** s `default_team_id` → least loaded u tom timu
4. inače vlasnik je onaj tko je unio lead

Lead pamti `assignment_rule_id` i `assigned_at`. Ručno: `POST /api/crm/leads/{id}/assign {ownerUserId, teamId}`
(bez vlasnika = red tima), ponovno po pravilima: `POST /api/crm/leads/{id}/auto-assign`. Pravila: CRUD na
`/api/crm/assignment-rules` (pisanje `PIPELINE_CONFIG`).

## Preraspodjela (odlazak, bolovanje, promjena tima)

`GET /api/crm/reassign/preview?fromUserId=` broji otvoreni posao; `POST /api/crm/reassign`:

```json
{ "fromUserId": 12, "toUserId": 15, "toTeamId": null, "includeLeads": true, "includeOpportunities": true,
  "includeAccounts": true, "includeEvents": true, "endMemberships": true, "note": "Left the company" }
```

Seli otvorene leadove, OPEN opportunityje, aktivne accounte i evente INQUIRY/TENTATIVE/DEFINITE koji još nisu prošli.
Zatvoreni zapisi zadržavaju izvornog vlasnika (izvještaji ostaju točni). Bez `toUserId` u red tima idu samo leadovi.
Smije ALL scope ili team lead osobe; `endMemberships` traži `PIPELINE_CONFIG`. Svaki run se logira u `crm_reassignment`
(`GET /api/crm/reassign/history`).

## Relacije accounta

- hijerarhija: `crm_account.parent_account_id` (grupa → članice); zaštita od ciklusa
- `crm_account_relation (from, to, relation_type)`: `AGENCY_FOR` (from mora biti tip AGENCY), `PARTNER` (deduplicira
  oba smjera), `SUPPLIER`, `OTHER`
- `GET /api/crm/accounts/{id}/relations` vraća roditelja, djecu i tipizirane veze s imenom i smjerom;
  `POST /api/crm/accounts/{id}/relations`, `DELETE /api/crm/accounts/relations/{id}`
- `crm_opportunity.agency_account_id` — agencija preko koje je posao došao (mora biti AGENCY)

## Pomoćni endpointi

- `GET /api/crm/teams/users` — korisnici za odabir vlasnika/člana (s aktivnim timovima)
- `GET /api/crm/teams/my-scope` — scope, timovi koje vodim, vidljivi timovi i korisnici

## Admin

- **Sales Teams** (`/crm/teams`): stablo timova, članovi s lead zastavicom i periodom, kraj članstva, "Reassign work";
  kartice Segments, Lead assignment, Reassignments
- **CRM Leads**: stupac Team, "Queue" za nedodijeljene, filter "Unassigned queue", klik na vlasnika = dodjela
- **Lead wizard**: Segment, Country
- **CRM Accounts → Open**: detalj accounta s relacijama i kontaktima
- **Opportunity**: "Booked via agency"

## Ograničenja

- Super admin s Platforme nema tenant; u timove se može dodati samo sam (ili ako je već član).
- Vidljivost vlasnika je tranzitivna kroz članstvo: lead tima A vidi i zapise svog člana koji su u timu B.
