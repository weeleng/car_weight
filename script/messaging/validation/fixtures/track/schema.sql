CREATE TABLE track_inbox (message_id VARCHAR(80) PRIMARY KEY) ENGINE=InnoDB;
CREATE TABLE position_fact (message_id VARCHAR(80) PRIMARY KEY, vehicle_id VARCHAR(40), occurred BIGINT) ENGINE=InnoDB;
CREATE TABLE latest_position (vehicle_id VARCHAR(40) PRIMARY KEY, occurred BIGINT) ENGINE=InnoDB;
