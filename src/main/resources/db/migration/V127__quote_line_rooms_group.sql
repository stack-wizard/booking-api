-- Quotes can list room-block accommodation separately from meeting / F&B / extras.
alter table sales_quote_line drop constraint sales_quote_line_line_group_check;
alter table sales_quote_line add constraint sales_quote_line_line_group_check
  check (line_group in ('MEETING', 'ROOMS', 'F_AND_B', 'AV', 'EXTRAS', 'DISCOUNT'));
