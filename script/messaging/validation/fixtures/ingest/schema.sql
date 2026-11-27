CREATE TABLE raw_event (
  event_id VARCHAR(80) PRIMARY KEY, digest CHAR(64) NOT NULL, payload BLOB NOT NULL,
  device_observed_ack BOOLEAN NOT NULL DEFAULT FALSE
) ENGINE=InnoDB;
CREATE TABLE outbox (
  message_id VARCHAR(80) PRIMARY KEY, event_id VARCHAR(80) NOT NULL UNIQUE,
  digest CHAR(64) NOT NULL, payload BLOB NOT NULL, topology_version INT NOT NULL,
  status ENUM('PENDING','PUBLISHING','RETRY_WAIT','PUBLISHED','DEAD') NOT NULL DEFAULT 'PENDING',
  version BIGINT NOT NULL DEFAULT 0, attempts INT NOT NULL DEFAULT 0,
  attempt_id VARCHAR(36), owner VARCHAR(40), lease_until DATETIME(3),
  next_retry_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  last_error VARCHAR(80), INDEX ready_idx(status, next_retry_at, message_id),
  INDEX lease_idx(status, lease_until, message_id)
) ENGINE=InnoDB;
CREATE TABLE replay_audit (
  attempt_id VARCHAR(36) PRIMARY KEY, message_id VARCHAR(80) NOT NULL, event_id VARCHAR(80) NOT NULL,
  digest CHAR(64) NOT NULL, payload BLOB NOT NULL, consumer_id VARCHAR(40) NOT NULL,
  requester VARCHAR(40) NOT NULL, approver VARCHAR(40) NOT NULL, reason VARCHAR(120) NOT NULL,
  requested_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  result VARCHAR(24) NOT NULL, reconciliation VARCHAR(32) NOT NULL
) ENGINE=InnoDB;
CREATE TABLE raw_conflict (
  event_id VARCHAR(80), received_digest CHAR(64), received_payload BLOB NOT NULL,
  detected_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(event_id,received_digest)
) ENGINE=InnoDB;
