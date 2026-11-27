import json
import subprocess


def docker(*args, env=None):
    return subprocess.run(["docker", *args], env=env, check=True, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT).stdout.strip()


def evidence(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
