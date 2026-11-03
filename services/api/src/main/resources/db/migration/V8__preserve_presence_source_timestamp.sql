ALTER TABLE robot_presence ADD COLUMN source_timestamp TIMESTAMP WITH TIME ZONE;
UPDATE robot_presence SET source_timestamp = observed_at;
ALTER TABLE robot_presence ALTER COLUMN source_timestamp SET NOT NULL;
