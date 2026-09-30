-- Databases for Socle local stack
CREATE USER temporal WITH PASSWORD 'socle';
CREATE USER temporal_visibility WITH PASSWORD 'socle';

CREATE DATABASE socle_core OWNER socle;
CREATE DATABASE keycloak OWNER socle;
CREATE DATABASE temporal OWNER socle;
CREATE DATABASE temporal_visibility OWNER socle;
CREATE DATABASE openfga OWNER socle;

GRANT ALL PRIVILEGES ON DATABASE socle_core TO socle;
GRANT ALL PRIVILEGES ON DATABASE keycloak TO socle;
GRANT ALL PRIVILEGES ON DATABASE temporal TO socle;
GRANT ALL PRIVILEGES ON DATABASE temporal_visibility TO socle;
GRANT ALL PRIVILEGES ON DATABASE openfga TO socle;

GRANT ALL PRIVILEGES ON DATABASE temporal TO temporal;
GRANT ALL PRIVILEGES ON DATABASE temporal_visibility TO temporal_visibility;
