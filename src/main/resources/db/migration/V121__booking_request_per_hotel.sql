-- A booking reaches each hotel through one reservation_request; every reservation line now has a request.
-- The request is created and kept in step by the booking (type INTERNAL, no expiry, no payment flow).
alter table reservation_request
  add column booking_id bigint null references booking(id) on delete cascade;

create index idx_reservation_request_booking on reservation_request(booking_id) where booking_id is not null;
create unique index uq_reservation_request_booking_hotel
  on reservation_request(booking_id, tenant_id) where booking_id is not null;

alter table reservation drop constraint reservation_owner_check;

insert into reservation_request (tenant_id, type, status, expires_at, customer_name, customer_email, customer_phone,
                                 extension_count, confirmed_at, booking_id)
select distinct on (b.id, r.tenant_id)
       r.tenant_id,
       'INTERNAL',
       case
         when b.status in ('DEFINITE', 'ACTUAL') then 'FINALIZED'
         when b.status in ('TURNED_DOWN', 'LOST', 'CANCELLED') then 'CANCELLED'
         else 'DRAFT'
       end,
       null,
       coalesce(a.name, b.name),
       a.email,
       a.phone,
       0,
       case when b.status in ('DEFINITE', 'ACTUAL') then now() end,
       b.id
from reservation r
join booking_function f on f.id = r.booking_function_id
join booking b on b.id = f.booking_id
left join crm_account a on a.id = b.account_id
where r.request_id is null
order by b.id, r.tenant_id;

update reservation r
set request_id = q.id
from booking_function f, reservation_request q
where f.id = r.booking_function_id
  and q.booking_id = f.booking_id
  and q.tenant_id = r.tenant_id
  and r.request_id is null;

alter table reservation alter column request_id set not null;
