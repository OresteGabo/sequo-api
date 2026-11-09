alter table product_customization_groups add column option_type varchar(32) not null default 'PREPARATION';
alter table product_customization_groups add column measure_unit varchar(16);
alter table product_customization_options add column measure_amount numeric(12,3);
alter table product_customization_groups add constraint chk_product_option_type check (option_type in ('PREPARATION','SIZE','COLOR','MEASURE','OTHER'));
alter table product_customization_groups add constraint chk_product_measure_unit check (
    (option_type = 'MEASURE' and measure_unit in ('KG','G','L','ML','M','CM','UNIT') and measure_unit is not null and min_choices = 1 and max_choices = 1)
    or (option_type <> 'MEASURE' and measure_unit is null)
);
alter table product_customization_options add constraint chk_product_measure_amount check (measure_amount is null or (measure_amount > 0 and measure_amount <= 1000));
alter table customer_order_lines add column option_label text;
