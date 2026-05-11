alter table reservation_request
  add column if not exists linked_opera_reservation_id bigint;

comment on column reservation_request.linked_opera_reservation_id is 'Linked OHIP reservation id for staff-driven INHOUSE/WALKIN posting flows.';
