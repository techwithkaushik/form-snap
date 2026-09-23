import 'package:flutter/services.dart';
import 'dart:io';
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
  static const _channel = MethodChannel('formsnap/opencv');

  Future<SourceInfo> inspect(File file) async {
    final r = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'process', {'sourcePath': file.path, 'mode': 'inspect'},
    );
    if (r == null) throw StateError('Native image inspection failed');
    return SourceInfo((r['width'] as num?)?.toInt() ?? 0, (r['height'] as num?)?.toInt() ?? 0);
  }

  Future<ProcessedOutputs> processWholeForm(File source, FormTemplate template) =>
      _process(source, 'wholeForm', template: template);

  Future<ProcessedOutputs> processSingle(
    File source, {
    required Region region,
    required double widthMm,
    required double heightMm,
    required int maxKb,
    required String fileName,
  }) => _process(
    source,
    fileName == 'photo.jpg' ? 'closePhoto' : 'closeSignature',
    region: region,
    widthMm: widthMm,
    heightMm: heightMm,
    maxKb: maxKb,
  );

  Future<ProcessedOutputs> _process(
    File source,
    String mode, {
    FormTemplate? template,
    Region? region,
    double? widthMm,
    double? heightMm,
    int? maxKb,
  }) async {
    final photo = template?.photo ?? region!;
    final signature = template?.signature ?? region!;
    final r = await _channel.invokeMethod<Map<dynamic, dynamic>>('process', {
      'sourcePath': source.path,
      'mode': mode,
      'photo': {'left': photo.left, 'top': photo.top, 'width': photo.width, 'height': photo.height},
      'signature': {'left': signature.left, 'top': signature.top, 'width': signature.width, 'height': signature.height},
      'widthMm': widthMm,
      'heightMm': heightMm,
      'maxKb': maxKb,
    });
    if (r == null) throw StateError('Native OpenCV processing returned no result');
    final width = (r['width'] as num?)?.toInt() ?? 0;
    final height = (r['height'] as num?)?.toInt() ?? 0;
    return ProcessedOutputs(
      photoPath: r['photoPath'] as String?,
      signaturePath: r['signaturePath'] as String?,
      width: width,
      height: height,
      photoBox: DetectedBox(left: 0, top: 0, width: width, height: height, detected: r['photoDetected'] == true),
      signatureBox: DetectedBox(left: 0, top: 0, width: width, height: height, detected: r['signatureDetected'] == true),
    );
  }
}
