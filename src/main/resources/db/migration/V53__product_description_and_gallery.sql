alter table product
  add column if not exists description text null;

create table if not exists product_image (
  id            bigserial primary key,
  product_id    bigint not null references product(id) on delete cascade,
  image_url     text not null,
  detail_image  boolean not null default false,
  sort_order    integer not null default 0,
  created_at    timestamptz not null default now()
);

create index if not exists idx_product_image_product
  on product_image(product_id, sort_order, id);

create unique index if not exists uq_product_image_detail_per_product
  on product_image(product_id)
  where detail_image;
