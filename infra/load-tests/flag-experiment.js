import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { graphqlQuery, login, authHeaders, defaultHeaders, defaultThresholds } from './config.js';

/**
 * Feature flag + A/B experiment end-to-end load test.
 *
 * Mirrors the workflow in scripts/test-flag-experiment.sh:
 *   1. Bucketing: calls featureFlags.evaluate with synthetic installation IDs.
 *   2. Conversion events: posts analytics events to the collector for a
 *      fraction of bucketed devices (simulating realistic conversion rates).
 *   3. Aggregation: triggers experiments.aggregateResults to populate results.
 *   4. Results fetch: reads back the aggregated experiment results.
 *
 * Environment variables:
 *   FLAG_KEY          Flag key to evaluate (required)
 *   EXPERIMENT_ID     Experiment UUID — when set, triggers aggregation after events
 *   ANALYTICS_URL     Analytics collector base URL  (default: http://localhost:8081)
 *   DEVICES           Number of synthetic devices per VU iteration  (default: 50)
 *   EVENTS_PER_DEVICE Events per converting device    (default: 3)
 *   EVENT_TYPE        Analytics event type to fire     (default: interaction)
 *   BASELINE_RATE     Control conversion rate fraction (default: 0.40)
 *   TREATMENT_LIFT    Additional rate for treatments   (default: 0.15)
 *   SKIP_EVENTS       Set to "true" to skip event posting (bucketing only)
 *   SKIP_AGGREGATE    Set to "true" to skip aggregation
 *   FLUSH             Set to "true" to call /api/v1/events/flush after events
 *   WAIT_SECONDS      Seconds to sleep after events before aggregation (default: 5)
 *   TEST_USER         Login identifier for admin mutations (default: admin)
 *   TEST_PASS         Login password (default: password)
 *   SMOKE             Set to "true" for minimal 1-VU smoke run
 */

const FLAG_KEY = __ENV.FLAG_KEY;
if (!FLAG_KEY) {
  throw new Error('FLAG_KEY environment variable is required. Usage: k6 run -e FLAG_KEY=my-flag flag-experiment.js');
}

const ANALYTICS_URL = __ENV.ANALYTICS_URL || 'http://localhost:8081';
const EXPERIMENT_ID = __ENV.EXPERIMENT_ID || '';
const DEVICES = parseInt(__ENV.DEVICES || '50', 10);
const EVENTS_PER_DEVICE = parseInt(__ENV.EVENTS_PER_DEVICE || '3', 10);
const EVENT_TYPE = (__ENV.EVENT_TYPE || 'interaction').toLowerCase();
const BASELINE_RATE = parseFloat(__ENV.BASELINE_RATE || '0.40');
const TREATMENT_LIFT = parseFloat(__ENV.TREATMENT_LIFT || '0.15');
const SKIP_EVENTS = __ENV.SKIP_EVENTS === 'true';
const SKIP_AGGREGATE = __ENV.SKIP_AGGREGATE === 'true';
const DO_FLUSH = __ENV.FLUSH === 'true';
const WAIT_SECONDS = parseInt(__ENV.WAIT_SECONDS || '5', 10);
const TEST_USER = __ENV.TEST_USER || 'admin';
const TEST_PASS = __ENV.TEST_PASS || 'password';
const isSmoke = __ENV.SMOKE === 'true';

// Custom metrics for experiment-specific tracking.
const bucketingDuration = new Trend('flag_evaluate_duration', true);
const eventPostDuration = new Trend('event_post_duration', true);
const bucketingErrors = new Rate('flag_evaluate_errors');
const eventPostErrors = new Rate('event_post_errors');
const devicesEvaluated = new Counter('devices_evaluated');
const eventsFired = new Counter('events_fired');
const devicesConverted = new Counter('devices_converted');
const devicesSkipped = new Counter('devices_skipped');

export const options = isSmoke
  ? {
      vus: 1,
      iterations: 1,
      thresholds: {
        flag_evaluate_errors: ['rate<0.10'],
        event_post_errors: ['rate<0.10'],
      },
    }
  : {
      scenarios: {
        experiment_run: {
          executor: 'per-vu-iterations',
          vus: parseInt(__ENV.VUS || '1', 10),
          iterations: 1,
        },
      },
      thresholds: Object.assign({}, defaultThresholds, {
        flag_evaluate_errors: ['rate<0.01'],
        event_post_errors: ['rate<0.01'],
        'flag_evaluate_duration': ['p(95)<300'],
        'event_post_duration': ['p(95)<500'],
      }),
    };

export function setup() {
  const token = login(TEST_USER, TEST_PASS);
  if (!token) {
    console.warn('Auto-login failed. Aggregation and flush require admin auth.');
  }
  return { token };
}

// -- Queries & mutations ------------------------------------------------

const EVAL_QUERY = `
  query Eval($key: String!, $iid: String!, $device: AnalyticsDevice) {
    featureFlags {
      evaluate(flagKey: $key, installationId: $iid, device: $device) {
        variationKey
        experimentId
        value
      }
    }
  }
`;

const AGG_MUTATION = `
  mutation Agg($id: UUID!) {
    experiments {
      aggregateResults(experimentId: $id)
    }
  }
`;

const RESULTS_QUERY = `
  query R($id: UUID!) {
    experiments {
      experiment(id: $id) {
        id
        status
        results(offset: 0, limit: 200) {
          variationKey
          goal { name metricType }
          impressions
          conversions
          conversionRate
          confidenceLevel
          liftOverControl
          mean
          variance
          probabilityBeatsControl
          expectedLoss
          adjustedMean
          adjustedVariance
        }
      }
    }
  }
`;

// -- Helpers ------------------------------------------------------------

function makeDevice(installationId) {
  return {
    installationId,
    manufacturer: 'k6',
    model: 'load-tester',
    platform: 'web',
    primaryLocale: 'en-US',
    systemName: 'k6',
    timezone: 'UTC',
    type: 'desktop',
    version: '1.0.0',
  };
}

function makeEventsPayload(installationId) {
  const now = Date.now();
  const events = [];
  for (let i = 0; i < EVENTS_PER_DEVICE; i++) {
    events.push({
      type: EVENT_TYPE,
      created: now,
      client_id: installationId,
      element: { id: 'test-element', type: 'click', content: [], extras: {} },
      page: { path: '/', url: 'http://test.local/', title: 'Test' },
    });
  }
  return {
    context: {
      app_id: 'k6-flag-experiment',
      app_version: '1.0.0',
      device: {
        installation_id: installationId,
        manufacturer: 'k6',
        model: 'load-tester',
        platform: 'test',
        primary_locale: 'en-US',
        system_name: 'k6',
        timezone: 'UTC',
        type: 'desktop',
        version: '1.0',
      },
      session_id: `k6-sess-${__VU}-${__ITER}`,
    },
    events,
    sent: now,
    sent_micros: 0,
  };
}

function shouldConvert(isControl) {
  const rate = isControl ? BASELINE_RATE : Math.min(1, BASELINE_RATE + TREATMENT_LIFT);
  return Math.random() < rate;
}

// -- Main ---------------------------------------------------------------

export default function (data) {
  const runId = `${__VU}-${__ITER}-${Date.now().toString(36)}`;
  const devicePrefix = `k6-${runId}-dev`;

  // Track bucketing distribution per variation.
  const buckets = {};
  // Device -> variation mapping for the conversion step.
  const deviceVariations = [];
  let controlVariation = null;

  // Step 1: Bucketing
  group('bucketing', () => {
    for (let i = 0; i < DEVICES; i++) {
      const iid = `${devicePrefix}-${i}`;
      const res = graphqlQuery(EVAL_QUERY, {
        key: FLAG_KEY,
        iid,
        device: makeDevice(iid),
      });

      bucketingDuration.add(res.timings.duration);
      devicesEvaluated.add(1);

      const ok = check(res, {
        'evaluate status 200': (r) => r.status === 200,
        'evaluate has variationKey': (r) => {
          try {
            return JSON.parse(r.body).data.featureFlags.evaluate.variationKey !== undefined;
          } catch (_e) {
            return false;
          }
        },
      });

      if (!ok) {
        bucketingErrors.add(1);
        continue;
      }
      bucketingErrors.add(0);

      try {
        const body = JSON.parse(res.body);
        const variation = body.data.featureFlags.evaluate.variationKey;
        buckets[variation] = (buckets[variation] || 0) + 1;
        deviceVariations.push({ iid, variation });
      } catch (_e) {
        // logged via check above
      }
    }

    // Determine control variation (alphabetically first, matches server convention).
    const sortedVariations = Object.keys(buckets).sort();
    if (sortedVariations.length > 0) {
      controlVariation = sortedVariations[0];
    }

    console.log(`[VU ${__VU}] Bucketing distribution: ${JSON.stringify(buckets)}`);
  });

  // Step 2: Conversion events
  if (!SKIP_EVENTS && deviceVariations.length > 0) {
    group('conversion-events', () => {
      let fired = 0;
      let skipped = 0;

      for (const { iid, variation } of deviceVariations) {
        const isControl = variation === controlVariation;
        if (!shouldConvert(isControl)) {
          skipped++;
          devicesSkipped.add(1);
          continue;
        }

        const payload = makeEventsPayload(iid);
        const res = http.post(
          `${ANALYTICS_URL}/api/v1/events`,
          JSON.stringify(payload),
          { headers: defaultHeaders },
        );

        eventPostDuration.add(res.timings.duration);
        const ok = check(res, {
          'event post accepted': (r) => r.status === 200 || r.status === 202,
        });
        if (ok) {
          eventPostErrors.add(0);
          fired++;
          eventsFired.add(EVENTS_PER_DEVICE);
          devicesConverted.add(1);
        } else {
          eventPostErrors.add(1);
        }
      }

      console.log(
        `[VU ${__VU}] Events: ${fired} devices fired ${EVENTS_PER_DEVICE} event(s) each ` +
        `(${fired * EVENTS_PER_DEVICE} total), ${skipped} silent`,
      );

      // Optional flush
      if (DO_FLUSH && data.token) {
        const flushRes = http.get(`${ANALYTICS_URL}/api/v1/events/flush`, {
          headers: Object.assign({}, defaultHeaders, authHeaders(data.token)),
        });
        check(flushRes, {
          'flush accepted': (r) => r.status >= 200 && r.status < 300,
        });
      }

      // Wait for collector to flush to Iceberg before aggregation.
      if (WAIT_SECONDS > 0 && EXPERIMENT_ID && !SKIP_AGGREGATE) {
        sleep(WAIT_SECONDS);
      }
    });
  }

  // Step 3: Aggregation + results
  if (EXPERIMENT_ID && !SKIP_AGGREGATE) {
    group('aggregation', () => {
      if (!data.token) {
        console.warn('[VU ' + __VU + '] No auth token; skipping aggregation (admin-gated).');
        return;
      }
      const headers = authHeaders(data.token);

      const aggRes = graphqlQuery(AGG_MUTATION, { id: EXPERIMENT_ID }, headers);
      check(aggRes, {
        'aggregateResults accepted': (r) => r.status === 200,
        'aggregateResults no errors': (r) => {
          try {
            return !JSON.parse(r.body).errors;
          } catch (_e) {
            return false;
          }
        },
      });

      // Fetch results
      const resultsRes = graphqlQuery(RESULTS_QUERY, { id: EXPERIMENT_ID }, headers);
      const resultsOk = check(resultsRes, {
        'results status 200': (r) => r.status === 200,
        'results has experiment': (r) => {
          try {
            return JSON.parse(r.body).data.experiments.experiment !== null;
          } catch (_e) {
            return false;
          }
        },
      });

      if (resultsOk) {
        try {
          const body = JSON.parse(resultsRes.body);
          const exp = body.data.experiments.experiment;
          console.log(`[VU ${__VU}] Experiment ${exp.id} status=${exp.status}`);
          for (const r of (exp.results || [])) {
            console.log(
              `  ${r.variationKey} [${r.goal?.name || 'unknown'}]: ` +
              `imp=${r.impressions} conv=${r.conversions} ` +
              `rate=${r.conversionRate?.toFixed(4)} ` +
              `lift=${r.liftOverControl?.toFixed(2)}% ` +
              `conf=${r.confidenceLevel?.toFixed(4)}`,
            );
          }
        } catch (_e) {
          // logged via check
        }
      }
    });
  }
}
