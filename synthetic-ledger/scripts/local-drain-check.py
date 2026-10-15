"""Verify local API routing during one pod's graceful replacement; no financial load."""
import argparse
import json
import subprocess
import time
from urllib.parse import urlparse
import requests

parser = argparse.ArgumentParser()
parser.add_argument("--kubectl", required=True)
parser.add_argument("--kubeconfig", required=True)
args = parser.parse_args()
base = [args.kubectl, "--kubeconfig", args.kubeconfig,
        "--context", "kind-dtp-f7", "-n", "dtp-lab"]

def kube(*arguments):
    result = subprocess.run(base + list(arguments), capture_output=True, timeout=100)
    if result.returncode:
        raise RuntimeError("Dedicated local Kubernetes operation failed; output omitted")
    return result.stdout.decode()

configuration = json.loads(kube("config", "view", "--minify", "-o", "json"))
server = urlparse(configuration["clusters"][0]["cluster"]["server"])
if server.scheme != "https" or server.hostname not in {"127.0.0.1", "localhost"}:
    raise SystemExit("Only a loopback kind API server is permitted")
nodes = json.loads(kube("get", "nodes", "-o", "json"))["items"]
if len(nodes) != 1 or nodes[0]["metadata"]["name"] != "dtp-f7-control-plane":
    raise SystemExit("Only the dedicated single-node laboratory is permitted")
deployment = json.loads(kube("get", "deployment/ledger-api", "-o", "json"))
if deployment.get("status", {}).get("readyReplicas", 0) < 2:
    raise SystemExit("Two ready API replicas are required before this check")
pods = json.loads(kube("get", "pods", "-l", "app.kubernetes.io/name=ledger-api",
                       "-o", "json"))["items"]
victim = next(p for p in pods if not p["metadata"].get("deletionTimestamp")
              and p.get("status", {}).get("phase") == "Running")
session = requests.Session()
session.trust_env = False
statuses = []
failures = 0
deletion = None
try:
    for index in range(100):
        if index == 5:
            deletion = subprocess.Popen(base + ["delete", "pod", victim["metadata"]["name"],
                                                 "--wait=true", "--timeout=70s"],
                                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            response = session.get("http://127.0.0.1:18085/actuator/health/liveness",
                                   timeout=3, allow_redirects=False)
            statuses.append(response.status_code)
        except requests.RequestException:
            failures += 1
        time.sleep(0.2)
    if deletion is None or deletion.wait(timeout=80):
        raise RuntimeError("Local pod deletion failed")
    kube("rollout", "status", "deployment/ledger-api", "--timeout=90s")
    current = json.loads(kube("get", "pods", "-l", "app.kubernetes.io/name=ledger-api",
                              "-o", "json"))["items"]
    replaced = all(p["metadata"]["uid"] != victim["metadata"]["uid"] for p in current)
    result = {"scope": "local two-replica API liveness routing during graceful pod replacement",
              "financial_inflight_drain_tested": False, "requests": 100,
              "http_200": statuses.count(200), "transport_failures": failures,
              "other_statuses": sorted(set(code for code in statuses if code != 200)),
              "old_pod_replaced": replaced}
    print(json.dumps(result))
    if failures or len(statuses) != 100 or set(statuses) != {200} or not replaced:
        raise SystemExit(1)
finally:
    session.close()
    if deletion is not None and deletion.poll() is None:
        deletion.wait(timeout=80)
