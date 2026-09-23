import 'dart:io';
import 'dart:math' as math;
import 'package:image/image.dart' as img;
import 'package:path_provider/path_provider.dart';
import 'template_service.dart';

class SourceInfo {
  const SourceInfo(this.width, this.height);
  final int width;
  final int height;
}

class ProcessedOutputs {
  const ProcessedOutputs({
    this.photoPath,
    this.signaturePath,
    required this.width,
    required this.height,
    required this.photoBox,
    required this.signatureBox,
  });
  final String? photoPath;
  final String? signaturePath;
  final int width;
  final int height;
  final DetectedBox photoBox;
  final DetectedBox signatureBox;
}

class DetectedBox {
  const DetectedBox({
    required this.left,
    required this.top,
    required this.width,
    required this.height,
    required this.detected,
  });
  final int left;
  final int top;
  final int width;
  final int height;
  final bool detected;
}

class ImageService {
  Future<SourceInfo> inspect(File file) async {
    final bytes = await file.readAsBytes();
    final result = await _inspectInIsolate(bytes);
    return SourceInfo(result.$1, result.$2);
  }

  Future<ProcessedOutputs> processWholeForm(
    File source,
    FormTemplate template,
  ) async {
    final dir = await getTemporaryDirectory();
    return _processInIsolate(
      ProcessRequest(
        sourcePath: source.path,
        outputDirectory: dir.path,
        photo: template.photo,
        signature: template.signature,
        processPhoto: true,
        processSignature: true,
      ),
    );
  }

  Future<ProcessedOutputs> processSingle(
    File source, {
    required Region region,
    required double widthMm,
    required double heightMm,
    required int maxKb,
    required String fileName,
  }) async {
    final dir = await getTemporaryDirectory();
    return _processInIsolate(
      ProcessRequest(
        sourcePath: source.path,
        outputDirectory: dir.path,
        photo: region,
        signature: region,
        processPhoto: fileName == 'photo.jpg',
        processSignature: fileName == 'signature.jpg',
        singleWidthMm: widthMm,
        singleHeightMm: heightMm,
        singleMaxKb: maxKb,
      ),
    );
  }
}

class ProcessRequest {
  const ProcessRequest({
    required this.sourcePath,
    required this.outputDirectory,
    required this.photo,
    required this.signature,
    required this.processPhoto,
    required this.processSignature,
    this.singleWidthMm,
    this.singleHeightMm,
    this.singleMaxKb,
  });
  final String sourcePath;
  final String outputDirectory;
  final Region photo;
  final Region signature;
  final bool processPhoto;
  final bool processSignature;
  final double? singleWidthMm;
  final double? singleHeightMm;
  final int? singleMaxKb;
}

Future<(int, int)> _inspectInIsolate(List<int> bytes) async {
  final decoded = img.decodeImage(bytes);
  if (decoded == null) throw StateError('Unsupported image');
  final oriented = img.bakeOrientation(decoded);
  return (oriented.width, oriented.height);
}

Future<ProcessedOutputs> _processInIsolate(ProcessRequest request) async {
  final bytes = await File(request.sourcePath).readAsBytes();
  var decoded = img.decodeImage(bytes);
  if (decoded == null) throw StateError('Could not decode image');
  decoded = img.bakeOrientation(decoded);

  final photoBox = _findFormBox(decoded, request.photo);
  final signatureBox = _findFormBox(decoded, request.signature);

  String? photoPath;
  String? signaturePath;

  if (request.processPhoto) {
    final crop = img.copyCrop(
      decoded,
      x: photoBox.left,
      y: photoBox.top,
      width: photoBox.width,
      height: photoBox.height,
    );
    final path = '${request.outputDirectory}/${DateTime.now().microsecondsSinceEpoch}_photo.jpg';
    final encoded = _resizeAndEncode(
      crop,
      widthMm: request.singleWidthMm ?? 40,
      heightMm: request.singleHeightMm ?? 50,
      maxKb: request.singleMaxKb ?? 100,
    );
    await File(path).writeAsBytes(encoded, flush: true);
    photoPath = path;
  }

  if (request.processSignature) {
    final crop = img.copyCrop(
      decoded,
      x: signatureBox.left,
      y: signatureBox.top,
      width: signatureBox.width,
      height: signatureBox.height,
    );
    final path = '${request.outputDirectory}/${DateTime.now().microsecondsSinceEpoch}_signature.jpg';
    final encoded = _resizeAndEncode(
      crop,
      widthMm: request.singleWidthMm ?? 50,
      heightMm: request.singleHeightMm ?? 20,
      maxKb: request.singleMaxKb ?? 60,
    );
    await File(path).writeAsBytes(encoded, flush: true);
    signaturePath = path;
  }

  return ProcessedOutputs(
    photoPath: photoPath,
    signaturePath: signaturePath,
    width: decoded.width,
    height: decoded.height,
    photoBox: photoBox,
    signatureBox: signatureBox,
  );
}

/// Starts from the exact Class-8 A4 PDF coordinates and then refines each
/// edge by looking for the strongest rectangular border nearby. This handles
/// small camera alignment differences without blindly changing the template.
DetectedBox _findFormBox(img.Image image, Region region) {
  final expectedLeft = (image.width * region.left).round();
  final expectedTop = (image.height * region.top).round();
  final expectedWidth = math.max(8, (image.width * region.width).round());
  final expectedHeight = math.max(8, (image.height * region.height).round());
  final expectedRight = expectedLeft + expectedWidth - 1;
  final expectedBottom = expectedTop + expectedHeight - 1;

  final xSearch = math.max(8, (expectedWidth * 0.08).round());
  final ySearch = math.max(8, (expectedHeight * 0.08).round());

  final left = _bestVerticalBorder(image, expectedLeft, expectedTop, expectedBottom, xSearch);
  final right = _bestVerticalBorder(image, expectedRight, expectedTop, expectedBottom, xSearch);
  final top = _bestHorizontalBorder(image, expectedTop, expectedLeft, expectedRight, ySearch);
  final bottom = _bestHorizontalBorder(image, expectedBottom, expectedLeft, expectedRight, ySearch);

  final detected = left.score >= 0.32 &&
      right.score >= 0.32 &&
      top.score >= 0.32 &&
      bottom.score >= 0.32 &&
      right.position > left.position &&
      bottom.position > top.position;

  if (!detected) {
    final safeLeft = expectedLeft.clamp(0, image.width - 2);
    final safeTop = expectedTop.clamp(0, image.height - 2);
    return DetectedBox(
      left: safeLeft,
      top: safeTop,
      width: math.min(expectedWidth, image.width - safeLeft),
      height: math.min(expectedHeight, image.height - safeTop),
      detected: false,
    );
  }

  final safeLeft = left.position.clamp(0, image.width - 2);
  final safeTop = top.position.clamp(0, image.height - 2);
  final safeRight = right.position.clamp(safeLeft + 1, image.width - 1);
  final safeBottom = bottom.position.clamp(safeTop + 1, image.height - 1);

  return DetectedBox(
    left: safeLeft,
    top: safeTop,
    width: safeRight - safeLeft + 1,
    height: safeBottom - safeTop + 1,
    detected: true,
  );
}

({int position, double score}) _bestVerticalBorder(
  img.Image image,
  int expectedX,
  int top,
  int bottom,
  int searchRadius,
) {
  final start = math.max(1, expectedX - searchRadius);
  final end = math.min(image.width - 2, expectedX + searchRadius);
  var bestPosition = expectedX.clamp(1, image.width - 2);
  var bestScore = 0.0;

  final span = math.max(4, bottom - top + 1);
  final inset = (span * 0.14).round();
  final y0 = math.max(1, top + inset);
  final y1 = math.min(image.height - 2, bottom - inset);

  for (var x = start; x <= end; x++) {
    var dark = 0;
    var total = 0;
    for (var y = y0; y <= y1; y += 2) {
      if (_edgeStrength(image, x, y, vertical: true) > 34) dark++;
      total++;
    }
    final score = total == 0 ? 0.0 : dark / total;
    if (score > bestScore) {
      bestScore = score;
      bestPosition = x;
    }
  }
  return (position: bestPosition, score: bestScore);
}

({int position, double score}) _bestHorizontalBorder(
  img.Image image,
  int expectedY,
  int left,
  int right,
  int searchRadius,
) {
  final start = math.max(1, expectedY - searchRadius);
  final end = math.min(image.height - 2, expectedY + searchRadius);
  var bestPosition = expectedY.clamp(1, image.height - 2);
  var bestScore = 0.0;

  final span = math.max(4, right - left + 1);
  final inset = (span * 0.14).round();
  final x0 = math.max(1, left + inset);
  final x1 = math.min(image.width - 2, right - inset);

  for (var y = start; y <= end; y++) {
    var dark = 0;
    var total = 0;
    for (var x = x0; x <= x1; x += 2) {
      if (_edgeStrength(image, x, y, vertical: false) > 34) dark++;
      total++;
    }
    final score = total == 0 ? 0.0 : dark / total;
    if (score > bestScore) {
      bestScore = score;
      bestPosition = y;
    }
  }
  return (position: bestPosition, score: bestScore);
}

double _edgeStrength(img.Image image, int x, int y, {required bool vertical}) {
  final a = img.getLuminance(
    image.getPixel(vertical ? x - 1 : x, vertical ? y : y - 1),
  );
  final b = img.getLuminance(
    image.getPixel(vertical ? x + 1 : x, vertical ? y : y + 1),
  );
  return (a - b).abs().toDouble();
}

List<int> _resizeAndEncode(
  img.Image source, {
  required double widthMm,
  required double heightMm,
  required int maxKb,
}) {
  final targetWidth = math.max(1, (widthMm / 25.4 * 300).round());
  final targetHeight = math.max(1, (heightMm / 25.4 * 300).round());

  final resized = img.copyResize(
    source,
    width: targetWidth,
    height: targetHeight,
    interpolation: img.Interpolation.linear,
  );

  var low = 45;
  var high = 92;
  var best = img.encodeJpg(resized, quality: high);

  for (var i = 0; i < 7 && low <= high; i++) {
    final quality = ((low + high) / 2).round();
    final encoded = img.encodeJpg(resized, quality: quality);
    if (encoded.length <= maxKb * 1024) {
      best = encoded;
      low = quality + 1;
    } else {
      high = quality - 1;
    }
  }
  return best;
}
