"""Reconcile only the task's synthetic local realm after changing import bindings."""
import json
from pathlib import Path
import requests

project = Path(__file__).resolve().parent.parent
private = project / ".local-secrets"
desired = json.loads((private / "dtp-local-realm.json").read_text())
assert desired["realm"] == "dtp-local"
assert '${' not in json.dumps(desired), "All import bindings must be rendered before local reconciliation"
base = "http://localhost:18080"
session = requests.Session()
session.trust_env = False
token = session.post(base + "/realms/master/protocol/openid-connect/token", data=dict(grant_type="password", client_id="admin-cli",
    username="dtp-admin", password=(private / "keycloak-admin.password").read_text().strip()), timeout=10)
if token.status_code != 200:
    raise RuntimeError("Local administrative bootstrap authentication failed; body omitted")
session.headers["Authorization"] = "Bearer " + token.json()["access_token"]
admin = base + "/admin/realms/dtp-local"
profile = session.get(admin + "/users/profile", timeout=10)
if profile.status_code != 200:
    raise RuntimeError("Local user profile metadata unavailable")
profile_config = profile.json()
if not any(attribute["name"] == "tenant_id" for attribute in profile_config["attributes"]):
    profile_config["attributes"].append(dict(name="tenant_id", displayName="Synthetic tenant", multivalued=False,
        permissions={"view": ["admin"], "edit": ["admin"]}, validations={"pattern": {"pattern": "[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"}}))
configured = session.put(admin + "/users/profile", json=profile_config, timeout=10)
if configured.status_code != 200:
    raise RuntimeError("Admin-only local tenant attribute configuration failed")
for client in desired["clients"]:
    listed = session.get(admin + "/clients", params={"clientId": client["clientId"]}, timeout=10)
    if listed.status_code != 200 or len(listed.json()) != 1:
        raise RuntimeError("Expected one existing synthetic client")
    identity = listed.json()[0]["id"]
    updated = session.put(admin + "/clients/" + identity, json=client, timeout=10)
    if updated.status_code != 204:
        raise RuntimeError("Local client binding reconciliation failed; body omitted")
for user in desired["users"]:
    if user.get("serviceAccountClientId"):
        continue
    listed = session.get(admin + "/users", params={"username": user["username"], "exact": "true"}, timeout=10)
    if listed.status_code != 200 or len(listed.json()) != 1:
        raise RuntimeError("Expected one existing synthetic user")
    identity = listed.json()[0]["id"]
    if identity != user["id"]:
        raise RuntimeError("Local seed subject identity must stay unchanged")
    representation = {key: user[key] for key in ['username', 'firstName', 'lastName', 'email', 'emailVerified', 'enabled', 'attributes'] if key in user}
    representation["requiredActions"] = []
    updated = session.put(admin + "/users/" + identity, json=representation, timeout=10)
    if updated.status_code != 204:
        raise RuntimeError("Local tenant binding reconciliation failed; body omitted")
print("Synthetic local realm bindings reconciled; credentials and tokens were not printed")
