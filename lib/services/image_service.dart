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

class ImageService {
  Future<SourceInfo> inspect(File file) async {
    final bytes = await file.readAsBytes();
    final decoded = img.decodeImage(bytes);
    if (decoded == null) throw StateError('Unsupported image');
    return SourceInfo(decoded.width, decoded.height);
  }

  Future<String> processRegion(
    File source,
    Region region, {
    required double widthMm,
    required double heightMm,
    required int maxKb,
    required String fileName,
  }) async {
    final bytes = await source.readAsBytes();
    final decoded = img.decodeImage(bytes);
    if (decoded == null) throw StateError('Could not decode image');

    final left = (decoded.width * region.left).round();
    final top = (decoded.height * region.top).round();
    final width = (decoded.width * region.width).round();
    final height = (decoded.height * region.height).round();

    final safeLeft = left.clamp(0, decoded.width - 1);
    final safeTop = top.clamp(0, decoded.height - 1);
    final safeWidth = math.min(width, decoded.width - safeLeft);
    final safeHeight = math.min(height, decoded.height - safeTop);

    final crop = img.copyCrop(
      decoded,
      x: safeLeft,
      y: safeTop,
      width: safeWidth,
      height: safeHeight,
    );

    return _encodeAndSave(
      crop,
      widthMm: widthMm,
      heightMm: heightMm,
      maxKb: maxKb,
      fileName: fileName,
    );
  }

  Future<String> processCenter(
    File source, {
    required double widthMm,
    required double heightMm,
    required int maxKb,
    required String fileName,
  }) async {
    final bytes = await source.readAsBytes();
    final decoded = img.decodeImage(bytes);
    if (decoded == null) throw StateError('Could not decode image');

    final targetRatio = widthMm / heightMm;
    final currentRatio = decoded.width / decoded.height;

    int cropWidth;
    int cropHeight;
    if (currentRatio > targetRatio) {
      cropHeight = decoded.height;
      cropWidth = (cropHeight * targetRatio).round();
    } else {
      cropWidth = decoded.width;
      cropHeight = (cropWidth / targetRatio).round();
    }

    final x = (decoded.width - cropWidth) ~/ 2;
    final y = (decoded.height - cropHeight) ~/ 2;

    final crop = img.copyCrop(
      decoded,
      x: x,
      y: y,
      width: cropWidth,
      height: cropHeight,
    );

    return _encodeAndSave(
      crop,
      widthMm: widthMm,
      heightMm: heightMm,
      maxKb: maxKb,
      fileName: fileName,
    );
  }

  Future<String> _encodeAndSave(
    img.Image source, {
    required double widthMm,
    required double heightMm,
    required int maxKb,
    required String fileName,
  }) async {
    // 300 DPI conversion: pixels = mm / 25.4 * 300.
    final targetWidth = math.max(1, (widthMm / 25.4 * 300).round());
    final targetHeight = math.max(1, (heightMm / 25.4 * 300).round());

    final resized = img.copyResize(
      source,
      width: targetWidth,
      height: targetHeight,
      interpolation: img.Interpolation.cubic,
    );

    int low = 35;
    int high = 100;
    List<int> best = img.encodeJpg(resized, quality: 100);

    // Find the highest JPEG quality that fits the requested maximum size.
    for (var i = 0; i < 8; i++) {
      final q = ((low + high) / 2).round();
      final encoded = img.encodeJpg(resized, quality: q);
      if (encoded.length <= maxKb * 1024) {
        best = encoded;
        low = q + 1;
      } else {
        high = q - 1;
      }
    }

    final dir = await getTemporaryDirectory();
    final output = File('${dir.path}/${DateTime.now().microsecondsSinceEpoch}_$fileName');
    await output.writeAsBytes(best, flush: true);
    return output.path;
  }
}
