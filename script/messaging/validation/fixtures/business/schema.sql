CREATE TABLE inbox (
  consumer_id VARCHAR(40), producer VARCHAR(40), message_id VARCHAR(80), event_id VARCHAR(80) NOT NULL,
  digest CHAR(64) NOT NULL, business_key VARCHAR(80) NOT NULL,
  PRIMARY KEY(consumer_id,producer,message_id)
) ENGINE=InnoDB;
CREATE TABLE fact (business_key VARCHAR(80) PRIMARY KEY, event_id VARCHAR(80) NOT NULL) ENGINE=InnoDB;
CREATE TABLE business_outbox (business_key VARCHAR(80) PRIMARY KEY) ENGINE=InnoDB;
CREATE TABLE failure (
  envelope_key CHAR(64) PRIMARY KEY, message_id VARCHAR(80) NOT NULL, body BLOB NOT NULL,
  reason VARCHAR(40) NOT NULL, attempts INT NOT NULL DEFAULT 1, state VARCHAR(20) NOT NULL DEFAULT 'HELD'
) ENGINE=InnoDB;
