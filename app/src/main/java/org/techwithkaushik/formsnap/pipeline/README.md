# FormSnap universal pipeline

The new pipeline is intentionally isolated from the legacy processor.

## Stages

Input -> Normalize -> Detect Photo -> Detect Signature -> Crop/Clean -> Quality Gate -> Preview -> Save

Camera and Import must enter the same pipeline after producing one source image.

Detection is independent:
- photo may exist without signature
- signature may exist without photo
- both may exist
- neither may exist
- printed boxes are optional
- photo and signature may have unrelated positions and sizes

The legacy processor remains untouched until the new pipeline has a compiling, testable baseline.


## Learning backup (.fsl)

The home screen exposes **Export** and **Import** for portable learning memory.
The bundle contains a versioned manifest plus validated correction profiles only;
it never contains original photos, signatures, or temporary image files. Import
validates bundle format, schema, entry names, payload size, and profile values
before atomically persisting a merge. Compatible profiles are merged using their
sample counts; incompatible conditions remain separate. Export/import runs on an
IO dispatcher so it does not block the Compose UI.


## Position-independent detection

Photo and signature selection are independent. Signature candidates are not discarded
because they occur above the photograph or outside a fixed lower-page band; vertical
position contributes only a weak ranking cue. This is important for forms with different
layouts. The detector still needs a labelled real-image corpus before accuracy can be
claimed or thresholds can be tuned safely.


## Condition-aware learning

The pipeline measures brightness, contrast, saturation, edge density, and aspect ratio
from the detected region in the original image before enhancement. A learned correction
is selected and revalidated using the features actually measured for that candidate;
unknown measurements are omitted instead of being compared with fabricated defaults.
Accepted preview corrections also record the source-region features. Feature extraction
releases every temporary OpenCV Mat in a `finally` block and returns no profile features
for invalid or unsupported image regions. Accuracy still needs evaluation against labelled
real images from varied forms, lighting, rotations, and capture distances.
