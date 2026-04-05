# Internal Observability Dashboard

This project now includes an internal reliability dashboard generator for GenAI requests.

## What is tracked per request

- Prompt pipeline stages
- Retrieval hits (rank, node id, label, confidence)
- Tool calls (name, latency, success, output preview)
- Provider responses (provider id, latency, response preview)
- Errors (stage + message)

## Evaluation harness dimensions

`BenchmarkCatalog.defaultTasks()` includes benchmark task coverage for:

- reasoning
- instruction-following
- factual QA
- tool-use accuracy
- offline parity vs online

## SLOs

`SloTracker.fromBenchmarks(...)` computes and checks:

- p95 latency
- success rate
- fallback frequency
- hallucination proxy metrics

Use `SloTracker.renderInternalDashboard(report, traces)` to render dashboard Markdown for internal review surfaces.
