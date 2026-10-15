"""Measure an isolated lab DB restart; preserve every failed native PKCE smoke."""
import argparse
import json
import subprocess
import sys
import time
from pathlib import Path
from urllib.parse import urlparse

p = argparse.ArgumentParser()
p.add_argument('--kubectl', required=True)
p.add_argument('--kubeconfig', required=True)
p.add_argument('--output', required=True)
args = p.parse_args()
base = [args.kubectl, '--kubeconfig', args.kubeconfig, '--context', 'kind-dtp-f7', '-n', 'dtp-lab']
result = {'scope': 'single-node synthetic local lab; PostgreSQL pod replacement, not backup restore',
          'attempts': [], 'passed': False, 'seamless_identity_availability_proven': False}
output = Path(args.output)

def save():
    output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')

def kube(*words, stdin=None):
    run = subprocess.run(base + list(words), input=stdin, capture_output=True, text=True, timeout=330)
    if run.returncode:
        raise RuntimeError('Local Kubernetes operation failed; output omitted')
    return run.stdout

def smoke():
    run = subprocess.run([sys.executable, str(Path(__file__).with_name('local-smoke.py'))],
                         capture_output=True, text=True, timeout=150)
    # Never export traceback, HTTP body, credentials or tokens.
    failure = 'other failure; private output omitted'
    for label in ('PKCE authorization', 'Native local login', 'PKCE code exchange'):
        for line in run.stderr.splitlines():
            if line.startswith('RuntimeError: ' + label + ': expected status'):
                failure = line.removeprefix('RuntimeError: ').split(';')[0]
    return {'passed': run.returncode == 0, 'failure': None if run.returncode == 0 else failure}

try:
    config = json.loads(kube('config', 'view', '--minify', '-o', 'json'))
    server = urlparse(config['clusters'][0]['cluster']['server'])
    if server.scheme != 'https' or server.hostname not in {'127.0.0.1', 'localhost'}:
        raise RuntimeError('Only loopback cluster is allowed')
    nodes = json.loads(kube('get', 'nodes', '-o', 'json'))['items']
    if len(nodes) != 1 or nodes[0]['metadata']['name'] != 'dtp-f7-control-plane':
        raise RuntimeError('Dedicated laboratory node identity mismatch')
    for workload in ('statefulset/postgres', 'statefulset/kafka', 'statefulset/mongo',
                     'statefulset/redis', 'deployment/keycloak', 'deployment/ledger-api',
                     'deployment/ledger-worker', 'deployment/activity'):
        kube('rollout', 'status', workload, '--timeout=300s')
        observed = json.loads(kube('get', workload, '-o', 'json'))
        desired = observed.get('spec', {}).get('replicas', 1)
        if desired < 1 or observed.get('status', {}).get('readyReplicas', 0) < desired:
            raise RuntimeError('Every laboratory workload must have ready nonzero replicas')
    baseline = smoke()
    result['baseline'] = baseline
    if not baseline['passed']:
        raise RuntimeError('Baseline smoke failed; database untouched')
    before = json.loads(kube('get', 'pod/postgres-0', '-o', 'json'))['metadata']['uid']
    start = time.monotonic()
    kube('delete', 'pod/postgres-0', '--wait=true', '--timeout=90s')
    kube('wait', '--for=condition=Ready', 'pod/postgres-0', '--timeout=180s')
    after = json.loads(kube('get', 'pod/postgres-0', '-o', 'json'))['metadata']['uid']
    if before == after:
        raise RuntimeError('PostgreSQL pod was not replaced')
    result['postgres_ready_seconds'] = round(time.monotonic() - start, 2)
    for attempt in range(1, 4):
        check = smoke()
        check.update(attempt=attempt, seconds_since_restart=round(time.monotonic() - start, 2))
        result['attempts'].append(check)
        save()
        if check['passed']:
            result['observed_full_smoke_recovery_seconds'] = check['seconds_since_restart']
            break
        time.sleep(5)
    sql = Path(__file__).resolve().parent.parent / 'docs/local-invariants.sql'
    invariants = json.loads(kube('exec', '-i', 'postgres-0', '--', 'psql', '-U', 'dtp_admin',
                                '-d', 'dtp', '-Atq', '-v', 'ON_ERROR_STOP=1', stdin=sql.read_text()))
    result['financial_invariants'] = invariants
    zero = ['not_posted', 'holds', 'reservations', 'negative_customer_balances', 'unbalanced_journals',
            'bad_materialized_balances', 'unpublished', 'runtime_superusers']
    denied = ['api_can_insert_journal', 'api_can_change_balance', 'worker_can_admit']
    result['passed'] = (bool(result['attempts'][-1]['passed']) and all(invariants[x] == 0 for x in zero)
                        and all(invariants[x] is False for x in denied)
                        and invariants['transfers'] == invariants['posting_journals'])
except Exception as error:
    result['error_type'] = type(error).__name__
    # Error text and subprocess outputs remain private to avoid accidental disclosure.
finally:
    save()
print(json.dumps(result))
sys.exit(0 if result['passed'] else 1)
