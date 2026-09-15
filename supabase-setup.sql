create table if not exists public.shiguang_items (
  collection text not null,
  id text not null,
  data jsonb not null,
  updated bigint not null,
  deleted boolean not null default false,
  primary key (collection, id)
);

alter table public.shiguang_items enable row level security;

create policy "shiguang_sync_key_access"
  on public.shiguang_items
  for all
  using (true)
  with check (true);
