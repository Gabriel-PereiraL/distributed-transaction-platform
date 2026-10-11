"""Validate specifications and the event produced by -Pintegration verify.

Install requirements-contracts.txt in an isolated venv. No service/cloud access.
"""
from pathlib import Path
import json
import jsonschema
import yaml
from openapi_spec_validator import validate_spec

root = Path(__file__).resolve().parent.parent
ledger_spec = yaml.safe_load((root / "contracts/openapi.yaml").read_text(encoding="utf-8"))
validate_spec(ledger_spec)
for filename in ["requeue-queued.json", "requeue-terminal.json"]:
    response = json.loads((root / "ledger-api/target/artifacts" / filename).read_text(encoding="utf-8"))
    jsonschema.Draft202012Validator(ledger_spec["components"]["schemas"]["Requeue"], format_checker=jsonschema.FormatChecker()).validate(response)
legacy_schema = ledger_spec["paths"]["/v1/accounts/{id}/legacy"]["get"]["responses"]["200"]["content"]["application/json"]["schema"]
legacy = json.loads((root / "ledger-api/target/artifacts/legacy-metadata.json").read_text(encoding="utf-8"))
jsonschema.Draft202012Validator(legacy_schema, format_checker=jsonschema.FormatChecker()).validate(legacy)
activity_spec = yaml.safe_load((root / "contracts/activity-openapi.yaml").read_text(encoding="utf-8"))
validate_spec(activity_spec)
receipt_schema = activity_spec["components"]["schemas"]["Receipt"]
receipt = json.loads((root / "activity-service/target/artifacts/activity-receipt.json").read_text(encoding="utf-8"))
jsonschema.Draft202012Validator(receipt_schema, format_checker=jsonschema.FormatChecker()).validate(receipt)
page = json.loads((root / "activity-service/target/artifacts/activity-page.json").read_text(encoding="utf-8"))
for item in page["items"]:
    jsonschema.Draft202012Validator(receipt_schema, format_checker=jsonschema.FormatChecker()).validate(item)
assert page["nextCursor"] is None or isinstance(page["nextCursor"], str)
history_schema = activity_spec["paths"]["/v1/activity/transfers/{id}/events"]["get"]["responses"]["200"]["content"]["application/json"]["schema"]
history = json.loads((root / "activity-service/target/artifacts/activity-history.json").read_text(encoding="utf-8"))
jsonschema.Draft202012Validator(history_schema, format_checker=jsonschema.FormatChecker()).validate(history)
schema = json.loads((root / "contracts/transfer-accepted.v1.schema.json").read_text(encoding="utf-8"))
jsonschema.Draft202012Validator.check_schema(schema)
sample_path = root / "ledger-api/target/artifacts/accepted-event.json"
if not sample_path.exists():
    raise SystemExit("Run ./mvnw -Pintegration verify first: persisted event sample missing.")
sample = json.loads(sample_path.read_text(encoding="utf-8"))
jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker()).validate(sample)
assert sample["tenantId"] == sample["payload"]["tenantId"]
assert sample["aggregateId"] == sample["payload"]["id"]
assert sample["occurredAt"] == sample["payload"]["acceptedAt"]
terminal_schema = json.loads((root / "contracts/transfer-terminal.v1.schema.json").read_text(encoding="utf-8"))
jsonschema.Draft202012Validator.check_schema(terminal_schema)
for filename in ["posted-event.json", "rejected-event.json"]:
    terminal = json.loads((root / "ledger-api/target/artifacts" / filename).read_text(encoding="utf-8"))
    jsonschema.Draft202012Validator(terminal_schema, format_checker=jsonschema.FormatChecker()).validate(terminal)
    assert terminal["tenantId"] == terminal["payload"]["tenantId"]
    assert terminal["aggregateId"] == terminal["payload"]["id"]
    assert terminal["occurredAt"] == terminal["payload"]["terminalAt"]
    assert terminal["payload"]["terminalAt"] >= terminal["payload"]["acceptedAt"]
quarantine_schema = json.loads((root / "contracts/quarantine.v1.schema.json").read_text(encoding="utf-8"))
jsonschema.Draft202012Validator.check_schema(quarantine_schema)
quarantine = json.loads((root / "ledger-worker/target/artifacts/quarantine-event.json").read_text(encoding="utf-8"))
jsonschema.Draft202012Validator(quarantine_schema).validate(quarantine)
import base64
import hashlib
original = base64.b64decode(quarantine["bytesBase64"], validate=True)
assert len(original) <= 65536
if not quarantine["truncated"]:
    assert len(original) == quarantine["originalBytes"]
    assert hashlib.sha256(original).hexdigest() == quarantine["sha256"]
print("OpenAPI and actual accepted/posted/rejected/quarantine contracts validated.")
