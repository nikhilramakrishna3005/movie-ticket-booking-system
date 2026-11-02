-- Compact, deterministic demo data. See docs/sample-data.md for contents and validity window.
-- Never edit this migration once published; extend or correct the data in a new migration.

INSERT INTO language (code, name) VALUES
    ('en', 'English'),
    ('es', 'Spanish'),
    ('hi', 'Hindi'),
    ('ja', 'Japanese'),
    ('ko', 'Korean');

INSERT INTO genre (name) VALUES
    ('Action'), ('Adventure'), ('Animation'), ('Crime'), ('Drama'),
    ('Family'), ('Fantasy'), ('Sci-Fi'), ('Sport'), ('Thriller');

INSERT INTO movie (title, original_language_code, duration_minutes, certification, release_date) VALUES
    ('Interstellar',    'en', 169, 'PG-13', DATE '2014-11-07'),
    ('Inception',       'en', 148, 'PG-13', DATE '2010-07-16'),
    ('The Dark Knight', 'en', 152, 'PG-13', DATE '2008-07-18'),
    ('Spirited Away',   'ja', 125, 'PG',    DATE '2001-07-20'),
    ('Dangal',          'hi', 161, 'NR',    DATE '2016-12-23'),
    ('Parasite',        'ko', 132, 'R',     DATE '2019-05-30'),
    ('Coco',            'en', 105, 'PG',    DATE '2017-11-22'),
    ('Roma',            'es', 135, 'R',     DATE '2018-08-30');

INSERT INTO movie_genre (movie_id, genre_id)
SELECT m.id, g.id
FROM (VALUES
    ('Interstellar', 'Sci-Fi'), ('Interstellar', 'Drama'), ('Interstellar', 'Adventure'),
    ('Inception', 'Sci-Fi'), ('Inception', 'Action'), ('Inception', 'Thriller'),
    ('The Dark Knight', 'Action'), ('The Dark Knight', 'Crime'), ('The Dark Knight', 'Drama'),
    ('Spirited Away', 'Animation'), ('Spirited Away', 'Fantasy'), ('Spirited Away', 'Family'),
    ('Dangal', 'Drama'), ('Dangal', 'Sport'),
    ('Parasite', 'Thriller'), ('Parasite', 'Drama'),
    ('Coco', 'Animation'), ('Coco', 'Family'), ('Coco', 'Fantasy'),
    ('Roma', 'Drama')
) AS mg (title, genre_name)
JOIN movie m ON m.title = mg.title
JOIN genre g ON g.name = mg.genre_name;

INSERT INTO city (name, state_code) VALUES
    ('Dallas', 'TX'),
    ('Houston', 'TX'),
    ('Austin', 'TX');

-- Theater names are fictional.
INSERT INTO theater (city_id, name, address)
SELECT c.id, t.name, t.address
FROM (VALUES
    ('Dallas',  'Starlight Cinemas Downtown', '1200 Elm St, Dallas, TX'),
    ('Dallas',  'Lone Star Cineplex Uptown',  '2500 McKinney Ave, Dallas, TX'),
    ('Houston', 'Bayou Picture House',        '800 Main St, Houston, TX'),
    ('Houston', 'Galleria Screens',           '5000 Westheimer Rd, Houston, TX'),
    ('Austin',  'Congress Avenue Cinema',     '600 Congress Ave, Austin, TX')
) AS t (city_name, name, address)
JOIN city c ON c.name = t.city_name AND c.state_code = 'TX';

-- Layouts: large screens have 6 rows x 10 seats, small screens 5 rows x 8 seats.
-- The last two rows of every screen are PREMIUM.
CREATE TEMPORARY TABLE sample_screen_layout (
    theater_name  text    NOT NULL,
    screen_name   text    NOT NULL,
    row_count     integer NOT NULL,
    seats_per_row integer NOT NULL
) ON COMMIT DROP;

INSERT INTO sample_screen_layout VALUES
    ('Starlight Cinemas Downtown', 'Screen 1', 6, 10),
    ('Starlight Cinemas Downtown', 'Screen 2', 5, 8),
    ('Lone Star Cineplex Uptown',  'Screen 1', 5, 8),
    ('Bayou Picture House',        'Screen 1', 6, 10),
    ('Galleria Screens',           'Screen 1', 5, 8),
    ('Congress Avenue Cinema',     'Screen 1', 6, 10);

INSERT INTO screen (theater_id, name)
SELECT t.id, l.screen_name
FROM sample_screen_layout l
JOIN theater t ON t.name = l.theater_name;

INSERT INTO seat (screen_id, row_label, seat_number, category)
SELECT s.id,
       chr(64 + r.row_index),
       n.seat_number,
       CASE WHEN r.row_index > l.row_count - 2 THEN 'PREMIUM' ELSE 'STANDARD' END
FROM sample_screen_layout l
JOIN theater t ON t.name = l.theater_name
JOIN screen s ON s.theater_id = t.id AND s.name = l.screen_name
CROSS JOIN LATERAL generate_series(1, l.row_count) AS r (row_index)
CROSS JOIN LATERAL generate_series(1, l.seats_per_row) AS n (seat_number);

-- Showtimes: every screen, every day from 2026-11-03 to 2027-01-31 inclusive, at 13:00 and
-- 16:30 America/Chicago local time (UTC-6 for the whole window, so UTC date = local date).
-- Movies rotate deterministically across screens, days, and slots. ends_at adds 15 minutes
-- of cleaning time; the longest movie (169 min) still ends before the 16:30 slot.
WITH screens AS (
    SELECT id, row_number() OVER (ORDER BY id) AS rn FROM screen
), movies AS (
    SELECT id, original_language_code, duration_minutes,
           row_number() OVER (ORDER BY id) AS rn, count(*) OVER () AS total
    FROM movie
), days AS (
    SELECT d::date AS day, row_number() OVER (ORDER BY d) - 1 AS idx
    FROM generate_series(DATE '2026-11-03', DATE '2027-01-31', INTERVAL '1 day') AS d
), slots AS (
    SELECT * FROM (VALUES (0, TIME '13:00'), (1, TIME '16:30')) AS s (slot, local_time)
)
INSERT INTO showtime (screen_id, movie_id, language_code, starts_at, ends_at, status, created_at)
SELECT s.id,
       m.id,
       m.original_language_code,
       (d.day + sl.local_time) AT TIME ZONE 'America/Chicago',
       (d.day + sl.local_time) AT TIME ZONE 'America/Chicago' + make_interval(mins => m.duration_minutes + 15),
       'SCHEDULED',
       TIMESTAMPTZ '2026-11-02 00:00:00+00'
FROM screens s
CROSS JOIN days d
CROSS JOIN slots sl
JOIN movies m ON m.rn = ((s.rn + d.idx + sl.slot * 3) % m.total) + 1;

-- Seat inventory for every sample showtime, all AVAILABLE.
INSERT INTO show_seat (showtime_id, seat_id, screen_id, status, price)
SELECT st.id,
       se.id,
       st.screen_id,
       'AVAILABLE',
       CASE se.category WHEN 'PREMIUM' THEN 16.00 ELSE 12.50 END
FROM showtime st
JOIN seat se ON se.screen_id = st.screen_id;
