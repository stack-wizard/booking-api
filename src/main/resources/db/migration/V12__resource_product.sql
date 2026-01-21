alter table resource
  add column product_id bigint null references product(id) on delete set null;
