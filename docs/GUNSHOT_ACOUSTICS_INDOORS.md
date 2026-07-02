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

**Why phones can't do true TDOA today, and what we do instead:**
- Android phones over Nearby Connections do **not** share a sub-millisecond common
  clock, and GPS indoors is coarse/absent. True TDOA multilateration is therefore not
  achievable with the current hardware path.
- What we *can* do honestly is a **confidence-weighted centroid** of the phones that
  independently confirmed the shot, using each phone's own position and classifier
  confidence as weight. Implemented in `VoteSession.estimateSource()`
  (`localizationMethod = "weighted_centroid"`). It reports the cluster centroid plus a
  `spreadMeters` radius as a crude confidence bound.
- **Within-building / floor** (`within building metrics`): each confirming vote now
  carries its **altitude**. `estimateSource()` converts the altitude spread across
  confirming phones into a relative **floor offset** using a nominal storey height
  (`FLOOR_HEIGHT_METERS = 3.5 m`). This is coarse (GPS altitude is noisy indoors) but
  gives responders a vertical hint that a pure lat/lon fix cannot.

**Documented upgrade path (not yet built):**
1. Distribute a shared time base (NTP-style offset exchange or a leader-broadcast
   epoch) so per-device detection timestamps become comparable to ~1–5 ms.
2. Carry a precise per-vote `detectedAtMs` (the payload already reserves room for it)
   and run a least-squares TDOA solve once ≥4 confirming phones with a common clock
   report.
3. Fuse a **barometric** floor estimate (pressure is a far better indoor altimeter
   than GPS) for reliable storey resolution.

## 5. Preventing the herding effect with many phones

Because one indoor shot is heard by many phones (§2), a naive design where any single
detection triggers a fleet-wide response will **cascade**: one false positive, or one
phone echoing another's alert, stampedes everyone into BARRICADE/EVACUATE.

Mitigations implemented in `MeshNetworkManager`:
- **Independent per-device classification.** A `WAKE:CLASSIFY` broadcast asks each
  phone to classify **its own microphone audio** and vote — phones do not simply
  forward or trust the raising node's verdict.
- **Dynamic quorum, not threshold = 1.** `requiredConfirmations()` scales the number
  of independent confirmations needed with fleet size
  (`ceil((peers+1) * QUORUM_FRACTION)`, clamped to `[2, MAX_REQUIRED_CONFIRMATIONS]`).
  A solo phone still self-triggers (nothing to corroborate against); once peers exist,
  **at least two distinct nodes** must agree.
- **Raising node is never sufficient alone.** `independentConfirmations()` requires at
  least one confirming node *other than* the one that raised the alarm before a
  response triggers, so a single misfiring phone cannot start a stampede.
- **Per-node, deduplicated votes.** Votes are keyed by node id and dedup'd by message
  id, so re-broadcast/relay traffic can't inflate the count.

## Sources

- Robert C. Maher, "Modeling and Signal Processing of Acoustic Gunshot Recordings" — https://www.montana.edu/rmaher/publications/maher_ieeedsp_0906_257-261.pdf
- Maher & Shaw, "Deciphering Gunshot Recordings" — https://www.montana.edu/rmaher/publications/maher_aesconf_0608_1-8.pdf
- "Acoustical Characterization of Gunshots" (IEEE) — https://ieeexplore.ieee.org/document/4218954/
- "Impulsive Sound Detection by a Novel Energy Formula and its Usage for Gunshot Recognition" — https://arxiv.org/pdf/1706.08759
- "Denoising by neural network for muzzle blast detection" — https://arxiv.org/pdf/2508.14919
- SoundThinking, "Precision and accuracy of acoustic gunshot location in an urban environment" — https://www.soundthinking.com/wp-content/uploads/2021/08/TN-098-Accuracy-of-Acoustic-Gunshot-Location.pdf
- Omnilert, "Gunshot Triangulation System Guide: How Detection Works" — https://www.omnilert.com/blog/gunshot-triangulation
- NIJ / OJP, "Audio Forensic Gunshot Analysis and Multilateration" — https://nij.ojp.gov/library/publications/audio-forensic-gunshot-analysis-and-multilateration
