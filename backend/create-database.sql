-- Run once as a PostgreSQL superuser (e.g. in psql) to create the application database.
-- Choose your own password; it is NOT stored in the repository. Export it as DB_PASSWORD when starting the app.
CREATE DATABASE loanlens_db;
-- Optional: a dedicated login instead of the postgres superuser
-- CREATE ROLE loanlens LOGIN PASSWORD 'change-me';
-- GRANT ALL PRIVILEGES ON DATABASE loanlens_db TO loanlens;
-- \c loanlens_db
-- GRANT ALL ON SCHEMA public TO loanlens;
