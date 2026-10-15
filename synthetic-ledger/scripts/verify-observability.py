"""Bounded, synthetic local OTLP smoke; no credentials or application databases."""
import json
import time
import uuid
import urllib.parse
import urllib.request


def request(url, payload=None):
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=3) as response:
        raw = response.read(262145)
        assert len(raw) <= 262144, "Response exceeds smoke bound"
        return json.loads(raw) if raw else {}


def eventually(check, seconds=45):
    deadline = time.monotonic() + seconds
    last = None
    while time.monotonic() < deadline:
        try:
            result = check()
            if result:
                return result
        except Exception as error:
            last = type(error).__name__
        time.sleep(1)
    raise AssertionError(f"Local smoke condition did not succeed: {last}")


def main():
    now = time.time_ns()
    trace_id, span_id = uuid.uuid4().hex, uuid.uuid4().hex[:16]
    resource = {"attributes": [{"key": "service.name", "value": {"stringValue": "dtp-smoke"}}]}
    scope = {"name": "dtp.synthetic.smoke"}
    root = "http://127.0.0.1:14318/v1/"
    request(root + "traces", {"resourceSpans": [{"resource": resource, "scopeSpans": [{"scope": scope, "spans": [{
        "traceId": trace_id, "spanId": span_id, "name": "dtp.smoke", "kind": 1,
        "startTimeUnixNano": str(now - 1000000), "endTimeUnixNano": str(now)}]}]}]})
    request(root + "metrics", {"resourceMetrics": [{"resource": resource, "scopeMetrics": [{"scope": scope, "metrics": [{
        "name": "dtp.observability.smoke", "gauge": {"dataPoints": [{"timeUnixNano": str(now), "asDouble": 3}]}}]}]}]})
    request(root + "logs", {"resourceLogs": [{"resource": resource, "scopeLogs": [{"scope": scope, "logRecords": [{
        "timeUnixNano": str(now), "observedTimeUnixNano": str(now), "severityNumber": 9,
        "traceId": trace_id, "spanId": span_id, "body": {"stringValue": "OBSERVABILITY_SMOKE"}}]}]}]})
    prometheus = "http://127.0.0.1:19090/api/v1/query?query=" + urllib.parse.quote("dtp_observability_smoke")
    eventually(lambda: request(prometheus).get("data", {}).get("result"))
    eventually(lambda: request("http://127.0.0.1:13200/api/traces/" + trace_id))
    query = urllib.parse.quote('{service_name="dtp-smoke"} |= "OBSERVABILITY_SMOKE"')
    eventually(lambda: request("http://127.0.0.1:13100/loki/api/v1/query_range?query=" + query + "&limit=1").get("data", {}).get("result"))
    eventually(lambda: request("http://127.0.0.1:13000/api/health").get("database") == "ok")
    print(json.dumps({"synthetic_smoke": "passed", "trace_id": trace_id,
                      "checks": ["OTLP HTTP", "Prometheus metric query", "Tempo trace retrieval", "Loki log query", "Grafana health"],
                      "limitations": "Synthetic pipeline smoke; not a financial end-to-end test."}))


if __name__ == "__main__":
    main()
