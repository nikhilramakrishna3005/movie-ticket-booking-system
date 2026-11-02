-- Owned by the catalog package: languages, genres, movies.

CREATE TABLE language (
    code text CONSTRAINT language_pk PRIMARY KEY,
    name text NOT NULL CONSTRAINT language_name_uk UNIQUE,
    CONSTRAINT language_code_ck CHECK (code ~ '^[a-z]{2}$')
);

CREATE TABLE genre (
    id   bigint GENERATED ALWAYS AS IDENTITY CONSTRAINT genre_pk PRIMARY KEY,
    name text NOT NULL CONSTRAINT genre_name_uk UNIQUE,
    CONSTRAINT genre_name_ck CHECK (btrim(name) <> '')
);

CREATE TABLE movie (
    id                     bigint GENERATED ALWAYS AS IDENTITY CONSTRAINT movie_pk PRIMARY KEY,
    title                  text    NOT NULL,
    original_language_code text    NOT NULL CONSTRAINT movie_original_language_fk REFERENCES language (code),
    duration_minutes       integer NOT NULL,
    certification          text    NOT NULL,
    release_date           date    NOT NULL,
    CONSTRAINT movie_title_release_uk UNIQUE (title, release_date),
    CONSTRAINT movie_title_ck CHECK (btrim(title) <> ''),
    CONSTRAINT movie_duration_ck CHECK (duration_minutes BETWEEN 1 AND 600),
    CONSTRAINT movie_certification_ck CHECK (certification IN ('G', 'PG', 'PG-13', 'R', 'NC-17', 'NR'))
);

CREATE TABLE movie_genre (
    movie_id bigint NOT NULL CONSTRAINT movie_genre_movie_fk REFERENCES movie (id),
    genre_id bigint NOT NULL CONSTRAINT movie_genre_genre_fk REFERENCES genre (id),
    CONSTRAINT movie_genre_pk PRIMARY KEY (movie_id, genre_id)
);

CREATE INDEX movie_genre_genre_idx ON movie_genre (genre_id, movie_id);
