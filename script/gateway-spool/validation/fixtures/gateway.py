"""Drive the original Gateway exec JAR using the read-only Spring observer."""
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys
import time

from contextlib import ExitStack

from run_support import docker, evidence

RUN = Path(os.environ["LAB_RUN"])
OUT = Path(os.environ["LAB_OUT"])
NETWORK = os.environ["LAB_NETWORK"]
LABEL = f"org.weeleng.eng002f02a={NETWORK}"
REDIS = os.environ["LAB_REDIS"]


def volume(name, fixture=None):
    directory = OUT / name
    directory.mkdir()
    if fixture:
        shutil.copyfile(RUN / fixture, directory / "spool.db")
    return directory


def open_gateway(stack, case, path, mode="redis", enabled=True, readonly=False, extra=(),
                 jar=None, readonly_file=None):
    name = NETWORK + "-" + case
    flags = {"SPRING_RABBITMQ_USERNAME": os.environ["LAB_MQ_USER"],
             "SPRING_RABBITMQ_PASSWORD": os.environ["LAB_MQ_PASSWORD"]}
    mount = "/data"
    bus = (f"--yudao.iot.message-bus.type={mode}",) if mode != "missing" else (
        "--spring.config.location=file:/probe/no-type.yaml",)
    file_mount = ["-v", f"{readonly_file}:/data/spool.db:ro"] if readonly_file else []
    args = [
        "run", "-d", "--name", name, "--label", LABEL, "--network", os.environ["LAB_GATEWAY_NETWORK"],
        "-v", f"{path}:{mount}:{'ro' if readonly else 'rw'}", *file_mount,
        "-v", f"{RUN}/classes:/probe/classes:ro",
        "-v", f"{RUN}/no-type.yaml:/probe/no-type.yaml:ro",
        "-v", f"{jar or os.environ['LAB_GATEWAY']}:/probe/gateway.jar:ro",
        "-e", "SPRING_RABBITMQ_USERNAME", "-e", "SPRING_RABBITMQ_PASSWORD",
        "--entrypoint", "java", "eclipse-temurin:21-jre", "-XX:ActiveProcessorCount=2", "-Xmx256m",
        "-Dloader.path=/probe/classes", "-Dloader.main=labprobe.GatewayProbe", "-cp", "/probe/gateway.jar",
        "org.springframework.boot.loader.launch.PropertiesLauncher", "--spring.main.web-application-type=none",
        "--spring.data.redis.host=redis", "--spring.data.redis.database=15",
        "--spring.rabbitmq.host=rabbit", "--spring.rabbitmq.virtual-host=fixture",
        "--yudao.iot.gateway.rpc.url=http://127.0.0.1:1", *bus,
        f"--yudao.iot.gateway.spool.enabled={str(enabled).lower()}",
        "--yudao.iot.gateway.spool.file=/data/spool.db", *extra,
    ]
    evidence(OUT / f"{case}-command.json", ["docker", *args])
    docker(*args, env={**os.environ, **flags})
    stack.callback(docker, "rm", "-fv", name)
    return name


def observe(name):
    for _ in range(100):
        log = docker("logs", name)
        if "F01 READY" in log or docker("inspect", "-f", "{{.State.Running}}", name) == "false":
            (OUT / f"{name.removeprefix(NETWORK + '-')}.log").write_text(
                log.replace(os.environ["LAB_MQ_PASSWORD"], "[redacted]") + "\n")
            return log
        time.sleep(0.3)
    raise RuntimeError(f"Gateway timed out: {name}")


def finish(name, crash=False):
    if docker("inspect", "-f", "{{.State.Running}}", name) == "true":
        if crash:
            docker("kill", name)
        else:
            docker("stop", "--time", "10", name)
    code = int(docker("wait", name))
    log = docker("logs", name).replace(os.environ["LAB_MQ_PASSWORD"], "[redacted]")
    case = name.removeprefix(NETWORK + "-")
    (OUT / f"{case}.log").write_text(log + "\n")
    result = dict(exit=code, ready="F01 READY" in log, beans=log.count("F01 PRE iotDeviceMessageSpool"),
                  workers=next((int(line.rsplit("=", 1)[1]) for line in log.splitlines()
                                if line.startswith("F01 READY")), None),
                  conditions=[line for line in log.splitlines() if line.startswith("F01 CONDITION")])
    evidence(OUT / f"{case}-process.json", result)
    return result, log


def check(test, path, mode="redis", enabled=True, readonly=False, workers=2, expected_exit=143,
          message=None, extra=()):
    with ExitStack() as stack:
        name = open_gateway(stack, test, path, mode, enabled, readonly, extra)
        observe(name)
        result, log = finish(name)
        condition = {"redis": "Redis", "rabbitmq": "RabbitMQ", "missing": "Local"}.get(mode)
        if condition:
            assert f"Iot{condition}MessageBusConfiguration fullMatch=true" in log
            assert result["beans"] == 2
        assert result["exit"] == expected_exit and result["workers"] == workers, result
        assert message is None or message in log, log
        if workers == 2:
            properties = [line.split("gatewayProperties=")[1] for line in log.splitlines()
                          if line.startswith("F01 STATE") and "gatewayProperties=" in line]
            assert len(properties) == 2 and len(set(properties)) == 1, properties
            assert "F01 MESSAGE DEPS" in log and "iotDeviceMessageSpoolServiceImpl" in log


def count_stream_fixture(message_id):
    data = json.loads(docker("exec", REDIS, "redis-cli", "-n", "15", "--json", "XRANGE",
                             "iot_device_message", "-", "+"))
    return sum(message_id in json.dumps(entry) for entry in data), data


def backlog():
    path = volume("historical", "s1.db")
    with sqlite3.connect(path / "spool.db") as db:
        now = int(time.time() * 1000)
        document = json.dumps(dict(id="eng002f-history-duplicate", requestId="fixture", deviceId=1, tenantId=1,
                                   method="thing.property.post", spoolTime=now))
        rows = [(now, len(document.encode()), document)] * 3
        db.executemany("INSERT INTO iot_device_message_spool(spool_time,size,message) VALUES (?,?,?)", rows)
    with ExitStack() as stack:
        name = open_gateway(stack, "historical", path, extra=("--yudao.iot.gateway.spool.drain-rate=1",))
        log = observe(name)
        assert "F01 READY drainWorkers=2" in log
        repeats = 0
        for _ in range(50):
            repeats, data = count_stream_fixture("eng002f-history-duplicate")
            if repeats >= 6:
                break
            time.sleep(0.25)
        result, _ = finish(name)
        evidence(OUT / "historical-stream.json", {"matching": repeats, "messages": data})
        assert result["exit"] == 143 and repeats == 6, repeats


def execute(case):
    if case == "matrix":
        check("redis-off", volume("redis-off"), enabled=False, workers=0)
        check("redis-on", volume("redis-on"))
        assert (OUT / "redis-on/spool.db").is_file()
        check("rabbit-off", volume("rabbit-off"), mode="rabbitmq", enabled=False, workers=0)
        check("rabbit-on", volume("rabbit-on"), mode="rabbitmq", workers=None, expected_exit=1,
              message="离线暂存只支持 Redis 消息总线")
    elif case == "permissions":
        check("readonly", volume("readonly", "s1.db"), readonly=True, workers=0, message="SQLITE_CANTOPEN")
        check("no-write", volume("no-write"), readonly=True, workers=0)
        corrupt = volume("corrupt")
        (corrupt / "spool.db").write_bytes(b"synthetic corrupt SQLite")
        check("corrupt", corrupt, workers=0, message="SQLITE_NOTADB")
        assert (corrupt / "spool.db").read_bytes() == b"synthetic corrupt SQLite"
    elif case == "schema":
        path = volume("unknown", "s2.db")
        with sqlite3.connect(path / "spool.db") as db:
            db.execute("PRAGMA user_version=99")
        check("unknown", path)
        with sqlite3.connect(path / "spool.db") as db:
            assert db.execute("PRAGMA user_version").fetchone() == (99,)
        check("missing-type", volume("missing-type"), mode="missing", enabled=False, workers=0)
        check("invalid-type", volume("invalid-type"), mode="invalid", enabled=False, workers=None, expected_exit=1,
              message="IotMessageBus")
    elif case == "shared":
        path = volume("shared")
        gate = OUT / "ownership-started"
        with subprocess.Popen(sys.argv[2:], stdout=(OUT / "legacy-lock-holder.log").open("w"),
                              stderr=subprocess.STDOUT) as lock_holder:
            try:
                for _ in range(80):
                    if gate.is_file() or lock_holder.poll() is not None:
                        break
                    time.sleep(0.1)
                assert gate.is_file()
                with ExitStack() as stack:
                    first = open_gateway(stack, "shared-first", path)
                    assert "F01 READY drainWorkers=2" in observe(first)
                    second = open_gateway(stack, "shared-second", path)
                    assert "F01 READY drainWorkers=2" in observe(second)
                    assert lock_holder.poll() is None
                    assert finish(second)[0]["exit"] == 143
                    assert finish(first, crash=True)[0]["exit"] == 137
                check("after-kill", path)
                assert lock_holder.poll() is None
            finally:
                lock_holder.terminate()
    elif case == "backlog":
        backlog()
    else:
        raise ValueError(case)


if __name__ == "__main__":
    execute(sys.argv[1])
