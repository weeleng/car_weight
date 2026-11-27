"""Black-box remediation checks against the actual Gateway exec JAR and real brokers."""
from contextlib import ExitStack, closing
import hashlib
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import time
import traceback

from gateway import (OUT, RUN, finish, observe, open_gateway,
                     count_stream_fixture, volume)
from run_support import docker, evidence

LEGACY = os.environ["LAB_LEGACY_GATEWAY"]
RABBIT = os.environ["LAB_RABBIT"]
PROBE_CP = f"{RUN}/classes:{RUN}/libs/BOOT-INF/classes:{RUN}/libs/BOOT-INF/lib/*"
TABLE = "iot_device_message_spool"


def broker(action):
    endpoint = docker("port", RABBIT, "5672/tcp")
    if not endpoint.startswith("127.0.0.1:"):
        raise RuntimeError("fixture AMQP must be loopback only")
    return subprocess.run(["java", "-cp", PROBE_CP, "labprobe.SpoolRabbitFixture", endpoint.split(":")[1], action],
                          check=True, capture_output=True, text=True, timeout=12).stdout.strip()


def eventually(check, seconds=30):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        result = check()
        if result:
            return result
        time.sleep(0.2)
    raise AssertionError(f"timed out waiting for {check.__name__}")


def message(id_):
    now = int(time.time() * 1000)
    body = json.dumps(dict(id=id_, requestId=id_, deviceId=1, tenantId=1, method="thing.property.post", spoolTime=now))
    return now, len(body.encode()), body


def seed(name, ids=()):
    path = volume(name, "s2.db")
    with closing(sqlite3.connect(path / "spool.db")) as db, db:
        db.execute(f"DELETE FROM {TABLE}")
        db.executemany(f"INSERT INTO {TABLE}(spool_time,size,message) VALUES (?,?,?)",
                       [message(id_) for id_ in ids])
    return path


def snapshot(path):
    with closing(sqlite3.connect(f"file:{path}/spool.db?mode=ro", uri=True)) as db:
        rows = list(db.execute(f"SELECT id,spool_time,size,message FROM {TABLE} ORDER BY id"))
        version = db.execute("PRAGMA user_version").fetchone()[0]
    evidence(OUT / f"{path.name}-snapshot.json", dict(version=version, rows=rows))
    return [json.loads(r[3])["id"] for r in rows]


def ready(log, workers):
    assert f"F01 READY drainWorkers={workers}" in log, log[-1200:]
    assert log.count("F01 PRE iotDeviceMessageSpool") == 1
    assert "F01 PRE iotDeviceMessageSpoolServiceImpl " in log


def rejected(log, reason):
    assert "F01 READY" not in log, log[-1200:]
    assert reason in log, log[-1500:]


def matrix():
    for bus in ("redis", "rabbitmq"):
        for enabled in (False, True):
            label = f"{bus}-{'on' if enabled else 'off'}"
            path = volume(label)
            with ExitStack() as stack:
                name = open_gateway(stack, label, path, bus, enabled)
                ready(observe(name), int(enabled))
                result, log = finish(name)
                assert result["exit"] == 143
                assert f"Iot{'Redis' if bus == 'redis' else 'RabbitMQ'}MessageBusConfiguration fullMatch=true" in log
            assert (path / "spool.db").is_file() == enabled
            assert (path / "spool.db.owner.lock").is_file() == enabled
    path = volume("disabled-invalid-path")
    with ExitStack() as stack:
        name = open_gateway(stack, "disabled-invalid-path", path, "local", False, readonly=True)
        ready(observe(name), 0)
        assert finish(name)[0]["exit"] == 143
    for label, bus, args, reason in (
        ("local-on", "local", (), "Local 消息总线不支持"),
        ("unconfirmed", "rabbitmq", ("--spring.rabbitmq.publisher-returns=false",), "确认发送需要配置"),
    ):
        path = volume(label)
        with ExitStack() as stack:
            name = open_gateway(stack, label, path, bus, extra=args)
            rejected(observe(name), reason)
            assert finish(name)[0]["exit"] == 1
            assert not (path / "spool.db").exists()


def owners():
    path = seed("owners")
    with ExitStack() as stack:
        first = open_gateway(stack, "owner-first", path)
        ready(observe(first), 1)
        second = open_gateway(stack, "owner-denied", path)
        rejected(observe(second), "其他进程已接管")
        assert finish(second)[0]["exit"] == 1
        old = open_gateway(stack, "old-denied-sqlite", path, jar=LEGACY)
        old_log = observe(old)
        assert "F01 READY drainWorkers=0" in old_log and "SQLITE_BUSY" in old_log
        assert finish(old)[0]["exit"] == 143
        assert finish(first, crash=True)[0]["exit"] == 137
        third = open_gateway(stack, "after-crash", path)
        ready(observe(third), 1)
        assert finish(third)[0]["exit"] == 143
        old = open_gateway(stack, "old-first", path, jar=LEGACY)
        assert "F01 READY drainWorkers=2" in observe(old)
        newer = open_gateway(stack, "new-denied-sqlite", path)
        rejected(observe(newer), "SQLITE_BUSY")
        assert finish(newer)[0]["exit"] == 1
        assert finish(old)[0]["exit"] == 143
        name = open_gateway(stack, "new-after-old-stopped", path)
        ready(observe(name), 1)
        assert finish(name)[0]["exit"] == 143


def incompatible():
    for label, prep, reason in (
        ("unknown", "PRAGMA user_version=99", "Schema 版本未知"),
        ("mixed", "CREATE TABLE alien(id INTEGER)", "Schema 与历史结构不匹配"),
        ("bad", None, "SQLITE_NOTADB"),
        ("readonly-file", None, "离线暂存不可用"),
        ("readonly-dir", None, "离线暂存目录不可写"),
    ):
        path = seed(label, [f"{label}-preserved"]) if label != "bad" else volume(label)
        dbfile = path / "spool.db"
        if label == "bad":
            dbfile.write_bytes(b"synthesized corrupt database")
        if prep:
            with closing(sqlite3.connect(dbfile)) as db, db:
                db.execute(prep)
        original = hashlib.sha256(dbfile.read_bytes()).hexdigest()
        with ExitStack() as stack:
            name = open_gateway(stack, label, path, readonly=label == "readonly-dir",
                                readonly_file=dbfile if label == "readonly-file" else None)
            rejected(observe(name), reason)
            assert finish(name)[0]["exit"] == 1
        assert hashlib.sha256(dbfile.read_bytes()).hexdigest() == original
    path = volume("missing-readonly-dir")
    with ExitStack() as stack:
        name = open_gateway(stack, "missing-readonly-dir", path, readonly=True)
        rejected(observe(name), "离线暂存目录不可写")
        assert finish(name)[0]["exit"] == 1
    assert not (path / "spool.db").exists()


def redis_history():
    path = seed("history", ["history-row"])
    wal = subprocess.run([sys.executable, "-c", """
import os, sqlite3, sys
db=sqlite3.connect(sys.argv[1])
db.execute('PRAGMA wal_autocheckpoint=0')
db.execute('INSERT INTO iot_device_message_spool(spool_time,size,message) VALUES (?,?,?)',
           (sys.argv[2], sys.argv[3], sys.argv[4]))
db.commit()
os._exit(37)
""", str(path / "spool.db"), *map(str, message("wal-row"))], check=False)
    assert wal.returncode == 37 and (path / "spool.db-wal").stat().st_size > 0
    main_only = volume("main-only")
    shutil.copyfile(path / "spool.db", main_only / "spool.db")
    assert snapshot(main_only) == ["history-row"]
    with ExitStack() as stack:
        name = open_gateway(stack, "redis-historical-replay", path)
        ready(observe(name), 1)
        eventually(lambda: count_stream_fixture("wal-row")[0] == 1)
        result, _ = finish(name)
        assert result["exit"] == 143
    for id_ in ("history-row", "wal-row"):
        matches, stream = count_stream_fixture(id_)
        assert matches == 1
        evidence(OUT / f"{id_}-redis.json", dict(matching=matches, records=stream))
    assert snapshot(path) == []


def real_rabbit_confirm():
    broker("bind")
    path = seed("rabbit-confirm")
    with ExitStack() as stack:
        name = open_gateway(stack, "rabbit-confirm", path, "rabbitmq",
                            extra=("--lab-spool-message=real-confirm",))
        ready(observe(name), 1)
        delivery = json.loads(eventually(lambda: broker("take")))
        assert delivery["id"] == "real-confirm" and delivery.get("spoolTime") is None
        result, log = finish(name)
        assert result["exit"] == 143 and "F02B SENT_OR_STORED real-confirm" in log
    assert snapshot(path) == []
    evidence(OUT / "real-confirm-delivery.json", delivery)


def returned_or_nack(label, text, configure):
    broker("reset")
    broker(configure)
    path = seed(label)
    with ExitStack() as stack:
        name = open_gateway(stack, label, path, "rabbitmq", extra=(f"--lab-spool-message={label}",))
        ready(observe(name), 1)
        eventually(lambda: "F02B SENT_OR_STORED " + label in docker("logs", name))
        result, log = finish(name)
        assert result["exit"] == 143 and text in log, log[-1500:]
    assert snapshot(path) == [label]
    broker("reset")
    broker("bind")
    with ExitStack() as stack:
        name = open_gateway(stack, label + "-replay", path, "rabbitmq")
        ready(observe(name), 1)
        item = json.loads(eventually(lambda: broker("take")))
        assert item["id"] == label and item["spoolTime"] > 0
        assert finish(name)[0]["exit"] == 143
    assert snapshot(path) == []


def real_rabbit_outage():
    broker("reset")
    broker("bind")
    path = seed("outage")
    docker("exec", RABBIT, "rabbitmqctl", "stop_app")
    try:
        with ExitStack() as stack:
            name = open_gateway(stack, "rabbit-offline-publish", path, "rabbitmq",
                                extra=("--lab-spool-message=real-outage",))
            ready(observe(name), 1)
            eventually(lambda: "F02B SENT_OR_STORED real-outage" in docker("logs", name))
            assert finish(name)[0]["exit"] == 143
        assert snapshot(path) == ["real-outage"]
    finally:
        docker("exec", RABBIT, "rabbitmqctl", "start_app")
    with ExitStack() as stack:
        name = open_gateway(stack, "rabbit-after-start-app", path, "rabbitmq")
        ready(observe(name), 1)
        assert json.loads(eventually(lambda: broker("take")))["id"] == "real-outage"
        assert finish(name)[0]["exit"] == 143
    assert snapshot(path) == []


def confirmation_loss():
    broker("reset")
    broker("bind")
    path = seed("confirm-loss")
    with ExitStack() as stack:
        name = open_gateway(stack, "rabbit-lost-confirm", path, "rabbitmq", extra=(
            "--lab-spool-message=real-confirm-loss", "--lab-spool-relay=true",
            "--yudao.iot.message-bus.confirm-timeout=800ms",
        ))
        ready(observe(name), 1)
        first = json.loads(eventually(lambda: broker("take")))
        eventually(lambda: "F02B SENT_OR_STORED real-confirm-loss" in docker("logs", name))
        log = docker("logs", name)
        assert "F02B INJECT dropped BASIC.ACK" in log and "确认超时" in log
        (path / "allow-confirms").touch()
        next_ = json.loads(eventually(lambda: broker("take")))
        assert first["id"] == next_["id"] == "real-confirm-loss"
        # A queue delivery can precede the publisher confirm and the local DELETE.
        # Let the actual Gateway report that the spool drained before stopping it.
        eventually(lambda: "[resumeIfEmpty][离线暂存已补传完成" in docker("logs", name))
        assert finish(name)[0]["exit"] == 143
    assert snapshot(path) == []
    evidence(OUT / "confirm-loss-deliveries.json", [first, next_])


def crash_after_publish():
    broker("reset")
    broker("bind")
    ids = ["crash-stable-id", *[f"filler-{i}" for i in range(20)]]
    path = seed("pre-delete-crash", ids)
    with ExitStack() as stack:
        name = open_gateway(stack, "rabbit-before-delete-kill", path, "rabbitmq",
                            extra=("--yudao.iot.gateway.spool.drain-rate=1",))
        ready(observe(name), 1)
        first = json.loads(eventually(lambda: broker("take")))
        assert first["id"] == ids[0]
        assert finish(name, crash=True)[0]["exit"] == 137
    assert snapshot(path) == ids
    broker("reset")
    broker("bind")
    with ExitStack() as stack:
        name = open_gateway(stack, "rabbit-after-kill-replay", path, "rabbitmq")
        ready(observe(name), 1)
        second = json.loads(eventually(lambda: broker("take")))
        assert second["id"] == first["id"]
        eventually(lambda: int(broker("count")) == len(ids) - 1)
        assert finish(name)[0]["exit"] == 143
    assert snapshot(path) == []
    evidence(OUT / "crash-before-delete-deliveries.json", [first, second])


def main():
    results = {}
    cases = {
        "startup": matrix, "ownership": owners, "incompatible": incompatible, "redis-history": redis_history,
        "rabbit-confirm": real_rabbit_confirm,
        "rabbit-returned": lambda: returned_or_nack("real-return", "unroutable", "exchange"),
        "rabbit-nack": lambda: returned_or_nack("real-nack", "nack", "limit"),
        "rabbit-outage": real_rabbit_outage,
        "rabbit-confirm-loss": confirmation_loss, "rabbit-before-delete-crash": crash_after_publish,
    }
    for name, case in cases.items():
        try:
            case()
            results[name] = "passed"
        except Exception:
            traceback.print_exc()
            results[name] = "failed"
        print(name, results[name], flush=True)
        evidence(OUT / "matrix.json", results)
    return int("failed" in results.values())


if __name__ == "__main__":
    raise SystemExit(main())
