-- The pipeline (编排) mode changed from a single serial chain to three parallel
-- lanes, so the persisted results column now holds a nested lanes array instead
-- of a flat stages array. Rename the column to match the new shape.
ALTER TABLE forkprobe_pipeline RENAME COLUMN stages_json TO lanes_json;
