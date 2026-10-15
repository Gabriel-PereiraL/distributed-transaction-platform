"""Export only test counts; never publish report properties, output or credentials."""
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent.parent
suites = []
for kind in ("surefire", "failsafe"):
    for report in sorted(root.glob(f"*/target/{kind}-reports/TEST-*.xml")):
        element = ET.parse(report).getroot()
        suites.append({"module": report.parts[-4], "kind": kind,
                       **{key: int(element.get(key, "0"))
                          for key in ("tests", "failures", "errors", "skipped")}})
if not suites:
    raise SystemExit("No test reports found")
result = {"suites": suites, "totals": {
    key: sum(suite[key] for suite in suites)
    for key in ("tests", "failures", "errors", "skipped")}}
destination = Path(sys.argv[1])
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
print(json.dumps(result["totals"]))
