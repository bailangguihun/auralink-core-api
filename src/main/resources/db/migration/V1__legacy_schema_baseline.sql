-- Auralink inherited schema baseline.
--
-- Clean databases execute this migration to create the two legacy tables.
-- Existing inherited databases must be explicitly baselined at version 1 so
-- Flyway records this version without executing it against those tables.
-- Keep this definition structurally faithful to the inherited SQLite schema.

CREATE TABLE users (
    id integer,
    account_non_expired boolean NOT NULL,
    account_non_locked boolean NOT NULL,
    created_at timestamp,
    credentials_non_expired boolean NOT NULL,
    email varchar(255) NOT NULL UNIQUE,
    enabled boolean NOT NULL,
    full_name varchar(255) NOT NULL,
    password varchar(255) NOT NULL,
    role varchar(255) NOT NULL,
    updated_at timestamp,
    username varchar(255) NOT NULL UNIQUE,
    PRIMARY KEY (id)
);

CREATE TABLE generation_logs (
    id integer,
    api_provider varchar(255),
    api_source varchar(255) NOT NULL,
    created_at timestamp NOT NULL,
    description varchar(1024),
    duration integer,
    error_message varchar(1024),
    image_url varchar(1024),
    input_data TEXT,
    metadata TEXT,
    model_size varchar(255) NOT NULL,
    output_data TEXT,
    processing_time_ms bigint,
    result_url varchar(1024),
    success boolean NOT NULL,
    task_type varchar(255) NOT NULL,
    use_fast_generate boolean NOT NULL,
    user_id bigint NOT NULL,
    PRIMARY KEY (id)
);
