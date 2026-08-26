-- Sanitized inherited-schema fixture for portable Flyway baseline tests.
-- This is deliberately independent from V1 so the clean migration path can
-- compare V1 output against the legacy table shape.

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

INSERT INTO users (
    id, account_non_expired, account_non_locked, created_at,
    credentials_non_expired, email, enabled, full_name, password,
    role, updated_at, username
) VALUES
    (1, 1, 1, '2026-01-01 00:00:00', 1, 'fixture-1@example.invalid', 1,
     'Fixture User One', 'test-hash-one', 'ROLE_USER', '2026-01-01 00:00:00', 'fixture-user-1'),
    (2, 1, 1, '2026-01-02 00:00:00', 1, 'fixture-2@example.invalid', 1,
     'Fixture User Two', 'test-hash-two', 'ROLE_USER', '2026-01-02 00:00:00', 'fixture-user-2');

INSERT INTO generation_logs (
    id, api_provider, api_source, created_at, description, duration,
    error_message, image_url, input_data, metadata, model_size,
    output_data, processing_time_ms, result_url, success, task_type,
    use_fast_generate, user_id
) VALUES
    (1, 'fixture', 'LOCAL', '2026-01-01 01:00:00', 'fixture success', 5,
     NULL, 'fixture/image-1.png', '{}', '{}', 'test', '{}', 100,
     'fixture/result-1.mp3', 1, 'MUSIC', 0, 1),
    (2, 'fixture', 'LOCAL', '2026-01-02 01:00:00', 'fixture failure', NULL,
     'expected fixture error', 'fixture/image-2.png', '{}', '{}', 'test', NULL, 50,
     NULL, 0, 'DESCRIPTION', 1, 2),
    (3, NULL, 'LEGACY', '2026-01-03 01:00:00', NULL, NULL,
     NULL, NULL, NULL, NULL, 'test', 'fixture text', NULL,
     NULL, 1, 'TEXT', 0, 1);
