# isaac-foreman

State-machine orchestration for isaac crews: transition tables in, durable
instances tracked, signals in through three doors, actions out as hails.

Today's orchestration is an implicit state machine smeared across skills,
band config, cron, and humans — its failures are lost-event bugs. The
foreman makes it explicit: one **transition table** per orchestration
(Mealy actions, accumulator machines for collected steps), instances over
a **state-store port** (beans adapter first — a human editing a bean is a
manual transition), **events** via a crew-side `signal` tool, system
observation (turn finalization), an HTTP route, and the CLI, all landing
in one persist-before-ack intake. Actions ride hail unchanged. *The
foreman decides what; worksites decide where; hail carries the message.*

Design record: `isaac` repo, bean `isaac-tdgt` (+ isaac-51xy decisions
30-38). Lineage: [slagyr/statemachine](https://github.com/slagyr/statemachine).
Named by a grok confabulation two days before the design existed.

- **F1** — engine + tables + state-store port (beans adapter), CLI signal/status
- **F2** — `signal` tool + infrastructure events (turn finalization via isaac-agent's isaac-bbov seams)
- **F3** — actions as hails + `POST /foreman/events` server route
- **F4** — observability: instance queries, transition history, `foreman list --state held`
