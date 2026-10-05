# isaac.foreman — Isaac's operating handbook, orchestration machines

You are a crew running inside Isaac. This chapter is isaac-foreman's: the
module that owns **machines** — durable state-machine instances that react
to events and fire actions, including submitting turns to other crews. If
you haven't read `isaac.foundation` (config mechanics, modules,
`handbook__configure` itself) or `isaac.agent` (crews, sessions,
frequencies — see `isaac.agent#frequencies` — turns, resource pools) yet,
read those first — this chapter assumes both.

Foreman doesn't decide *where* work happens or *who* does it — it decides
*what happens next* given the current state and an event. A machine's
`:turn` action hands off to `isaac.agent` for the actual work; Foreman only
tracks the instance's state and durably records what occurred.

## Machines

A **machine** is a config entity: a named transition table describing
states, an initial state, and actions to fire on each transition. Machines
live in the `machines` table — one file per machine under
`config/machines/<name>.edn`, or inline under the root `:machines` key —
and can also be edited through `handbook__configure` like any other entity.

A machine's shape:

- `:initial` — the state a brand-new instance is born into
  (`config:machines["lighthouse-watch"].initial`). Required.
- `:transitions` — an ordered list of rows, each
  `{:start :event :end :actions}`. `:start` names the state the row applies
  from, or `:*` to match **any** state; an explicit row for the current
  state always wins over a `:*` row for the same event. `:actions` is
  always a **vector** of action names, even for a single action or none.
  (`config:machines["lighthouse-watch"].transitions`)
- `:states` — optional, per-state `:entry` / `:exit` action lists, fired in
  addition to a transition's own `:actions`
  (`config:machines["lighthouse-watch"].states`). On a fired transition,
  actions run in this order: the **old** state's `:exit` actions, then the
  transition row's own `:actions`, then the **new** state's `:entry`
  actions.
- `:actions` — named actions local to this machine
  (`config:machines["lighthouse-watch"].actions`). See Actions, below.
- `:observers` — a list of observer ids notified after a handled
  transition (`config:machines["lighthouse-watch"].observers`). The only
  built-in id is `:log`, which emits a structured `:foreman/transition`
  log line (machine, instance, from, to, event) — see `isaac.foundation`'s
  Logs section for reading it back. Any other id has to be registered in
  code by a module (`isaac.foreman.observer/register!`); there's no config
  path to add a new observer *implementation*, only to attach an already-
  registered one's id to a machine.

A transition row naming an action absent from both the machine's own
`:actions` and the shared pool (see below) is a **config error**: the
machine is rejected at load/validate time, naming the machine and the
dangling action. The same load-time check rejects a transition row that
still uses the retired singular `:action` key, or an `:actions` value that
isn't a vector — both name the offending machine and field.

### Troubleshooting

- **`isaac config validate` rejects a machine, naming a dangling action.**
  An action referenced by a transition row, or a state's `:entry`/`:exit`,
  isn't defined in that machine's own `:actions` map or in the shared
  `foreman.actions` pool (see Actions). Add the action or fix the typo in
  the reference.
- **A machine is rejected citing `:action` or a non-vector `:actions`.**
  Transition rows only ever take a **vector** under the plural `:actions`
  key — rewrite `:action [:foo]` as `:actions [:foo]`, and a bare
  `:actions :foo` as `:actions [:foo]`.
- **A wildcard (`:*`) row never seems to fire.** Check for an explicit row
  matching the same event from the instance's *current* state — an
  explicit row always wins, even if the wildcard row appears earlier in
  the list.

## Actions

An action is a named `{:type ... }` map, defined either in a machine's own
`:actions` (`config:machines["lighthouse-watch"].actions`, shadows the
shared pool for that machine) or in the shared pool at
`config:foreman.actions`, available to every machine that doesn't define
its own action of the same name. Three types are declared in the schema:

- **`:log`** — prints `:message` to stdout when the action fires. Nothing
  else; it doesn't persist, doesn't ride hail, doesn't submit a turn.
  Fields: `config:foreman.actions["tend-lamp"].message`.
- **`:turn`** — submits one turn to `isaac.agent` when the transition
  fires. Fields: `config:foreman.actions["tend-lamp"].frequencies` (an
  `isaac.agent#frequencies` map — crew/session/tags/prefer/create; string
  values are templated before submission: Foreman never itself picks a
  session or a directory), `config:foreman.actions["tend-lamp"].resource-pools` (pool
  ids the turn must lease before it runs — same admission mechanism as any
  other turn, see `isaac.agent`), and
  `config:foreman.actions["tend-lamp"].prompt` (a template filled at
  submission time — see Prompt templates, below). With `:output {:data :key}`
  the reply is stored in the instance data as `:key` before the turn outcome
  is applied; Foreman writes a preamble instructing the model to reply with
  only that content. Every submitted turn's preamble first names the Foreman
  machine, instance, and state just entered, whether or not `:output` is set.
  The instance id is the bean id for bean-work machines; use `foreman__data`
  to pull instance data when that tool is available. `:output :event` adds
  the valid event reply lines after the identity line.
- **`:exec`** — runs a declared argv command. With `:output {:data :key}`
  its stdout is stored as instance data before the next action executes.
- **`:notify`** — declared as a valid `:type` in the schema, but **nothing
  in Foreman currently consumes it**: unlike `:log` (executed immediately)
  and `:turn` (submitted and tracked as pending), a `:notify` action
  becomes a pending entry that nothing ever resolves — it neither prints,
  nor submits, nor clears. Don't rely on `:notify` for anything yet.
  `[verify]`

### Action templates

Every string value anywhere in an action's spec is rendered when that action
fires: `:prompt`, `:frequencies` (including nested vectors), `:resource-pools`,
`:command`, `:cwd`, and `:log`'s `:message`. Keywords, numbers, and map keys
remain unchanged. The fixed variables are:

- `{{machine}}` — the machine name.
- `{{instance}}` — the instance id.
- `{{state}}` — the state just entered.
- `{{event}}` — the event that fired the transition.
- `{{data.<path>}}` — a dotted path in the instance's current data, including
  data from the triggering event and earlier actions. Missing data paths fill
  empty because data can arrive over time.

`isaac config validate` rejects unknown placeholders with the machine and
action named in the error; missing data is not a config error. This is plain
string substitution through the foundation template engine, not a soul or a
full prompt-templating system. Turn targets, prompts, and resource pools are
saved with the pending action so retries keep the original rendered values.
For a session per instance, use `:frequencies {:session "bean-{{instance}}"
:create :if-missing}`.

### Resolution order

When a transition needs action `:tend-lamp`, Foreman looks first in the
firing machine's own `:actions`, then falls back to the shared
`config:foreman.actions` pool. A machine-local action of the same name
always wins — there's no way to "extend" the shared one from a machine,
only to shadow it entirely.

### Troubleshooting

- **A `:turn` action's turn goes to the wrong session, or none at all.**
  Foreman renders string placeholders in `:frequencies` at enqueue, then
  passes the target to `isaac.agent`'s matching. Check the rendered session
  using a machine test's `target is:` assertion, then troubleshoot remaining
  selection issues using `isaac.agent#frequencies`.
- **An action seems to silently do nothing.** If its `:type` is
  `:notify`, that's the current, unimplemented gap above — not a bug in
  your config. Use `:log` or `:turn` for anything you need to actually
  happen.
- **You defined an action on the machine but the shared pool's version
  still fires.** Check the name matches exactly — the machine-local map
  and the shared pool are looked up by the same action name, and a typo in
  either leaves the shared one (or a dangling-action rejection) instead of
  the override you meant.

## Instances, and signaling one

An **instance** is one running copy of a machine, identified by a plain
string id you choose (a session key, an external record id, anything
stable). Birth one with `isaac foreman start <machine> <id>` — it lands at
the machine's `:initial` state; starting an id that already exists is
refused. There's no config path for this — instances are **runtime state**
under Isaac's root, not declarative config, the same distinction
`isaac.foundation`'s Files section draws between `config/` and everything
else.

An instance moves by being **signaled** with an event, through one of four
doors, all converging on the same durable path: the crew tool
`foreman__signal`, `POST /foreman/events`, `isaac foreman signal <machine>
<id> <event>`, and a **turn observer** ref `foreman:<machine>/<instance>`
(attached to a turn with `--observer`, or via a machine's own config —
see Turn actions, below) that automatically signals `:foreman/turn-started`,
`:foreman/turn-ended` (success), `:foreman/turn-failed` (error), and `:foreman/turn-died`
(the process died mid-turn) as that turn's lifecycle unfolds. A successful
turn that called `foreman__signal` reports no `:foreman/turn-ended`: its
signal is the one outcome. Failed and died turns still report their failure.
The `foreman` event namespace is reserved for these observer events; CLI,
HTTP and the crew tool cannot submit events in it.

Every door writes the same **envelope** — event, source, optional data,
crew, session — to the instance's durable event history *before*
acknowledging the caller, and only then attempts the transition
("persist-before-ack"). This makes signaling idempotent: a caller-supplied
event id (`--id` on the CLI, `id` in the HTTP body) that's already in the
instance's history is recorded as a duplicate and produces **no** second
transition, whichever door it arrives through. Recovery also flows through
the same log: on server start, `isaac.foreman.module` sweeps every
instance and re-applies any **received** event that was never followed by
a transition or unhandled record — the case where a process died between
acknowledging an event and consuming it.

An event with **no matching transition** from the instance's current state
is a **refusal**, not a silent no-op, and the instance does not move: the
CLI exits 1 with `no transition for <event> from <state>`; HTTP answers
409 naming the same message; the crew tool's `foreman__signal` result
comes back as a tool error the model can read and correct. The one
exception is the turn-observer door: since a turn's own lifecycle events
(`:foreman/turn-started`, etc.) often have no matching row on a given machine by
design, an unhandled signal from that source is recorded in history and
logged to stderr, but never raised as an error back into the turn — see
Turn actions, below, for the "backstop row" pattern this implies.

`isaac foreman status <machine> <id>` shows one instance: current state,
since-timestamp, any pending actions, and its full transition/unhandled/
duplicate history, each line noting the event id, the door it arrived
through, and (when known) the signaling crew and session. `isaac foreman
list <machine>` surveys every instance of a machine, `--state <s>` narrows
it to one state.

### Troubleshooting

- **A signal is refused with "no transition for `<event>` from
  `<state>`".** The machine has no row (explicit or `:*`) for that event
  from the instance's current state. Check `isaac foreman status` for the
  actual current state before assuming the config is wrong — a prior
  signal may have moved it further than expected.
- **The same signal seems to have been silently ignored a second time.**
  Check `isaac foreman status` history for a `duplicate:` line at that
  event id — a repeated id is expected to be a no-op, on any door, by
  design.
- **An instance you expected to exist reports "unknown instance".** It was
  never started (`isaac foreman start <machine> <id>`), or the id has a
  typo — `isaac foreman list <machine>` shows what actually exists.
- **A turn's lifecycle events aren't moving the instance, and nothing
  looks broken.** Confirm the machine actually has rows for
  `:foreman/turn-started`/`:foreman/turn-ended`/`:foreman/turn-failed`/`:foreman/turn-died` from the
  relevant state — an unhandled turn-observer signal is by design silent
  (history + stderr only), not an error, so a missing row just means
  "nothing happens," not "something's wrong."

## Turn actions, resource pools, and retry

When a transition's actions include a `:turn` action, Foreman submits one
turn to `isaac.agent` and records it as **pending** on the instance (shown
under `pending:` in `isaac foreman status`) until that turn's outcome
comes back through the turn-observer door (see Instances, above). Each
submission's idempotency key is `<machine>/<instance>/<event-id>/
<action>` — `isaac.agent` refuses a second request with the same key, so
re-processing the same event (or retrying, below) never creates a second
turn for it.

If a `:turn` action's submission is **refused** outright at submit time
(most commonly: `resource-pools` names a pool that doesn't exist), the
pending entry records the error and stays pending — nothing is retried
automatically. `isaac foreman retry <machine> <id>` re-attempts every
still-pending `:turn` action on that instance; a submission that already
has an accepted request id is left alone, so retry is always safe to run
speculatively. Foreman also calls retry automatically right after
consuming any event, so a transiently-refused submission (a pool that was
briefly missing, say) often clears on its own once the config is fixed,
without an explicit `foreman retry`.

A `:turn` action whose `resource-pools` are currently **busy** doesn't
fail — the turn is admitted to `isaac.agent`'s own held-turn queue (see
`isaac.agent`) and runs once its pool frees up, same as any other pooled
turn. Because the machine has already moved to its "awaiting the turn"
state by the time the turn itself is merely *parked*, a well-formed
machine should always include a **backstop row** for `:foreman/turn-ended` /
`:foreman/turn-failed` / `:foreman/turn-died` from that awaiting state — the case where
the crew's turn finishes (or dies) without ever calling `foreman__signal`
itself. Without a backstop row, the instance would simply pile up
unhandled turn-lifecycle history and never leave the awaiting state.

### Troubleshooting

- **`isaac foreman status` shows a pending `:turn` action with a
  `failed:` note.** The submission was refused (commonly an unknown
  resource pool). Fix the config the error names, then run `isaac foreman
  retry <machine> <id>` — or just re-signal the event again if the
  machine will still accept it.
- **`foreman retry` reports nothing to do, but you expected a retry.** A
  pending entry with a request id already attached succeeded at submit
  time and is just waiting on the turn to finish — that's not a failure
  state, and retry correctly leaves it alone.
- **An instance seems stuck in an "awaiting turn" state forever.** Check
  whether the machine has a backstop row for `:foreman/turn-ended`/
  `:foreman/turn-failed`/`:foreman/turn-died` from that state — without one, a turn that
  finishes without the crew explicitly signaling leaves the instance
  parked with only unhandled history to show for it.
- **A resource-pool-gated turn seems to have vanished.** It's most likely
  held, not lost — `isaac turns list` (an `isaac.agent` command) shows
  parked turns across the whole system, Foreman or not.

## Machine tests

`isaac foreman test <file.feature>…` runs Gherkin scenarios written
**only** for this module's own fixed step vocabulary, purely against the
pure transition engine — no instance is created, no turn is submitted, no
`:log` action prints, and nothing is persisted. It's a config-testing tool
for machine authors, not a way to exercise real orchestration; relative
feature paths resolve against the Isaac root.

Steps (exact/substring/regex, by the step's own wording):

| Step | Checks |
|---|---|
| `Given the "<machine>" machine` | Load a machine from current config |
| `Given instance "<id>"` | Name the instance (cosmetic only — nothing is persisted) |
| `Given the state is "<state>"` | Start from a state other than `:initial` |
| `When "<event>" is signaled` | Step the pure engine one event |
| `Then the state is "<state>"` | Exact match |
| `Then the signal is unhandled` | The last signal had no matching row |
| `Then the actions are "<a>, <b>"` | Exact, ordered action-name list |
| `Then the actions contain "<a>"` | Membership only |
| `Then there are no actions` | Empty action list |
| `Then the "<action>" prompt is / contains / matches "<text>"` | The filled prompt template |
| `Then the "<action>" target is:` (key/value table) | The action's rendered `frequencies` (plus `resource-pools` if set) |

One line prints per scenario — `PASS <name>` or `FAIL <name> — <step>:
<mismatch>` — and the command exits non-zero if any scenario failed.

### Troubleshooting

- **A machine test can't find a step it expects.** The vocabulary above
  is fixed and complete — there's no way to add a custom step from a
  feature file; an unrecognized step text fails with "unrecognized step"
  naming exactly what wasn't understood.
- **A test passes state/action checks but fails on a signal you expected
  to be unhandled (or vice versa).** An unhandled signal blocks every
  later `Then` check on that scenario except `the signal is unhandled`
  itself — acknowledge it with that step before asserting anything else.
- **`foreman test` output shows real machine names you didn't expect.**
  It reads the *actual* current config's `machines` table, same as any
  other `foreman` subcommand — point it at a root with the fixture config
  you intend, not your production root.
