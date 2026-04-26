create table if not exists tabs (id text primary key, name text not null, position integer not null default 0);
create table if not exists settings (key text primary key, value text);
