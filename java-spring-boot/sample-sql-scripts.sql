-- sample_script1.sql
CREATE TABLE example_config (
    id NUMBER PRIMARY KEY,
    config_key VARCHAR2(100) NOT NULL,
    config_value VARCHAR2(4000),
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- sample_script2.sql
INSERT INTO example_config (id, config_key, config_value)
VALUES (1, 'app.timeout', '3600');

INSERT INTO example_config (id, config_key, config_value)
VALUES (2, 'app.max_connections', '100');

-- sample_script3.sql
CREATE INDEX idx_config_key ON example_config(config_key);
