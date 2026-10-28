-- Event types a catalog offers on the website: codes of the chain's EVENT_TYPE code list (crm_lookup), in the
-- order the website shows them. A catalog without rows asks for no event type.
create table sales_catalog_event_type (
  catalog_id bigint not null references sales_catalog(id) on delete cascade,
  display_order int not null,
  code varchar(40) not null,
  primary key (catalog_id, display_order)
);
