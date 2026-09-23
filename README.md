# FormSnap

**FormSnap** is an offline Android tool for capturing a complete form or a
close-up photo/signature, then preparing the extracted image for a required
physical size and file-size limit.

## First template

The first template is the supplied Class 8 Board Application Form for
2026–27.

- Photo: 40 × 50 mm
- Signature: 50 × 20 mm
- Default output resolution: 300 DPI
- Photo/signature processing is local

## Important

The current repository is the **initial buildable foundation**. The next
implementation stage will add:

1. interactive crop/adjustment handles;
2. perspective correction for full-page capture;
3. template alignment/calibration;
4. user-editable width/height and KB limits;
5. quality-aware compression;
6. save/export naming and folders;
7. better signature background cleanup.

The template coordinates are deliberately kept in a JSON file so additional
forms can be added without changing the core processing code.

## GitHub Actions

Android is built on GitHub Actions because the development phone uses 32-bit
Termux and cannot perform the Android/Flutter build locally.

The workflow uses Flutter 3.38.5 and generates the Android host project on the
runner. It also supports the same signing secret names used by the earlier
project.

