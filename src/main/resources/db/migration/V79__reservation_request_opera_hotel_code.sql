alter table reservation_request
  add column if not exists opera_hotel_code varchar(32);

comment on column reservation_request.opera_hotel_code is 'OHIP hotel id for staff flows (e.g. WALKIN PMS search); optional override of tenant default';
