create index if not exists idx_products_active_category_created_at
    on products (category, created_at desc)
    where status = 'ACTIVE' and category is not null;
