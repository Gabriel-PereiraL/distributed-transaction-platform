"""Synthetic local PKCE admission-to-projection smoke; never print/save credentials or JWTs."""
import argparse
import base64
import hashlib
import json
import secrets
import time
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import parse_qs, urlparse
import requests

parser = argparse.ArgumentParser()
parser.add_argument("--api", default="http://127.0.0.1:18085")
parser.add_argument("--activity", default="http://127.0.0.1:18086")
parser.add_argument("--issuer", default="http://localhost:18080/realms/dtp-local")
parser.add_argument("--diagnose-claims", action="store_true", help="Print only non-secret synthetic claim metadata, never a token")
args = parser.parse_args()
for address in [args.api, args.activity, args.issuer]:
    parsed = urlparse(address)
    if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost"} or not parsed.port:
        raise SystemExit("Only explicit loopback local endpoints are allowed")
project = Path(__file__).resolve().parent.parent
password = (project / ".local-secrets/alice.password").read_text().strip()

class LoginForm(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.action = None
        self.hidden = {}
    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        if tag == "form" and values.get("id") == "kc-form-login":
            self.action = values.get("action")
        if tag == "input" and values.get("type") == "hidden" and values.get("name"):
            self.hidden[values["name"]] = values.get("value", "")

def expect(response, expected, label):
    if response.status_code != expected:
        raise RuntimeError(f"{label}: expected status{expected}, received{response.status_code}; body intentionally omitted")

verifier = secrets.token_urlsafe(48)
challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
state = secrets.token_urlsafe(24)
redirect = "http://127.0.0.1:8999/callback"
session = requests.Session()
session.trust_env = False
authorization = session.get(args.issuer + "/protocol/openid-connect/auth", params=dict(client_id="dtp-web", response_type="code", scope="openid",
    redirect_uri=redirect, state=state, nonce=secrets.token_urlsafe(24), code_challenge=challenge, code_challenge_method="S256"), timeout=10, allow_redirects=False)
expect(authorization, 200, "PKCE authorization")
# Fixture only: requests lacks the browser's secure-cookie exception for loopback HTTP.
# The endpoint guard above prevents this normalization from reaching any external host.
for cookie in session.cookies:
    cookie.secure = False
form = LoginForm()
form.feed(authorization.text)
if not form.action:
    raise RuntimeError("Native local login form was not found")
origin = urlparse(args.issuer)
action = urlparse(form.action)
if (action.scheme, action.hostname, action.port) != (origin.scheme, origin.hostname, origin.port):
    raise RuntimeError("Local login action must stay on the issuer origin")
login = session.post(form.action, data=dict(form.hidden, username="alice", password=password), timeout=10, allow_redirects=False)
expect(login, 302, "Native local login")
callback = urlparse(login.headers.get("Location", ""))
if callback.scheme != "http" or callback.hostname != "127.0.0.1" or callback.port != 8999 or callback.path != "/callback":
    raise RuntimeError(f"Unexpected local callback path: {callback.hostname}:{callback.port}{callback.path}; parameters omitted")
parameters = parse_qs(callback.query)
if parameters.get("state") != [state] or not parameters.get("code"):
    raise RuntimeError("PKCE state/code verification failed")
tokens = session.post(args.issuer + "/protocol/openid-connect/token", data=dict(grant_type="authorization_code", client_id="dtp-web",
    redirect_uri=redirect, code=parameters["code"][0], code_verifier=verifier), timeout=10)
expect(tokens, 200, "PKCE code exchange")
if args.diagnose_claims:
    encoded_claims = tokens.json()["access_token"].split('.')[1]
    claims = json.loads(base64.urlsafe_b64decode(encoded_claims + '=' * (-len(encoded_claims) % 4)))
    print(json.dumps({name: claims.get(name) for name in ['iss', 'aud', 'azp', 'tenant_id', 'scope', 'realm_access', 'exp', 'iat']}))
headers = {"Authorization": "Bearer " + tokens.json()["access_token"], "Idempotency-Key": "local-smoke-" + secrets.token_hex(12)}
payload = dict(sourceAccountId="20000000-0000-0000-0000-000000000001", destinationAccountId="20000000-0000-0000-0000-000000000002", amountCents=25, currency="BRL")
accepted = session.post(args.api + "/v1/transfers", json=payload, headers=headers, timeout=15)
expect(accepted, 202, "Synthetic admission")
transfer = accepted.json()["transferId"]
duplicate = session.post(args.api + "/v1/transfers", json=payload, headers=headers, timeout=15)
expect(duplicate, 202, "Idempotent admission replay")
if duplicate.json()["transferId"] != transfer:
    raise RuntimeError("Idempotent replay created another transfer")
deadline = time.monotonic() + 90
while True:
    receipt = session.get(args.activity + "/v1/activity/transfers/" + transfer, headers=headers, timeout=5)
    if receipt.status_code == 200 and receipt.json().get("status") == "POSTED":
        break
    if receipt.status_code not in {200, 404} or time.monotonic() > deadline:
        raise RuntimeError(f"Local projection did not reach POSTED; status{receipt.status_code}")
    time.sleep(0.5)
canonical = session.get(args.api + "/v1/transfers/" + transfer, headers=headers, timeout=5)
expect(canonical, 200, "Canonical transfer read")
if canonical.json().get("status") != "POSTED":
    raise RuntimeError("Canonical state was not POSTED")
print(json.dumps(dict(auth="real local Keycloak authorization_code/S256", duplicate_same_transfer=True,
                      canonical="POSTED", projection="POSTED", transfer_id=transfer, amount_cents=25)))
