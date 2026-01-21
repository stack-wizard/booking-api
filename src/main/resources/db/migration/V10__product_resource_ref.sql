alter table product
  add column resource_id bigint null references resource(id) on delete restrict;

update product
set resource_id = null;

alter table product
  drop column resource_ref_kind,
  drop column resource_ref_id;
