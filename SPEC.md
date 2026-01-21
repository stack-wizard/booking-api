
# Sunbed Booking Model – Product-Driven Time Translation

## Core Idea
- Resources (sunbeds, peninsulas) do NOT define time models.
- Products define:
  - Which resource or resource type they apply to
  - Default and extra UOMs (DAY, HOUR, etc.)
- Price list defines price per (product, UOM).
- System translates (product, UOM, qty) → real time period using service calendar rules.

---

## Resources

### Single Resource
Example:
```json
{
  "resource_id": 12012,
  "type": "SUNBED",
  "code": "A-12"
}
```

### Resource Composition (e.g. Peninsula)
```json
{
  "resource_id": 5001,
  "type": "PENINSULA",
  "components": [
    { "resource_id": 12012, "qty": 1 },
    { "resource_id": 12013, "qty": 1 }
  ]
}
```

Booking a composition means allocating ALL component resources for the same period.

---

## Products

Products define how resources are sold.

```json
{
  "product_id": 700,
  "name": "Sunbed rental",
  "resource_ref": { "kind": "RESOURCE_TYPE", "id": "SUNBED" },
  "default_uom": "DAY",
  "extra_uoms": ["HOUR"]
}
```

---

## Price List

Price is per (product, UOM):

```json
[
  { "product_id": 700, "uom": "HOUR", "price": 8.00, "currency": "EUR" },
  { "product_id": 700, "uom": "DAY",  "price": 35.00, "currency": "EUR" }
]
```

---

## Service Calendar

System must know daily service window:

```
service_schedule(date) -> open_time, close_time
Example: 2026-06-20 -> 09:00 – 19:00
```

Also defines:
- time grid (e.g. 15 or 30 min)
- min/max duration rules

---

## UOM → Period Translation Rules

### UOM = DAY
Calendar service day:
```
start = open(date)
end   = close(date)
```

### UOM = HOUR
Requires start time from user:

```
start = user_start
end   = start + qty hours
validate:
- start >= open(date)
- end   <= close(date)
- round to grid
```

---

## Booking Flow

Input:
- resource_id (or composition id)
- product_id
- uom
- qty
- service_date
- optional start_time

Process:
1) Validate UOM allowed for product
2) Validate price exists for (product, uom)
3) Translate to (start_dt, end_dt)
4) If resource is composition:
   - allocate each component
   Else:
   - allocate single resource
5) Store result on allocation + invoice item

---

## Allocation Record

```json
{
  "allocation_id": 91001,
  "resource_id": 12012,
  "product_id": 700,
  "uom": "HOUR",
  "qty": 3,
  "start": "2026-06-20T10:00:00",
  "end":   "2026-06-20T13:00:00"
}
```

---

## Invoice Item

Always stores translated period:

```json
{
  "invoice_item_id": 30001,
  "product_id": 700,
  "resource_id": 12012,
  "sold_uom": "HOUR",
  "sold_qty": 3,
  "unit_price": 8.00,
  "currency": "EUR",
  "service_date": "2026-06-20",
  "service_start": "2026-06-20T10:00:00",
  "service_end": "2026-06-20T13:00:00",
  "net_amount": 24.00
}
```

---

## Validations

- UOM ∈ product.default_uom + product.extra_uoms
- Price exists for (product, uom, currency, channel, season…)
- Translated period:
  - inside open/close
  - does not cross to next day (for sunbeds)
- Composition: all components must be free

---

## Key Principles

- Product defines how time is sold
- Calendar defines what a “day” means
- Allocation always uses real datetimes
- Compositions are just multi-resource allocations
- No resource-level time model needed
