do $$
begin
  if exists (
    select 1
    from information_schema.columns
    where table_name = 'product_image'
      and column_name = 'detail_image'
  ) and not exists (
    select 1
    from information_schema.columns
    where table_name = 'product_image'
      and column_name = 'default_image'
  ) then
    alter table product_image
      rename column detail_image to default_image;
  end if;
end $$;

drop index if exists uq_product_image_detail_per_product;

create unique index if not exists uq_product_image_default_per_product
  on product_image(product_id)
  where default_image;
