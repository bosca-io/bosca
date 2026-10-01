import http from "k6/http";
import ws from "k6/ws";
import { check, sleep } from "k6";

const GRAPHQL_URL = __ENV.GRAPHQL_URL || "http://localhost:8080/graphql";
const BASE_URL = GRAPHQL_URL.replace(/\/graphql$/, "");
const WS_URL = GRAPHQL_URL.replace(/^http/, "ws").replace(/\/graphql$/, "/graphqlws");
const JSON_HEADERS = { headers: { "Content-Type": "application/json" } };

// ---------------------------------------------------------------------------
// Phase 1: REST / health endpoints (GET)
// ---------------------------------------------------------------------------
const restGetEndpoints = [
  { name: "health", path: "/api/v1/health" },
  { name: "ready", path: "/api/v1/ready" },
  { name: "graphiql", path: "/graphiql" },
  { name: "installation", path: "/api/v1/installation" },
];

// ---------------------------------------------------------------------------
// Phase 2: REST endpoints that exercise auth / error paths
// ---------------------------------------------------------------------------
const restPostEndpoints = [
  {
    name: "login-unauth",
    path: "/api/v1/security/login",
    body: JSON.stringify({ email: "warmup@localhost", password: "warmup" }),
    headers: { "Content-Type": "application/json" },
    expect: [401, 403, 400, 200],
  },
  {
    name: "login-form-encoded",
    path: "/api/v1/security/login",
    body: "email=warmup%40localhost&password=warmup",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    expect: [401, 403, 400, 200],
  },
];

// ---------------------------------------------------------------------------
// Phase 3: Error / edge-case paths (warm up exception handling code)
// ---------------------------------------------------------------------------
const errorEndpoints = [
  { name: "404-path", method: "GET", path: "/does-not-exist", expect: [404] },
  {
    name: "malformed-graphql",
    method: "POST",
    path: "/graphql",
    body: JSON.stringify({ query: "{ __INVALID { " }),
    headers: { "Content-Type": "application/json" },
    expect: [200, 400],
  },
  {
    name: "empty-body-graphql",
    method: "POST",
    path: "/graphql",
    body: "",
    headers: { "Content-Type": "application/json" },
    expect: [200, 400],
  },
];

// ---------------------------------------------------------------------------
// Phase 4: CORS preflight
// ---------------------------------------------------------------------------
const corsEndpoints = [
  {
    name: "cors-preflight-graphql",
    path: "/graphql",
    headers: {
      Origin: "http://localhost:3000",
      "Access-Control-Request-Method": "POST",
      "Access-Control-Request-Headers": "content-type,authorization",
    },
  },
  {
    name: "cors-preflight-api",
    path: "/api/v1/content/collection/00000000-0000-0000-0000-000000000000",
    headers: {
      Origin: "http://localhost:3000",
      "Access-Control-Request-Method": "GET",
    },
  },
];

// ---------------------------------------------------------------------------
// Phase 5: GraphQL queries via POST (diverse domain coverage)
// ---------------------------------------------------------------------------
const graphqlQueries = [
  // introspection — warms up schema reflection
  {
    name: "introspection",
    query:
      "{ __schema { types { name kind } directives { name locations args { name } } } }",
  },
  {
    name: "introspection-type",
    query:
      '{ __type(name: "Query") { name fields { name type { name kind ofType { name } } } } }',
  },
  // server
  { name: "server-now", query: "{ server { now } }" },
  // languages
  {
    name: "languages",
    query: "{ languages { all { tag name localName } } }",
  },
  // content - collections
  {
    name: "collections-root",
    query: "{ content { collections { root { id name } } } }",
  },
  // content - metadata
  {
    name: "find-metadata",
    query:
      '{ content { findMetadata(query: { limit: 5 offset: 0 }) { id name languageTag } } }',
  },
  // content - workflows
  {
    name: "workflows",
    query:
      "{ content { workflows { all { id name description } } } }",
  },
  // content - models
  {
    name: "models",
    query: "{ content { models { all { id name type } } } }",
  },
  // search
  {
    name: "search",
    query:
      '{ search { search(query: { query: "test" limit: 10 offset: 0 }) { estimatedHits } } }',
  },
  // configurations
  {
    name: "configurations",
    query: "{ configurations { all { key value } } }",
  },
  // profiles
  {
    name: "profiles",
    query:
      "{ profiles { all(limit: 5 offset: 0) { id name type visibility } } }",
  },
  // security
  {
    name: "security-groups",
    query: "{ security { groups { all { id name } } } }",
  },
  // persisted queries
  {
    name: "persisted-queries",
    query: "{ persistedQueries { all { id name } } }",
  },
  // agents
  {
    name: "agents",
    query: "{ agents { all { id name } } }",
  },
  // storage systems
  {
    name: "storage-systems",
    query: "{ storageSystems { all { id name } } }",
  },
];

// ---------------------------------------------------------------------------
// Phase 6: GraphQL via GET (exercises the GET handler path)
// ---------------------------------------------------------------------------
const graphqlGetQueries = [
  { name: "get-server-now", query: "{ server { now } }" },
  {
    name: "get-languages",
    query: "{ languages { all { tag name } } }",
  },
  {
    name: "get-introspection",
    query: "{ __schema { queryType { name } mutationType { name } subscriptionType { name } } }",
  },
];

// ---------------------------------------------------------------------------
// Phase 7: GraphQL mutations (safe / idempotent)
// ---------------------------------------------------------------------------
const graphqlMutations = [
  {
    name: "mutation-clear-cache",
    query: "mutation { clearCache }",
  },
];

// ---------------------------------------------------------------------------
// Execution plan — each entry is a function that runs one warmup action
// ---------------------------------------------------------------------------
const steps = [];

// REST GETs
for (const ep of restGetEndpoints) {
  steps.push(() => {
    const res = http.get(`${BASE_URL}${ep.path}`);
    check(res, {
      [`${ep.name} status ok`]: (r) => r.status === 200,
    });
  });
}

// REST POSTs (auth / form paths)
for (const ep of restPostEndpoints) {
  steps.push(() => {
    const res = http.post(`${BASE_URL}${ep.path}`, ep.body, {
      headers: ep.headers,
    });
    check(res, {
      [`${ep.name} expected status`]: (r) => ep.expect.includes(r.status),
    });
  });
}

// Error / edge-case paths
for (const ep of errorEndpoints) {
  steps.push(() => {
    let res;
    if (ep.method === "GET") {
      res = http.get(`${BASE_URL}${ep.path}`);
    } else {
      res = http.post(`${BASE_URL}${ep.path}`, ep.body, {
        headers: ep.headers,
      });
    }
    check(res, {
      [`${ep.name} expected status`]: (r) => ep.expect.includes(r.status),
    });
  });
}

// CORS preflight
for (const ep of corsEndpoints) {
  steps.push(() => {
    const res = http.options(`${BASE_URL}${ep.path}`, null, {
      headers: ep.headers,
    });
    check(res, {
      [`${ep.name} preflight ok`]: (r) =>
        r.status === 200 || r.status === 204,
    });
  });
}

// GraphQL queries via POST
for (const q of graphqlQueries) {
  steps.push(() => {
    const res = http.post(
      GRAPHQL_URL,
      JSON.stringify({ query: q.query }),
      JSON_HEADERS,
    );
    check(res, {
      [`${q.name} status 200`]: (r) => r.status === 200,
    });
  });
}

// GraphQL queries via GET
for (const q of graphqlGetQueries) {
  steps.push(() => {
    const url = `${GRAPHQL_URL}?query=${encodeURIComponent(q.query)}`;
    const res = http.get(url);
    check(res, {
      [`${q.name} status 200`]: (r) => r.status === 200,
    });
  });
}

// GraphQL mutations
for (const m of graphqlMutations) {
  steps.push(() => {
    const res = http.post(
      GRAPHQL_URL,
      JSON.stringify({ query: m.query }),
      JSON_HEADERS,
    );
    check(res, {
      [`${m.name} status 200`]: (r) => r.status === 200,
    });
  });
}

// WebSocket (graphql-transport-ws lifecycle)
steps.push(() => {
  const res = ws.connect(
    WS_URL,
    { headers: { "Sec-WebSocket-Protocol": "graphql-transport-ws" } },
    (socket) => {
      socket.on("open", () => {
        socket.send(JSON.stringify({ type: "connection_init", payload: {} }));
      });

      socket.on("message", (msg) => {
        const data = JSON.parse(msg);
        if (data.type === "connection_ack") {
          // Subscribe to trigger subscription code paths
          socket.send(
            JSON.stringify({
              id: "warmup-1",
              type: "subscribe",
              payload: { query: "subscription { metadata { id } }" },
            }),
          );

          // Immediately complete the subscription
          socket.send(
            JSON.stringify({ id: "warmup-1", type: "complete" }),
          );

          socket.close();
        }
      });

      socket.setTimeout(() => {
        socket.close();
      }, 5000);
    },
  );

  check(res, {
    "ws connected": (r) => r && r.status === 101,
  });
});

// ---------------------------------------------------------------------------
// k6 options — run all steps 3 times
// ---------------------------------------------------------------------------
export const options = {
  iterations: steps.length * 3,
  vus: 1,
};

let iteration = 0;

export default function () {
  const idx = iteration % steps.length;
  iteration++;
  steps[idx]();
  sleep(0.05);
}
