import http from 'k6/http';
import { Trend, Rate } from 'k6/metrics';
import exec from 'k6/execution';

// Secret-bearing fixture file is generated only in target; never print it.
const config = JSON.parse(open(__ENV.BENCH_CONFIG));
const queryMode = __ENV.BENCH_MODE === 'query';
const duration = Number(__ENV.BENCH_SECONDS || 10);
const rates = (__ENV.BENCH_RATES || '2,5,10').split(',').map(Number);
const scenarios = {};
rates.forEach((rate, index) => {
  scenarios[`tier${index}`] = { executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration: `${duration}s`,
    startTime: `${duration * index}s`, preAllocatedVUs: 4, maxVUs: 20, gracefulStop: '15s' };
});
export const options = { scenarios, discardResponseBodies: true, summaryTrendStats: ['avg','min','med','max','p(95)','p(99)'],
  thresholds: { expected_response: ['rate==1'] } };
const durationMetric = new Trend(queryMode ? 'activity_duration' : 'admission_duration', true);
const expected = new Rate('expected_response');
export default function () {
  const index = exec.scenario.iterationInTest;
  const api = config.apis[index % config.apis.length];
  const pair = config.pairs[__ENV.BENCH_HOT === 'true' ? 0 : index % config.pairs.length];
  let response;
  if (queryMode) {
    response = http.get(`${config.activity}/v1/activity/transfers/${config.transfers[index % config.transfers.length]}`,
      { headers: { Authorization: `Bearer ${config.activityToken}` }, timeout: '15s', tags: { name: 'GET Activity receipt' } });
  } else {
    response = http.post(`${api}/v1/transfers`, JSON.stringify({ sourceAccountId: pair.source, destinationAccountId: pair.destination, amountCents: 1, currency: 'BRL' }),
      { headers: { Authorization: `Bearer ${config.token}`, 'Content-Type': 'application/json',
        'Idempotency-Key': `${__ENV.BENCH_LABEL}-${exec.scenario.name}-${index}` }, timeout: '15s', tags: { name: 'POST transfer' } });
  }
  durationMetric.add(response.timings.duration);
  expected.add(response.status === (queryMode ? 200 : 202));
}
export function handleSummary(data) { return { [__ENV.BENCH_SUMMARY]: JSON.stringify(data, null, 2) }; }
