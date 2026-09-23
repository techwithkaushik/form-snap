import 'dart:io';
import 'dart:isolate';
import 'dart:typed_data';
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
    return Isolate.run(() => _processInIsolate(
      ProcessRequest(
        sourcePath: source.path,
        outputDirectory: dir.path,
        photo: template.photo,
        signature: template.signature,
        processPhoto: true,
        processSignature: true,
      ),
    ));
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
    return Isolate.run(() => _processInIsolate(
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
        centerCrop: true,
      ),
    ));
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
    this.centerCrop = false,
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
  final bool centerCrop;
}

Future<(int, int)> _inspectInIsolate(Uint8List bytes) async {
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

  final photoCrop = request.centerCrop
      ? _centerCropResult(decoded, request.photo)
      : _extractFormRegion(decoded, request.photo);
  final signatureCrop = request.centerCrop
      ? _centerCropResult(decoded, request.signature)
      : _extractFormRegion(decoded, request.signature);
  final photoBox = photoCrop.box;
  final signatureBox = signatureCrop.box;

  String? photoPath;
  String? signaturePath;

  if (request.processPhoto) {
    final crop = photoCrop.image;
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
    final crop = signatureCrop.image;
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

class _CropResult {
  const _CropResult({required this.image, required this.box});

  final img.Image image;
  final DetectedBox box;
}

class _Quad {
  const _Quad({
    required this.topLeft,
    required this.topRight,
    required this.bottomLeft,
    required this.bottomRight,
    required this.score,
  });

  final img.Point topLeft;
  final img.Point topRight;
  final img.Point bottomLeft;
  final img.Point bottomRight;
  final double score;
}

_CropResult _centerCropResult(img.Image image, Region region) {
  final targetRatio = region.width / region.height;
  final currentRatio = image.width / image.height;

  var width = image.width;
  var height = image.height;

  if (currentRatio > targetRatio) {
    height = image.height;
    width = (height * targetRatio).round();
  } else {
    width = image.width;
    height = (width / targetRatio).round();
  }

  final left = ((image.width - width) / 2).round();
  final top = ((image.height - height) / 2).round();

  final safeLeft = left.clamp(0, image.width - 1).toInt();
  final safeTop = top.clamp(0, image.height - 1).toInt();
  final safeWidth = width.clamp(1, image.width - safeLeft).toInt();
  final safeHeight = height.clamp(1, image.height - safeTop).toInt();

  final inset = math.max(1, (math.min(safeWidth, safeHeight) * 0.035).round());
  final cropLeft = (safeLeft + inset).clamp(0, image.width - 1).toInt();
  final cropTop = (safeTop + inset).clamp(0, image.height - 1).toInt();
  final cropRight =
      (safeLeft + safeWidth - inset).clamp(cropLeft + 1, image.width).toInt();
  final cropBottom =
      (safeTop + safeHeight - inset).clamp(cropTop + 1, image.height).toInt();

  return _CropResult(
    image: img.copyCrop(
      image,
      x: cropLeft,
      y: cropTop,
      width: cropRight - cropLeft,
      height: cropBottom - cropTop,
    ),
    box: DetectedBox(
      left: cropLeft,
      top: cropTop,
      width: cropRight - cropLeft,
      height: cropBottom - cropTop,
      detected: false,
    ),
  );
}

_CropResult _extractFormRegion(img.Image image, Region region) {
  final expectedLeft = (image.width * region.left).round();
  final expectedTop = (image.height * region.top).round();
  final expectedWidth = math.max(12, (image.width * region.width).round());
  final expectedHeight = math.max(12, (image.height * region.height).round());

  final quad = _findFormQuad(
    image,
    expectedLeft,
    expectedTop,
    expectedWidth,
    expectedHeight,
  );

  if (quad != null) {
    final minX = [
      quad.topLeft.x,
      quad.topRight.x,
      quad.bottomLeft.x,
      quad.bottomRight.x,
    ].reduce(math.min).round();
    final maxX = [
      quad.topLeft.x,
      quad.topRight.x,
      quad.bottomLeft.x,
      quad.bottomRight.x,
    ].reduce(math.max).round();
    final minY = [
      quad.topLeft.y,
      quad.topRight.y,
      quad.bottomLeft.y,
      quad.bottomRight.y,
    ].reduce(math.min).round();
    final maxY = [
      quad.topLeft.y,
      quad.topRight.y,
      quad.bottomLeft.y,
      quad.bottomRight.y,
    ].reduce(math.max).round();

    final safeLeft = minX.clamp(0, image.width - 2).toInt();
    final safeTop = minY.clamp(0, image.height - 2).toInt();
    final safeRight = maxX.clamp(safeLeft + 1, image.width - 1).toInt();
    final safeBottom = maxY.clamp(safeTop + 1, image.height - 1).toInt();

    final targetWidth = math.max(64, expectedWidth);
    final targetHeight = math.max(32, expectedHeight);

    final rectified = img.copyRectify(
      image,
      topLeft: quad.topLeft,
      topRight: quad.topRight,
      bottomLeft: quad.bottomLeft,
      bottomRight: quad.bottomRight,
      interpolation: img.Interpolation.linear,
      toImage: img.Image(width: targetWidth, height: targetHeight),
    );

    final insetX = math.max(1, (rectified.width * 0.025).round());
    final insetY = math.max(1, (rectified.height * 0.025).round());

    final cropped = img.copyCrop(
      rectified,
      x: insetX,
      y: insetY,
      width: math.max(1, rectified.width - insetX * 2),
      height: math.max(1, rectified.height - insetY * 2),
    );

    return _CropResult(
      image: cropped,
      box: DetectedBox(
        left: safeLeft,
        top: safeTop,
        width: safeRight - safeLeft + 1,
        height: safeBottom - safeTop + 1,
        detected: true,
      ),
    );
  }

  final safeLeft = expectedLeft.clamp(0, image.width - 2).toInt();
  final safeTop = expectedTop.clamp(0, image.height - 2).toInt();
  final baseWidth = math.min(expectedWidth, image.width - safeLeft).toInt();
  final baseHeight = math.min(expectedHeight, image.height - safeTop).toInt();

  final insetX = math.max(1, (baseWidth * 0.025).round());
  final insetY = math.max(1, (baseHeight * 0.025).round());

  final cropLeft = (safeLeft + insetX).clamp(0, image.width - 1).toInt();
  final cropTop = (safeTop + insetY).clamp(0, image.height - 1).toInt();
  final cropWidth = math.max(1, baseWidth - insetX * 2);
  final cropHeight = math.max(1, baseHeight - insetY * 2);

  return _CropResult(
    image: img.copyCrop(
      image,
      x: cropLeft,
      y: cropTop,
      width: math.min(cropWidth, image.width - cropLeft),
      height: math.min(cropHeight, image.height - cropTop),
    ),
    box: DetectedBox(
      left: safeLeft,
      top: safeTop,
      width: baseWidth,
      height: baseHeight,
      detected: false,
    ),
  );
}

_Quad? _findFormQuad(
  img.Image image,
  int left,
  int top,
  int width,
  int height,
) {
  final radiusX = math.max(8, (width * 0.16).round());
  final radiusY = math.max(8, (height * 0.16).round());

  final tl = _bestCorner(image, left, top, radiusX, radiusY);
  final tr = _bestCorner(image, left + width, top, radiusX, radiusY);
  final bl = _bestCorner(image, left, top + height, radiusX, radiusY);
  final br = _bestCorner(image, left + width, top + height, radiusX, radiusY);

  final score = _cornerScore(image, tl) +
      _cornerScore(image, tr) +
      _cornerScore(image, bl) +
      _cornerScore(image, br);

  final widthTop = tr.x - tl.x;
  final widthBottom = br.x - bl.x;
  final heightLeft = bl.y - tl.y;
  final heightRight = br.y - tr.y;

  final geometryOk = widthTop > width * 0.55 &&
      widthBottom > width * 0.55 &&
      heightLeft > height * 0.55 &&
      heightRight > height * 0.55 &&
      widthTop > 0 &&
      widthBottom > 0 &&
      heightLeft > 0 &&
      heightRight > 0;

  if (!geometryOk || score < 160) return null;

  return _Quad(
    topLeft: tl,
    topRight: tr,
    bottomLeft: bl,
    bottomRight: br,
    score: score,
  );
}

img.Point _bestCorner(
  img.Image image,
  int expectedX,
  int expectedY,
  int radiusX,
  int radiusY,
) {
  final x0 = math.max(2, expectedX - radiusX);
  final x1 = math.min(image.width - 3, expectedX + radiusX);
  final y0 = math.max(2, expectedY - radiusY);
  final y1 = math.min(image.height - 3, expectedY + radiusY);

  var best = img.Point(
    expectedX.clamp(2, image.width - 3),
    expectedY.clamp(2, image.height - 3),
  );
  var bestScore = -1.0;

  for (var y = y0; y <= y1; y += 2) {
    for (var x = x0; x <= x1; x += 2) {
      final score = _edgeStrength(image, x, y, vertical: false) +
          _edgeStrength(image, x, y, vertical: true);
      if (score > bestScore) {
        bestScore = score;
        best = img.Point(x, y);
      }
    }
  }

  return best;
}

double _cornerScore(img.Image image, img.Point point) {
  final x = point.xi.clamp(2, image.width - 3);
  final y = point.yi.clamp(2, image.height - 3);
  return _edgeStrength(image, x, y, vertical: false) +
      _edgeStrength(image, x, y, vertical: true);
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

Uint8List _resizeAndEncode(
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
