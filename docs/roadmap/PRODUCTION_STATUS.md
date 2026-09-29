# FormSnap 2.0 — Roadmap Verification & Production Status

Last reviewed: 2026-09-30
Roadmap source: 'FormSnap_2_0_Master_Development_Specification_UPDATED.pdf' (22 pages), including the delta update for geometric layout matching, pre-preview correction, one-time SAF destination selection, person-name output naming, and the two-run learning integration gate.

## Status legend

- **Implemented in code**: repository implementation exists; this does not imply device validation.
- **CI verified**: the current code revision passes the relevant CI workflow.
- **Evidence required**: needs a labeled image corpus, Android device run, or artifact inspection before it can be called complete.

## Roadmap phase audit

| Phase | Scope | Current status | Exit evidence still required |
|---|---|---|---|
| 1 | Shared input/pipeline, orientation, cancellation, temporary storage | Partially implemented | EXIF/orientation regression tests; process-death and cache lifecycle checks |
| 2 | OpenCV candidate generation | Implemented baseline | Candidate overlay corpus and difficult-form review |
| 3 | Independent photo detection | Baseline implemented | Precision/recall and crop-clipping metrics on labeled boxed/unboxed/gray/tilted examples |
| 4 | Independent signature detection | Baseline implemented | Black/blue/faint ink, underline, border and printed-text evaluation |
| 5 | Crop geometry and perspective | Partially implemented; source-coordinate editor added | Verify mapping against original-resolution known rectangles; tilt/perspective corpus |
| 6 | Confidence and four presence combinations | Baseline implemented | Calibrate thresholds on held-out data; report photo/signature metrics separately |
| 7 | Preview and manual correction | Source-coordinate editor implemented in this branch | CI and interactive device checks; confirm drag handles, reset/cancel and repeated edits |
| 8 | Persistent folder and verified save | SAF root folder reuse, collision-safe names, JPEG photo and PNG signature implemented in this branch | Verify folder permission across cold start/revocation; test partial failures and read-back on Android |
| 9 | Feedback and controlled learning | Versioned feedback store and persisted rejected candidates implemented | Verify Accept/Adjust/Reject across restart; verify Reset Learning clears user profiles without damaging baseline |
| 10 | Portable .fsl import/export | Existing implementation | Round-trip, malformed, oversized, incompatible and interrupted-import tests |
| 11 | Optional lightweight ML | Not enabled | Do not add until a held-out baseline shows a measurable gap and model benefit exceeds size/RAM cost |
| 12 | Hardening and release | CI workflow and release signing configured | Current revision CI, target-device profiling, APK size, repeated-run memory/cache tests, no critical crash/data-loss defects |

## Delta-update acceptance gates

- Candidate geometry and feedback must use a documented coordinate space.
- Current topology matching uses normalized photo/signature candidate boxes plus source aspect ratio. The full segmented-component topology graph described in the delta specification is still a gap; it must not be reported as complete.
- Crop offsets recorded by new profiles are normalized against candidate width/height, so similar crops can transfer across source resolutions. Legacy v1/v2 profiles remain interpreted as pixel offsets and are not merged with v3 normalized profiles.
- The manual crop editor edits the original source coordinate space rather than editing only the already-cropped output.
- Rejected candidate regions persist per source identity and are bounded in count.
- The output destination is a single persisted SAF document tree; both output types use that root, without creating Photos/ or Signatures/ subdirectories.
- Default output names are [personName]-photo.jpg and [personName]-sign.png; collision suffixes are inserted before the extension. The signature defaults to PNG, with a size-budgeted lossless encoder that downsamples only if necessary.
- The required two-run learning test is not considered passed until a labeled integration fixture demonstrates: run 1 corrects a loose crop; run 2 on a matching layout applies the learned correction before preview and stays within an explicitly measured tolerance.

## Current code changes in the feature branch

- Source-coordinate crop editor with move/resize handles, Reset, Cancel and Apply; corrected bounds flow back to the preview ViewModel.
- Normalized topology signature/matcher and a bounded profile store; the matcher gates pre-preview reuse at a measured similarity of at least 0.75. This is a first-release two-region signature, not the full segmented-component graph.
- Learning profile version 3 stores normalized crop edge deltas; application converts them to pixels using the current candidate dimensions.
- Added pure unit tests for normalized-delta scaling and output filename sanitization/collision suffixes.
- Rejected candidate storage is persisted per source-file identity, bounded to ten regions per detection type.
- One persisted output-folder URI is shared by photo and signature. Photo uses JPEG; signature uses PNG. Writes are read back and size-verified.

## Release decision

Do **not** mark FormSnap 2.0 fully production-ready until:
1. the latest feature-branch CI run passes;
2. the labeled detection dataset is present and its held-out metrics are reported;
3. the two-run learned-correction integration gate passes;
4. real Android tests confirm SAF persistence, output naming, PNG/JPEG size limits, cancellation, and cache cleanup; and
5. release APK/AAB size and low-memory performance are measured on target-class devices.

A successful build is necessary, but not sufficient, evidence for production readiness.
