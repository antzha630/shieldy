# Gunshot Acoustics Inside Buildings — Research & Design Notes

Purpose: ground EchoShield's detection tuning and localization design in the physics
of how gunshots actually behave indoors, and record which choices in the code follow
from it. Read alongside `AudioSensorService.kt` (detection) and
`MeshNetworkManager.kt` (consensus + triangulation).

## 1. What a gunshot actually is, acoustically

A firearm produces up to two distinct acoustic events:

1. **Muzzle blast** — the confined propellant charge venting at the barrel. It is an
   *impulse*: the bulk of the energy lasts only ~3–7 ms. Sound radiates in all
   directions but is concentrated toward the barrel; peak level drops by **20 dB or
   more** when the muzzle faces away from the microphone.
2. **Ballistic shockwave** — a supersonic bullet's Mach cone. Only present for
   supersonic rounds and only along the trajectory; less relevant for a phone that is
   not on the bullet's path.

For a phone-based detector the muzzle blast is the reliable primary cue.

**Design consequences in code:**
- The activity gate treats high **peak-to-RMS ratio** as an impulsive cue
  (`IMPULSIVE_PEAK_RMS_RATIO`), because a 3–7 ms impulse has a very high crest
  factor compared to speech or music.
- A close blast **saturates cheap phone microphones**. We therefore treat microphone
  **clipping** as its own impulsive gate trigger (`CLIP_FRACTION_GATE`) rather than
  discarding the frame. See §3.

## 2. Why indoors is hard

Indoors, the microphone never hears the clean impulse. What arrives is the
**convolution of the gun's report with the room's acoustic impulse response**:

- **Reverberation and multipath** — the impulse reflects and diffracts off walls,
  floors, lockers, and ceilings, arriving many times with decaying amplitude. This
  smears the sharp 3–7 ms impulse into tens or hundreds of milliseconds of clutter.
- **Deconvolution is genuinely hard** — recovering the original report from the
  reverberant tail is an open signal-processing problem, so we should *not* rely on
  precise waveform shape indoors.
- **One shot is heard by many rooms** — reflections and structure-borne sound mean a
  single shot can trigger phones well beyond line of sight. This is simultaneously an
  asset (corroboration for triangulation) and a risk (cascade / "herding"). See §5.

**Design consequences in code:**
- Detection is a **two-tier gate + ML** design, not waveform matching. The ML model
  (YAMNet) provides class evidence; we never assume a clean impulse survived the room.
- Because reverberation and clipping *degrade* ML confidence for a real close shot,
  we **relax the ML threshold when the mic is severely clipped**
  (`CLIPPED_CONFIDENCE_THRESHOLD`) so a genuine loud event is not lost to distortion.

## 3. Microphone clipping

Nearby gunshots routinely drive consumer MEMS microphones to the 16-bit rails. Once
samples pin to ±32767 the waveform is destroyed and normalized ML input loses shape.

Our handling (`AudioSensorService.calculateClipFraction`):
- Count samples within `CLIP_MARGIN` of full scale per frame → `clipFraction`.
- `clipFraction ≥ CLIP_FRACTION_GATE` → open the activity gate (clipping *is* a strong
  nearby-impulse cue).
- `clipFraction ≥ CLIP_FRACTION_SEVERE` → waveform is unreliable, so lower the ML
  confidence bar to avoid a false negative on a real close shot.
- `clipFraction` is published to the UI via `SystemEventFlow.clipFraction` for
  operator visibility.

## 4. Localization: what is honestly achievable

Professional systems (e.g. fixed outdoor arrays) localize by **multilateration /
TDOA**: measure the tiny differences in muzzle-blast arrival time across sensors at
known positions and solve for the source.

Reported accuracy from the literature:
- ~15 m or better for 96% of shots **when six or more synchronized sensors** solve.
- Sub-metre to ~7 m in controlled range tests at 35–100 m.

The hard prerequisite is **tight clock synchronization** (sub-millisecond); sound
travels ~0.34 m/ms, so a 1 ms clock error is a ~0.34 m ranging error. Echoes,
diffraction, and reverberation add further uncertainty indoors.

**What we do, and when each method applies.** Both live in `core/Triangulation.kt`
(mirrored in `triangulateTdoa()` / `meshEstimateSource()` in `server/src/server.js`),
and the app always reports *which* one produced a given fix rather than presenting
them as equally trustworthy.

*1. TDoA multilateration (`method = "tdoa_multilateration"`).* Phones have no shared
clock out of the box, so we build one:

- **Clock sync.** Each node measures its offset to each peer NTP-style over the mesh
  link (`TIME:PING` / `TIME:PONG` in `MeshNetworkManager`): ping at `t1`, peer stamps
  receive `t2` and reply `t3`, we stamp `t4`, and
  `offset = ((t2 - t1) + (t3 - t4)) / 2`. Round-trip delay cancels out of the average.
  The lowest-round-trip sample is kept, because a fast round trip bounds how wrong the
  offset can be; the uncertainty carried forward is `rtt / 2`. Browser sensor nodes do
  the same against the relay's `GET /v1/time`, so every device reports on one clock.
- **Arrival time, not vote time.** `AudioSensorService` timestamps the *impulse* — it
  locates the loudest sample inside the captured frame and converts its index to wall
  clock — so the reported time is when the microphone heard the shot, not when
  classification finished. That distinction is worth tens of metres.
- **The solve.** `Triangulation.solve()` grid-searches (coarse 5 m sweep, then 0.5 m
  refinement) for the point minimising the variance of `arrival_i - distance_i / c`
  across confirming nodes. At the true source that quantity is constant and equals the
  shared clock origin. A grid search needs no initial guess and cannot diverge the way
  an iterative least-squares solve can.
- **Refusing to guess.** The solve returns null — falling back to the centroid —
  unless there are ≥3 timed nodes, every clock offset is known to ≤60 ms, the
  confirming phones span ≥4 m of baseline, the arrival spread is ≤1.5 s (wider is not
  physically one indoor event), and the final RMS residual fits the clocks' own
  uncertainty. A fix that cannot meet its own error budget is not reported as a fix.

*2. Confidence-weighted centroid (`method = "weighted_centroid"`).* When timing is
missing or fails those guards, we fall back to the centre of the confirming cluster,
weighted by each phone's classifier confidence, reported with a `spreadMeters` radius.
This says "somewhere in here", which is the honest answer for unsynchronised phones.

**Accuracy in practice.** The limiting term is clock-offset uncertainty, not the
solver. Measured against the relay implementation, with five simulated phones spread
across a 60 x 60 m floor plate and Gaussian clock error applied to each arrival time:

| Clock jitter | TDoA fixes | Median error |
| --- | --- | --- |
| ±0 ms | 6/6 | 0.0 m |
| ±5 ms | 6/6 | 1.6 m |
| ±20 ms | 6/6 | 7.0 m |
| ±50 ms | 0/6 | — (all fell back to centroid) |

Two things to take from this. First, the error tracks the clock directly — roughly
0.343 m per millisecond of offset error, as the physics demands. Second, the guards do
their job: at ±50 ms the solver **refuses** rather than emitting a confident-looking
fix it cannot support. So treat a TDoA result as a *room-or-corridor* answer, not the
sub-metre figure a fixed, wired sensor array achieves — and the UI labels it as such.

**Within-building / floor metrics.** Each confirming vote carries its **altitude**, and
the fix converts the altitude spread across confirming phones into a relative **floor
offset** using a nominal storey height (`FLOOR_HEIGHT_METERS = 3.5 m`). GPS altitude is
noisy indoors, so this is coarse — but a vertical hint is something a pure lat/lon fix
cannot give a responder at all.

**Remaining upgrade path:**
1. Fuse a **barometric** floor estimate — pressure is a far better indoor altimeter
   than GPS — for reliable storey resolution.
2. Tighten clock sync beyond `rtt / 2` (repeated sampling with outlier rejection, or a
   leader-broadcast epoch) to pull the ranging error under a few metres.
3. Weight each node's contribution by its own timing quality rather than gating the
   whole solve on the worst clock in the set.

## 5. Preventing the herding effect with many phones

Because one indoor shot is heard by many phones (§2), a naive design where any single
detection triggers a fleet-wide response will **cascade**: one false positive, or one
phone echoing another's alert, stampedes everyone into BARRICADE/EVACUATE.

Mitigations implemented in `MeshNetworkManager` and `AudioSensorService`:
- **Independent per-device classification.** A `WAKE:CLASSIFY` broadcast asks each
  phone to classify **its own microphone audio** and vote — phones do not simply
  forward or trust the raising node's verdict.
- **Corroboration is physically possible.** Every node keeps a rolling
  `HISTORY_SECONDS = 4` retrospective audio buffer, so a woken phone classifies the
  window around *the moment it heard the impulse*, addressed by wall-clock time,
  rather than whatever the microphone happens to be picking up when the mesh message
  finally arrives. Without this, a woken peer classifies the silence after the shot
  and votes NO — consensus then fails on every real event, which is the quiet failure
  mode that makes a quorum design look like it works while never firing.
- **The raiser's own score is used as-is.** The node that fires first is the one most
  likely to be wrong, so its self-vote carries its real classifier confidence rather
  than a blanket 1.0 that would let it dominate the weighted fix.
- **Dynamic quorum, not threshold = 1.** `requiredConfirmations()` scales the number
  of independent confirmations needed with fleet size
  (`ceil((peers+1) * QUORUM_FRACTION)`, clamped to `[2, MAX_REQUIRED_CONFIRMATIONS]`).
  A solo phone still self-triggers (nothing to corroborate against); once peers exist,
  **at least two distinct nodes** must agree.
- **Raising node is never sufficient alone.** `independentConfirmations()` requires at
  least one confirming node *other than* the one that raised the alarm before a
  response triggers, so a single misfiring phone cannot start a stampede.
- **Per-node, deduplicated votes.** Votes are keyed by node id and dedup'd by message
  id, so re-broadcast/relay traffic can't inflate the count. A peer that votes four
  times still counts once.
- **Only listeners count toward the quorum.** On the relay bus, devices with the page
  open but no microphone are excluded from `meshRequiredConfirmations()`. Counting
  them would raise the bar for everyone while they can never help clear it.
- **A timestamp is never guessed.** A node that did not actually hear an impulse votes
  without an arrival time rather than substituting "now". Its vote still counts toward
  consensus, but it cannot corrupt the triangulation.

## Sources

- Robert C. Maher, "Modeling and Signal Processing of Acoustic Gunshot Recordings" — https://www.montana.edu/rmaher/publications/maher_ieeedsp_0906_257-261.pdf
- Maher & Shaw, "Deciphering Gunshot Recordings" — https://www.montana.edu/rmaher/publications/maher_aesconf_0608_1-8.pdf
- "Acoustical Characterization of Gunshots" (IEEE) — https://ieeexplore.ieee.org/document/4218954/
- "Impulsive Sound Detection by a Novel Energy Formula and its Usage for Gunshot Recognition" — https://arxiv.org/pdf/1706.08759
- "Denoising by neural network for muzzle blast detection" — https://arxiv.org/pdf/2508.14919
- SoundThinking, "Precision and accuracy of acoustic gunshot location in an urban environment" — https://www.soundthinking.com/wp-content/uploads/2021/08/TN-098-Accuracy-of-Acoustic-Gunshot-Location.pdf
- Omnilert, "Gunshot Triangulation System Guide: How Detection Works" — https://www.omnilert.com/blog/gunshot-triangulation
- NIJ / OJP, "Audio Forensic Gunshot Analysis and Multilateration" — https://nij.ojp.gov/library/publications/audio-forensic-gunshot-analysis-and-multilateration
