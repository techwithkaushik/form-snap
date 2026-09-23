import 'dart:convert';
import 'package:flutter/services.dart';

class Region {
  const Region({
    required this.left,
    required this.top,
    required this.width,
    required this.height,
  });

  final double left;
  final double top;
  final double width;
  final double height;

  factory Region.fromJson(Map<String, dynamic> json) => Region(
        left: (json['left'] as num).toDouble(),
        top: (json['top'] as num).toDouble(),
        width: (json['width'] as num).toDouble(),
        height: (json['height'] as num).toDouble(),
      );
}

class FormTemplate {
  const FormTemplate({required this.photo, required this.signature});
  final Region photo;
  final Region signature;
}

class TemplateService {
  static Future<FormTemplate> loadClass8Template() async {
    final raw = await rootBundle.loadString(
      'assets/templates/class8_2026_27.json',
    );
    final json = jsonDecode(raw) as Map<String, dynamic>;
    return FormTemplate(
      photo: Region.fromJson(json['photo'] as Map<String, dynamic>),
      signature: Region.fromJson(json['signature'] as Map<String, dynamic>),
    );
  }
}
