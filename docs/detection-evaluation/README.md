# Detection evaluation corpus

This directory defines a reproducible annotation format; it does **not** claim that a real-world
accuracy corpus has already been collected. Keep image files in a separate private corpus unless
they are synthetic or explicitly cleared for redistribution. Form images can contain faces,
signatures, names, IDs, and other personal data; do not commit them without consent and review.

## One fixture per source image

Create one JSON file per image that validates against `fixture.schema.json`. Store the image
beside the JSON in a local evaluation directory, not necessarily in this repository. Bounding
coordinates are normalized to `[0,1]` relative to the original, orientation-corrected image:
`left = pixelLeft / width`, `right = pixelRight / width`, and similarly for top/bottom.
`box` is the desired crop region including safe margins; `contentBox` is the actual visible
photo/ink content used to measure clipping and retention.

Every annotation should be reviewed by two people for high-stakes evaluation. An image labelled
`neither` must have an empty annotations array. The scenario and annotations must agree:
- `photo_only`: exactly one photo annotation and no signature annotation.
- `signature_only`: exactly one signature annotation and no photo annotation.
- `both`: one annotation of each kind.
- `neither`: no annotations.

## Required coverage before tuning detector thresholds

Include boxed and unboxed examples; photo-only, signature-only, both, and neither; black and blue
ink; faint and thick signatures; printed lines, tables, stamps and barcodes as hard negatives;
small/far-away targets; blur; shadows; low/high light; landscape/portrait forms; rotated images;
and mild perspective distortion. Split by **source form/session**, not randomly by near-duplicate
images, to avoid leakage between tuning and evaluation sets.

## Metrics to report

Report photo and signature separately:
- precision, recall, F1, false-positive and false-negative counts;
- IoU at 0.50 and 0.75 for candidate boxes;
- content retention (fraction of annotated content inside the saved crop);
- clipping rate, border/noise contamination, processing latency, peak memory and output size.

Keep a fixed hold-out set that is not used to tune thresholds or learning parameters. Report sample
counts and the scenario/lighting breakdown beside every metric. If the hold-out set is too small,
say so rather than presenting its results as general accuracy.

## Baseline comparison

Record the git commit, OpenCV SDK version, detector version, device model/Android version, image
dimensions, and per-image result. Compare changes against the same corpus. Do not claim that a
change improved detection until the same held-out images have been processed and the metrics show
the change without a material regression in another scenario.

## Example record

The following is a format illustration only; its image and coordinates are not a measured test
fixture:

```json
{
  "schemaVersion": 1,
  "id": "example-signature-only",
  "image": {
    "file": "example-signature-only.png",
    "width": 1200,
    "height": 1600,
    "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  },
  "scenario": "signature_only",
  "capture": {
    "boxed": false,
    "tiltDegrees": 4.0,
    "distance": "normal",
    "lighting": "normal",
    "background": "printed_form"
  },
  "annotations": [
    {
      "kind": "signature",
      "box": {"left": 0.18, "top": 0.55, "right": 0.78, "bottom": 0.63},
      "contentBox": {"left": 0.21, "top": 0.57, "right": 0.74, "bottom": 0.61}
    }
  ]
}
```
