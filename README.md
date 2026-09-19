# 🍏 Isaac Foreman 👷

<img align="left" width="200" src="https://raw.githubusercontent.com/slagyr/isaac-foreman/main/isaac-foreman.png" alt="isaac-foreman" style="margin-right: 20px; margin-bottom: 10px;">

State-machine orchestration for [Isaac](https://github.com/slagyr/isaac) crews:
transition tables in, durable instances tracked, signals in, actions out as
hails. *The foreman decides what; worksites decide where; hail carries the
message.*

Depends on [isaac-foundation](https://github.com/slagyr/isaac-foundation) and
[isaac-agent](https://github.com/slagyr/isaac-agent). Contributes the `foreman`
CLI, a crew `signal` tool, and `POST /foreman/events`.

<br>

[![Foreman](https://github.com/slagyr/isaac-foreman/actions/workflows/ci-tests.yml/badge.svg)](https://github.com/slagyr/isaac-foreman/actions/workflows/ci-tests.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Clojure](https://img.shields.io/badge/Clojure-1.11%2B-blue?logo=clojure)](https://clojure.org)
[![Babashka](https://img.shields.io/badge/Babashka-1.3%2B-red?logo=clojure)](https://babashka.org)
[![Java](https://img.shields.io/badge/Java-21%2B-orange?logo=openjdk)](https://openjdk.org/)

<br clear="left">

## What's here

- Transition tables (Mealy actions, accumulator machines) and a state-store port (beans adapter first).
- Events via a crew-side `signal` tool, turn-finalization observation, HTTP, and CLI — persist-before-ack intake.
- Actions ride hail unchanged.
- Observability: instance queries, transition history, `foreman list --state held`.

Design record: `isaac` repo, bean `isaac-tdgt` (+ isaac-51xy decisions 30–38). Lineage: [slagyr/statemachine](https://github.com/slagyr/statemachine).

## Development

Sibling checkouts expected:

```
plan/
  isaac-foundation/
  isaac-agent/
  isaac-http/
  isaac-foreman/   # this repo
```

```sh
bb spec       # unit specs
bb features   # acceptance features
bb ci         # specs + features
```

From the JVM:

```sh
clj -M:spec
clj -M:features
```

## Consumer coordinate

```clojure
io.github.slagyr/isaac-foreman {:local/root "../isaac-foreman"}
;; or {:git/url "https://github.com/slagyr/isaac-foreman.git" :git/sha "..."}
```
