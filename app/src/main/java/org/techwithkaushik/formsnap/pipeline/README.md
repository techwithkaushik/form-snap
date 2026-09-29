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
