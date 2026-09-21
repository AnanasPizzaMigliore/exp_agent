# Event-driven expiry capture

This is a paper-inspired adaptation of the event-monitoring idea in
[Event-Driven Proactive Assistive Manipulation with Grounded Vision-Language Planning, III-C1](https://arxiv.org/html/2603.23950v1#S3.SS3.SSS1).
It is not a reproduction of their robot system or optical-flow implementation.
Bounded, local image registration now handles handheld jitter, with no new
ML/native dependency. Raw block differences remain in the logs for comparison;
they no longer trigger an action or invalidate an answer.

## What changed

- `EventCaptureController.kt`: Android-free event lifecycle, onset persistence,
  settling, stalls, immutable capture tickets, pause/resume and request ownership.
- `StabilityMonitor.kt`: sensor/camera adapter; image measurements run off the UI
  thread. State and capture decisions are owned by the main thread.
- `HandheldViewMatcher.kt` and `HandheldViewTracker.kt`: small-shift/rotation
  tolerance across image tiles, with separate fixed instruction, settling and
  submitted-view references. Reference epochs discard work from earlier turns.
- `CameraAnalysisFrame.kt` and `StableFrameBuffer.kt`: ~320x240 motion measurements
  and a bounded buffer of three ~640px colour keyframes, sampled at up to 2 Hz.
  One pre-event snapshot is pinned separately until its turn is complete.
- `ExpiryFragment.kt`: reserve a ticket before calling CameraX, including manual
  captures. Camera callbacks carry that ticket and cannot affect another session.
- `InspectionController.kt`: validate capture, compare event-before and current
  still in verification, reject stale responses, and permit one automatic recovery
  attempt before asking for manual input. Gemini/model prompts are unchanged.
- `GateConditions.kt`: selects either the instruction-conditioned `E_full` gate
  or the live `Q_quality_aware` comparator. Both use the same ticket-owning
  lifecycle; Q changes only readiness and returned-view freshness rules.
- `GuidanceSpeaker.kt`: queued and active speech block auto-submission; flushed
  utterance callbacks cannot unblock replacement speech.

The event sequence is waiting for movement -> tracking -> settling -> capture
pending -> request running. Renewed activity restarts settling. Auto captures that
become unstable while CameraX is capturing are rejected. Manual capture bypasses
visual eligibility but not the session/one-request-at-a-time guards.

An initial observation and an explicit hold-still/recovery observation do not
require movement. Other automatic turns require a persistent, meaningfully
different view relative to the instruction reference, followed by settling. A ten-second
stall prompts the user but does not manufacture an event or force an upload.

## Both hands can tremble

Three questions are now answered separately:

1. **Is the current view sufficiently quiet to capture?** Compare it to a fixed
   settling anchor with small-motion tolerance. Reset that anchor/window when the
   view departs from it. Do not require perfectly stationary pixels.
2. **Did the view meaningfully change after the instruction?** Compare to the
   fixed instruction reference, not just the preceding frame. Slow deliberate
   movement accumulates. Returning to the original view cancels action eligibility.
3. **Does an arriving answer still concern the current view?** Compare to a
   separate submitted-view reference. A temporary shake/occlusion is not a
   permanent stale flag: automatic results can become fresh again after the
   matching view has returned and remained quiet for 300 ms.

Each textured tile can shift independently within a small search window, allowing
the package and background to jitter differently. Gyroscope activity is only a
large-motion capture veto; it is never evidence that the package changed.
Multiple textured tiles must agree. Weak texture and ambiguous scores are UNKNOWN,
not evidence of stability. Automatic answers require a recent observed match.
Manual capture remains available without usable visual measurements; however,
once a different view is observed, UNKNOWN cannot erase that warning.

The submitted reference is the nearest processed analysis thumbnail at CameraX
acceptance, **not the actual uploaded still**. Capture-time stability and callback
guards reduce this timing discrepancy but do not eliminate it, especially for
manual captures. Pixel-exact still/analysis registration is not claimed.

The pre-event keyframe is not always available (startup, analysis gaps, etc.).
Verification then uses the previous submitted frame if available, or is skipped.
The observation log explicitly records `before_source`; do not analyse these as
equivalent event-aligned pairs. Recovery/resume discards the old image reference
and gives the refresh its own instruction ID.

CameraX still pixels and the analysis keyframe are normalised to the same target
orientation. See the [CameraX capture callback contract](https://developer.android.com/reference/kotlin/androidx/camera/core/ImageCapture.OnImageCapturedCallback#onCaptureSuccess(androidx.camera.core.ImageProxy)).

## Engineering defaults, not validated user thresholds

| Parameter | Default |
|---|---|
| Analysis | approximately 10 Hz |
| Sustained onset | 250 ms |
| Initial settling | 600 ms |
| Settling after movement / explicit refresh | 900 ms |
| Maximum observed-frame gap for settling | 300 ms |
| Registration thumbnail | longest edge at most 96 px; normally 80x60 |
| Local alignment allowance | +/-2 thumbnail pixels; rotations 0, +/-1 and +/-2 degrees |
| Registered tile residual | 1 minus normalized cross-correlation |
| SAME | 75th-percentile residual <0.18; at most 20% changed tiles |
| DIFFERENT | at least 30% changed tiles (minimum 2), or 2 strongly changed tiles |
| Changed / strongly changed tile | residual >0.35 / >0.65 |
| Minimum usable texture | 3 tiles; luminance standard deviation >=7 |
| Gyroscope capture veto | >=0.80 rad/s RMS; no action-onset role |
| Returned-view match hold | 300 ms; analysis age <=300 ms |
| Stall notification | 10 s; no forced capture |
| Camera callback watchdog | 10 s |
| Consecutive automatic recovery attempts | 1, then manual capture |

All lifecycle times are monotonic. These are starting values, not measured
accessibility tolerances. The local matcher fits no scale transform and its shift
window is bounded; changes within that tolerance can nevertheless be ignored.
A pause in the middle
of an action can still outlast the settling threshold. Device/user calibration
is required before relying on automatic capture in a study.

## Logs and privacy

`events.jsonl` now includes `event_onset`, `event_transition`, `event_stalled`,
`capture_reserved`, `capture_accepted`, `capture_rejected`, and `stale_response`.
Observation rows include `event_id`, `capture_token`, `capture_reason`,
`before_source`, and `before_frame_id`. Only selected evidence is uploaded through
the existing model calls; the camera stream itself is not uploaded.

When ablation logging is enabled, each session also writes
`runs/traces/<session_id>/trace.jsonl` with monotonic full-rate controller
samples and a small grayscale fingerprint used for exact replay. These files
are local and never uploaded, but they remain image-derived research data and
must be covered by consent and retention policy. The trace records the selected
live gate, matched `pair_id`, operator/product/scenario identifiers, every arm,
ticket, accepted/rejected still and response-freshness decision.

`stability_sample` retains raw block/gyro diagnostics and adds `settling_relation`,
`settling_residual_p75`, `instruction_relation`, `instruction_residual_p75`,
`informative_tiles`, `changed_tiles`, `submitted_relation`, `last_upload_relation`,
`gyro_capture_veto` and `measurement_ms`. Use these to calibrate the deadband and
check that local processing sustains the intended analysis rate on the phone.

`submitted_relation` and `last_upload_relation` are two different references and
are not interchangeable. The first exists only while a request is in flight and
is dropped when the turn ends, which is the right lifetime for asking whether an
arriving reply still concerns the current view. The second is the frame last
handed to the model and survives until the next one replaces it, which is the
right lifetime for asking whether there is anything new to send. On 18 September
the quality baseline was reading the first for its duplicate test and so saw
UNKNOWN in half of all samples; it now reads the second. **Q_quality_aware
numbers from before that date are not comparable with ones after it.**

As of 21 September, Q is no longer shadow-only. Selecting
`gate=Q_quality_aware` in `run_config.json` makes it own the real camera and
request lifecycle. Its shadow twin is constructed by the same factory; this is
checked by the unit suite. The production Q condition waits 600 ms for a stable
view, requires the persistent last-upload comparison to be DIFFERENT after the
opening observation, enforces a 1.5 s minimum interval, and intentionally
accepts returned responses without an instruction-relative freshness check.

Session identity is two fields, not one. `run_label` is the package in the
user's hand and `block_label` the scripted behaviour being performed; they
shared `run_label` until 18 September, so older logs carry both jammed into one
string ("A_idle_can") and two sessions carry neither, because an empty `label`
in run_config.json suppressed the block instead of deferring to it. The scorer
accepts the legacy prefix form.

`ablation_arms.live_arm` was the literal `"E_full"` in every session logged
before 18 September, including eight that really did run `E_no_settling_hold`.
`session_start.gate` was correct throughout and is what a scorer must read.
`ablation_capture` rows now also carry `capture_reason`; without it an opening
look, which is never required to wait for movement, cannot be told from a
capture that fired although nothing moved.

The stable buffer lives in memory, is bounded, and is cleared on pause/stop/new
session. As before, persistent images require the `storeImages` setting. When
enabled, accepted observation pairs have `frame_NNNN.jpg` and, if available,
`frame_NNNN_before.jpg`, linked by `image_path` and `before_image_path`.
OCR and other text remain in the normal run logs even when image saving is off.

`frame_NNNN.jpg` is the frame as perception read it: bounded to 1152 px on the
long edge and turned upright. Until 18 September the untouched CameraX buffer
was written instead, which put the still on disk in sensor orientation while its
`_before` keyframe was upright, so the pair the observation row describes as
comparable could not be laid side by side. Saving what the model was given also
means the file is the evidence the turn's answer rests on, at the cost of not
keeping the full-resolution original.

Only the success path writes a photograph. A capture whose reply was discarded
as stale, or whose turn ended in a model or network error, is recorded in the
log but has no image, although its `capture_reserved` and `stale_response` rows
are exactly the ones a controller comparison is about. Fixing that is a decision
about how much shop photography to retain, not an oversight to patch quietly.

## Verification and device smoke test

Run `gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`.
`EventCaptureControllerTest` covers onset/offset timing, brief shakes, interrupted
rotations, speech gating, unknown frames, dropped samples, capture invalidation,
manual override, stale responses, bounded buffers, and lifecycle/token isolation.
`HandheldViewMatcherTest` uses synthetic luminance sequences for independent
two-hand jitter, small rotation, exposure shifts, deliberate translation/scale,
changed package faces, accumulated slow motion, restored views and reference
epoch isolation. Synthetic tests are regression checks, not field validation.

Before a user study, test the built APK on the target phone:

1. Start and hold still: one initial image, not repeated uploads.
2. After a movement instruction, do nothing for 10 s: hear a prompt, no forced image.
3. Rotate, pause briefly, continue, then settle: inspect onset and capture timing.
4. Move during a still capture: check rejection and bounded recovery.
5. Move to another view while an API request runs: suppress its stale result. Then
   separately test a shake followed by return to the original view: the answer
   should remain usable after the match hold.
6. Pause/resume and stop/start during capture: no old callback should act.
7. Interrupt the network: one recovery attempt, then an accessible manual fallback.
8. Check portrait/landscape images, small print, low texture, tremor, and glare.
9. Hold both phone and package naturally for 20 s after a rotation instruction:
   tremor alone should cause no capture. Then deliberately rotate and settle:
   expect one capture. Repeat while standing and with a busy shop background.

Keep the model and prompts fixed when comparing the old and new controllers.
Measure premature captures, missed events, duplicate requests, event-end-to-capture
delay, correct-date completion, and total task time. JVM/lint/build success does
not validate CameraX timing, audio behaviour or vision accuracy on a real phone.

## Deliberately not claimed

Movement is not verified action completion. VIEWPOINT/SCALE/POSITION currently
require an event but are not classified locally. There is no package-specific
segmentation/tracker, optical flow, or semantic duplicate classifier. Date-location
retrieval for the planner is described separately in RETRIEVAL.md.
Bounded tile alignment is a heuristic, not full camera-motion compensation:
parallax, larger tremor, changed background, glare, partial occlusion and repetitive
printing can still confuse it. Matching thumbnails do not prove an identical
date, product or semantic view. The existing date-grounding policy is unchanged;
its previously identified limitations still need separate hardening before a
user study. Model inference latency is not reduced by the event controller itself.
