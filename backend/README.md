# Expiry inspection backend

> **Not in the current request path.** The agent was ported onto the phone:
> `app/.../agent/VisionClient.kt` calls DashScope directly using credentials
> compiled in from `local.properties`. Nothing in `app/src/main` talks to this
> server, and the app has no field for the `EXPIRY_AUTH_TOKEN` below. Kept as
> the reference implementation and for laptop-side experiments; the setup
> instructions that follow describe that use, not the shipped app.

The agent logic for VScan's "Find expiry date" mode. The phone captures; this
decides what to say next and remembers what has already been looked at.

## Why it is a separate process

Two reasons, and the second is the one that matters day to day.

The Qwen credentials stay here. The phone holds only a shared secret for this
server, so a lost handset does not leak an API key.

More usefully: the prompts, the guard, and the planning policy are what the
research is actually about, and changing any of them here costs a server restart
rather than a Gradle build and a reinstall.

## Running it

```
pip install -r requirements.txt

set DASHSCOPE_API_KEY=...        # the vision model
set EXPIRY_AUTH_TOKEN=...        # what the phone sends as a bearer token
python -m uvicorn server:app --host 0.0.0.0 --port 8000
```

`--host 0.0.0.0` is what lets the phone reach the laptop; bound to localhost the
app will simply time out. Find the laptop's address on the shared network
(`ipconfig`) and enter `http://<address>:8000` under Settings → Expiry
inspection → Guidance server, with the same token under Guidance token.

A debug build can talk to plain `http://` because
`app/src/debug/res/xml/network_security_config.xml` permits cleartext. A release
build cannot, by design: put it behind TLS before it leaves a lab network.

Check reachability from the phone's browser first — `http://<address>:8000/health`
answers without a token. A Windows laptop will usually need the port opened for
private networks, and hotel or campus Wi-Fi frequently isolates clients from each
other entirely, which looks exactly like a broken app.

### Environment

| Variable | Default | Meaning |
| --- | --- | --- |
| `DASHSCOPE_API_KEY` | — | Model credentials. Required. |
| `EXPIRY_AUTH_TOKEN` | — | Shared secret the phone presents. Required; requests are refused without it. |
| `EXPIRY_MODEL` | `qwen3.8-max` | Vision model. Must accept images: all three calls but POLICY send one. |
| `EXPIRY_BASE_URL` | DashScope compatible-mode | Any OpenAI-protocol endpoint. |
| `EXPIRY_LOG_DIR` | `runs` | Event log, and stored frames when consented. |
| `EXPIRY_PERCEPTION_TIMEOUT` | `25` | Seconds. |
| `EXPIRY_VERIFY_TIMEOUT` | `20` | Seconds. |
| `EXPIRY_POLICY_TIMEOUT` | `20` | Seconds. |

## The loop

One captured still per turn, three model calls, one sentence back.

```
POST /sessions                          -> session_id + opening instruction
POST /sessions/{id}/observations        -> next instruction, or the answer
POST /sessions/{id}/stop
GET  /sessions/{id}                     -> whole belief state, for eyeballing a run
GET  /health                            -> no auth; use it to test reachability
```

Inside one observation:

```
PERCEPTION(frame)                 what is visible right now
VERIFY(previous, current, asked)  did the requested movement actually happen
POLICY(belief, obs, verify)       what to say next          (text only, cheap)
```

They are kept apart so that when a session goes wrong you can tell which step
failed. Fused into one call, "it mis-saw the lid" and "it wrongly believed the
user had turned the pot" are indistinguishable in the log.

## Two things it refuses to do

**Confuse "seen" with "inspected."** A face recorded through glare is not a face
that can be ruled out. `RegionRecord` keeps the best quality a surface was ever
seen at and only ever upgrades it, so a later ruined frame cannot erase one clean
look — and a poor look never licenses "the date is not there."

**Speak a date the frame does not support.** `answer_is_grounded()` rejects an
ANSWER when the current perception did not read a legible date, when the reading
looks like a lot code or a print time, or when the policy's date contradicts the
frame's. A blocked answer does not end the session; the user is asked to hold the
code steady and try once more.

## Logging

Every turn appends a line to `runs/events.jsonl`: timestamps, capture metadata,
all three model outputs, latency, tokens, and the model id. That file is the
experiment record — one run per session id.

Frames are written to `runs/images/<session>/` **only** when the session was
started with `store_images`, which the app sends only when the user has turned it
on in Settings. These are photographs taken in a shop; treat the directory as
consented research data with a retention policy, not as a cache.

## Tests

```
python -m pytest -q
```

The model calls are stubbed, so what is under test is the session machinery:
memory across turns, frame supersession, the grounding guard, and the failure
paths a phone in a supermarket actually hits — a stopped session with a request
in flight, a superseded frame, malformed model output, an unreachable upstream.
No credentials needed.
