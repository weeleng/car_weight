CREATE TABLE key_event_spool (
 local_record_id INTEGER PRIMARY KEY,
 producer_namespace TEXT NOT NULL,
 event_id TEXT NOT NULL,
 message_id TEXT NOT NULL,
 tenant_context TEXT NOT NULL,
 device_identity TEXT NOT NULL,
 payload BLOB,
 payload_hash TEXT NOT NULL,
 schema_version INTEGER NOT NULL DEFAULT 1,
 category TEXT NOT NULL CHECK(category='KEY_MEASUREMENT'),
 protocol TEXT NOT NULL DEFAULT 'synthetic',
 received_at INTEGER NOT NULL,
 persisted_at INTEGER NOT NULL,
 status TEXT NOT NULL CHECK(status IN ('PENDING','PUBLISHING','RETRY_WAIT','BROKER_CONFIRMED','WAITING_INGEST','HANDOFF_COMPLETE','QUARANTINED')),
 attempt_count INTEGER NOT NULL DEFAULT 0,
 attempt_id TEXT,
 lease_owner INTEGER,
 lease_until INTEGER,
 next_retry INTEGER,
 last_error TEXT,
 broker_confirmed_at INTEGER,
 handoff_evidence INTEGER,
 handoff_completed_at INTEGER,
 version INTEGER NOT NULL DEFAULT 0,
 UNIQUE(producer_namespace,event_id),
 UNIQUE(producer_namespace,message_id),
 FOREIGN KEY (handoff_evidence,local_record_id) REFERENCES key_handoff_evidence(evidence_id,local_record_id),
 CHECK ((status='HANDOFF_COMPLETE' AND handoff_evidence IS NOT NULL AND handoff_completed_at IS NOT NULL) OR
        (status<>'HANDOFF_COMPLETE' AND handoff_evidence IS NULL AND handoff_completed_at IS NULL)),
 CHECK(payload IS NOT NULL OR status='HANDOFF_COMPLETE'),
 CHECK ((status='PUBLISHING' AND attempt_id IS NOT NULL AND lease_owner IS NOT NULL AND lease_until IS NOT NULL) OR
        (status<>'PUBLISHING' AND lease_owner IS NULL AND lease_until IS NULL))
);
CREATE INDEX key_event_due ON key_event_spool(status,next_retry,local_record_id);
CREATE INDEX key_event_lease ON key_event_spool(lease_until) WHERE status='PUBLISHING';
CREATE TABLE key_handoff_evidence (
 evidence_id INTEGER PRIMARY KEY,
 local_record_id INTEGER NOT NULL UNIQUE REFERENCES key_event_spool(local_record_id),
 issuer TEXT NOT NULL,
 proof_id TEXT NOT NULL,
 tenant TEXT NOT NULL,
 device TEXT NOT NULL,
 namespace TEXT NOT NULL,
 event_id TEXT NOT NULL,
 message_id TEXT NOT NULL,
 hash TEXT NOT NULL,
 intent TEXT NOT NULL,
 raw_ref TEXT NOT NULL,
 outbox_ref TEXT NOT NULL,
 proof_version INTEGER NOT NULL,
 content_digest TEXT NOT NULL,
 observed_at INTEGER NOT NULL,
 UNIQUE(issuer,proof_id),
 UNIQUE(evidence_id,local_record_id)
);
CREATE TABLE key_spool_quarantine (
 quarantine_id INTEGER PRIMARY KEY,
 original_record_id INTEGER REFERENCES key_event_spool(local_record_id),
 source_volume TEXT NOT NULL,
 source_row TEXT NOT NULL,
 migration_batch TEXT,
 payload BLOB NOT NULL,
 hash TEXT NOT NULL,
 reason TEXT NOT NULL,
 UNIQUE(source_volume,source_row)
);
CREATE TABLE lab_owner (singleton INTEGER PRIMARY KEY CHECK(singleton=1), generation INTEGER NOT NULL);
INSERT INTO lab_owner VALUES (1,0);
CREATE TABLE lab_ordinary (id INTEGER PRIMARY KEY, payload BLOB NOT NULL, received_at INTEGER NOT NULL);
CREATE TABLE lab_cleanup (local_record_id INTEGER PRIMARY KEY REFERENCES key_event_spool(local_record_id),
 action TEXT NOT NULL CHECK(action IN ('ARCHIVE','CLEAN')), archived BLOB, payload_hash TEXT NOT NULL);
