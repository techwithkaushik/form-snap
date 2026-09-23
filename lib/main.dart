import 'dart:async';
import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:path_provider/path_provider.dart';
import 'package:flutter/services.dart';

import 'services/image_service.dart';
import 'services/permission_service.dart';
import 'services/template_service.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();

  FlutterError.onError = (details) {
    FlutterError.presentError(details);
    debugPrint('Flutter error: ${details.exception}');
    debugPrintStack(stackTrace: details.stack);
  };

  PlatformDispatcher.instance.onError = (error, stack) {
    debugPrint('Unhandled async error: $error');
    debugPrintStack(stackTrace: stack);
    return true;
  };

  runZonedGuarded(
    () => runApp(const FormSnapApp()),
    (error, stack) {
      debugPrint('Unhandled app error: $error');
      debugPrintStack(stackTrace: stack);
    },
  );
}

class FormSnapApp extends StatelessWidget {
  const FormSnapApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'FormSnap',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo),
        useMaterial3: true,
      ),
      home: const HomePage(),
    );
  }
}

enum CaptureMode { wholeForm, closePhoto, closeSignature }

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  final _picker = ImagePicker();
  final _permissions = PermissionService();

  bool _busy = false;
  bool _permissionDialogOpen = false;


  Future<bool> _ensureCameraPermission() async {
    if (_permissionDialogOpen) return false;

    try {
      var status = await _permissions.cameraStatus();
      if (status.isGranted) return true;

      if (status.isDenied) {
        final continueRequest = await _showCameraRationale();
        if (!continueRequest || !mounted) return false;
        status = await _permissions.cameraStatus();
        if (status.isGranted) return true;
      }

      if (status.isPermanentlyDenied) return _handlePermanentCameraDenial();

      if (status.isRestricted) {
        await _showPermissionError(
          title: 'Camera access restricted',
          message:
              'Android is restricting camera access for FormSnap. Check the device privacy or app permission settings.',
        );
        return false;
      }

      final result = await _permissions.requestCamera(showRationale: false);

      if (!mounted) return false;

      switch (result) {
        case CameraPermissionResult.granted:
          return true;

        case CameraPermissionResult.denied:
          await _showPermissionError(
            title: 'Camera permission denied',
            message:
                'FormSnap cannot open the camera without camera access. You can continue using existing images, or allow Camera permission and try again.',
          );
          return false;

        case CameraPermissionResult.permanentlyDenied:
          return _handlePermanentCameraDenial();

        case CameraPermissionResult.restricted:
          await _showPermissionError(
            title: 'Camera access restricted',
            message:
                'The device is restricting camera access. Please check Android privacy controls.',
          );
          return false;

        case CameraPermissionResult.unavailable:
          await _showPermissionError(
            title: 'Camera unavailable',
            message:
                'Camera permission could not be granted on this device. You can still select an existing image.',
          );
          return false;

        case CameraPermissionResult.error:
          await _showPermissionError(
            title: 'Permission error',
            message:
                'FormSnap could not complete the camera permission request. Please try again or open App Settings.',
            showSettings: true,
          );
          return false;
      }
    } catch (error, stack) {
      debugPrint('Camera permission flow error: $error');
      debugPrintStack(stackTrace: stack);

      if (mounted) {
        await _showPermissionError(
          title: 'Permission error',
          message:
              'An unexpected error occurred while requesting camera access. You can retry or open App Settings.',
          showSettings: true,
        );
      }
      return false;
    }
  }

  Future<bool> _handlePermanentCameraDenial() async {
    if (!mounted) return false;

    final openSettings = await _showPermissionError(
      title: 'Camera permission is blocked',
      message:
          'Camera permission was denied permanently or Android will no longer show the permission dialog. Open App Settings and enable Camera for FormSnap.',
      showSettings: true,
    );

    if (openSettings) {
      await _permissions.openSettings();
    }

    return false;
  }

  Future<bool> _showCameraRationale() async {
    if (!mounted || _permissionDialogOpen) return false;

    _permissionDialogOpen = true;
    try {
      return await showDialog<bool>(
            context: context,
            barrierDismissible: false,
            builder: (context) => AlertDialog(
              title: const Text('Camera permission'),
              content: const Text(
                'FormSnap needs Camera access only when you capture a form, photo, or signature. '
                'The image is processed locally on the device.',
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.pop(context, false),
                  child: const Text('Not now'),
                ),
                FilledButton(
                  onPressed: () => Navigator.pop(context, true),
                  child: const Text('Continue'),
                ),
              ],
            ),
          ) ??
          false;
    } finally {
      _permissionDialogOpen = false;
    }
  }

  Future<bool> _showPermissionError({
    required String title,
    required String message,
    bool showSettings = false,
  }) async {
    if (!mounted || _permissionDialogOpen) return false;

    _permissionDialogOpen = true;
    try {
      return await showDialog<bool>(
            context: context,
            builder: (context) => AlertDialog(
              title: Text(title),
              content: Text(message),
              actions: [
                TextButton(
                  onPressed: () => Navigator.pop(context, false),
                  child: const Text('Close'),
                ),
                if (showSettings)
                  FilledButton(
                    onPressed: () => Navigator.pop(context, true),
                    child: const Text('Open Settings'),
                  ),
              ],
            ),
          ) ??
          false;
    } finally {
      _permissionDialogOpen = false;
    }
  }

  Future<void> _capture(CaptureMode mode) async {
    if (_busy) return;

    // Camera permission is requested only after the user taps a capture action.
    final allowed = await _ensureCameraPermission();
    if (!allowed || !mounted) return;

    setState(() => _busy = true);

    try {
      final source = await _picker.pickImage(
        source: ImageSource.camera,
        imageQuality: 100,
        maxWidth: 5000,
        maxHeight: 5000,
      );

      if (source == null || !mounted) return;

      await _openEditor(File(source.path), mode);
    } on PlatformException catch (error, stack) {
      debugPrint('Image picker platform error: $error');
      debugPrintStack(stackTrace: stack);

      if (!mounted) return;

      final code = error.code.toLowerCase();
      if (code.contains('camera') ||
          code.contains('permission') ||
          code.contains('denied')) {
        await _ensureCameraPermission();
      } else {
        await _showPermissionError(
          title: 'Camera error',
          message:
              'The camera could not be opened. Error: ${error.message ?? error.code}',
        );
      }
    } catch (error, stack) {
      debugPrint('Camera capture error: $error');
      debugPrintStack(stackTrace: stack);

      if (mounted) {
        await _showPermissionError(
          title: 'Unable to capture image',
          message:
              'FormSnap could not capture the image. Please check camera access and try again.',
          showSettings: true,
        );
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _pickFile() async {
    if (_busy) return;

    setState(() => _busy = true);
    try {
      // FilePicker uses the Android system document picker; no storage
      // permission is requested when the user imports an image.
      final result = await FilePicker.platform.pickFiles(
        type: FileType.image,
        allowMultiple: false,
      );

      final path = result?.files.single.path;
      if (path != null && mounted) {
        await _openEditor(File(path), CaptureMode.wholeForm);
      }
    } on PlatformException catch (error, stack) {
      debugPrint('File picker platform error: $error');
      debugPrintStack(stackTrace: stack);

      if (mounted) {
        await _showPermissionError(
          title: 'File selection error',
          message:
              'The image picker could not be opened. ${error.message ?? error.code}',
        );
      }
    } catch (error, stack) {
      debugPrint('File selection error: $error');
      debugPrintStack(stackTrace: stack);

      if (mounted) {
        await _showPermissionError(
          title: 'Unable to select image',
          message: 'The selected image could not be opened.',
        );
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _openEditor(File file, CaptureMode mode) async {
    if (!mounted) return;

    try {
      await Navigator.of(context).push(
        MaterialPageRoute(
          builder: (_) => EditorPage(file: file, mode: mode),
        ),
      );
    } catch (error, stack) {
      debugPrint('Editor navigation error: $error');
      debugPrintStack(stackTrace: stack);

      if (mounted) {
        await _showPermissionError(
          title: 'Unable to open editor',
          message: 'The captured image could not be opened for editing.',
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('FormSnap'),
        centerTitle: false,
      ),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          const Text(
            'Capture • Extract • Resize • Save',
            style: TextStyle(fontSize: 25, fontWeight: FontWeight.w800),
          ),
          const SizedBox(height: 8),
          Text(
            'Offline form photo & signature utility',
            style: TextStyle(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ),
          const SizedBox(height: 18),
          _ActionCard(
            icon: Icons.document_scanner_outlined,
            title: 'Capture Whole Form',
            subtitle:
                'Take the complete page and extract photo + signature using the form template.',
            onTap: _busy ? null : () => _capture(CaptureMode.wholeForm),
          ),
          const SizedBox(height: 12),
          _ActionCard(
            icon: Icons.photo_camera_outlined,
            title: 'Capture Photo',
            subtitle:
                'Capture only the photograph and prepare it at the configured size.',
            onTap: _busy ? null : () => _capture(CaptureMode.closePhoto),
          ),
          const SizedBox(height: 12),
          _ActionCard(
            icon: Icons.draw_outlined,
            title: 'Capture Signature',
            subtitle:
                'Capture only the signature and prepare it at the configured size.',
            onTap: _busy ? null : () => _capture(CaptureMode.closeSignature),
          ),
          const SizedBox(height: 12),
          _ActionCard(
            icon: Icons.photo_library_outlined,
            title: 'Select Existing Image',
            subtitle: 'Use a photo of the complete form from your gallery.',
            onTap: _busy ? null : _pickFile,
          ),
          const SizedBox(height: 28),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'Class 8 • 2026–27 template',
                    style: TextStyle(fontWeight: FontWeight.w700),
                  ),
                  const SizedBox(height: 10),
                  const Text('Photo  40 × 50 mm'),
                  const Text('Signature  50 × 20 mm'),
                  const SizedBox(height: 8),
                  Text(
                    'Default output is 300 DPI. Target file size can be adjusted before saving.',
                    style: TextStyle(
                      fontSize: 12,
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            ),
          ),
          if (_busy)
            const Padding(
              padding: EdgeInsets.only(top: 20),
              child: Center(child: CircularProgressIndicator()),
            ),
        ],
      ),
    );
  }
}

class _ActionCard extends StatelessWidget {
  const _ActionCard({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.onTap,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(17),
          child: Row(
            children: [
              Icon(icon, size: 32),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      style: const TextStyle(
                        fontSize: 17,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(subtitle),
                  ],
                ),
              ),
              const Icon(Icons.chevron_right),
            ],
          ),
        ),
      ),
    );
  }
}

class EditorPage extends StatefulWidget {
  const EditorPage({super.key, required this.file, required this.mode});

  final File file;
  final CaptureMode mode;

  @override
  State<EditorPage> createState() => _EditorPageState();
}

class _EditorPageState extends State<EditorPage> {
  final _service = ImageService();
  late Future<SourceInfo> _sourceInfo;
  String? _photoPath;
  String? _signaturePath;
  String _status = '';

  @override
  void initState() {
    super.initState();
    _sourceInfo = _service.inspect(widget.file);
  }

  Future<void> _extract() async {
    if (!mounted) return;
    setState(() => _status = 'Processing image…');

    try {
      final template = await TemplateService.loadClass8Template();
      final source = await _sourceInfo;

      if (widget.mode == CaptureMode.wholeForm) {
        _photoPath = await _service.processRegion(
          widget.file,
          template.photo,
          widthMm: 40,
          heightMm: 50,
          maxKb: 100,
          fileName: 'photo.jpg',
        );
        _signaturePath = await _service.processRegion(
          widget.file,
          template.signature,
          widthMm: 50,
          heightMm: 20,
          maxKb: 60,
          fileName: 'signature.jpg',
        );
      } else if (widget.mode == CaptureMode.closePhoto) {
        _photoPath = await _service.processCenter(
          widget.file,
          widthMm: 40,
          heightMm: 50,
          maxKb: 100,
          fileName: 'photo.jpg',
        );
      } else {
        _signaturePath = await _service.processCenter(
          widget.file,
          widthMm: 50,
          heightMm: 20,
          maxKb: 60,
          fileName: 'signature.jpg',
        );
      }

      if (mounted) {
        setState(() {
          _status =
              'Source: ${source.width} × ${source.height}px  •  Output ready';
        });
      }
    } catch (error, stack) {
      debugPrint('Image processing error: $error');
      debugPrintStack(stackTrace: stack);

      if (mounted) {
        setState(() => _status = 'Processing failed. Please try again.');
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Processing failed: $error')),
        );
      }
    }
  }

  Future<void> _saveAll() async {
    try {
      final dir = await getApplicationDocumentsDirectory();
      final folder = Directory('${dir.path}/FormSnap');
      await folder.create(recursive: true);

      for (final path in [_photoPath, _signaturePath]) {
        if (path == null) continue;
        final name = path.split(Platform.pathSeparator).last;
        await File(path).copy(
          '${folder.path}/${DateTime.now().millisecondsSinceEpoch}_$name',
        );
      }

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Saved to ${folder.path}')),
        );
      }
    } catch (error, stack) {
      debugPrint('Save error: $error');
      debugPrintStack(stackTrace: stack);

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Could not save the output files.')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Preview & Save')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          FutureBuilder<SourceInfo>(
            future: _sourceInfo,
            builder: (_, snapshot) {
              if (snapshot.hasError) {
                return const Padding(
                  padding: EdgeInsets.all(24),
                  child: Text('Unable to read the captured image.'),
                );
              }

              if (!snapshot.hasData) {
                return const AspectRatio(
                  aspectRatio: 1,
                  child: Center(child: CircularProgressIndicator()),
                );
              }

              return ClipRRect(
                borderRadius: BorderRadius.circular(14),
                child: Image.file(widget.file, fit: BoxFit.contain),
              );
            },
          ),
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: _extract,
            icon: const Icon(Icons.auto_fix_high),
            label: const Text('Extract Photo & Signature'),
          ),
          const SizedBox(height: 14),
          if (_photoPath != null)
            _ResultCard(title: 'Photo • 40 × 50 mm', path: _photoPath!),
          if (_signaturePath != null)
            _ResultCard(
              title: 'Signature • 50 × 20 mm',
              path: _signaturePath!,
            ),
          if (_photoPath != null || _signaturePath != null)
            FilledButton.icon(
              onPressed: _saveAll,
              icon: const Icon(Icons.save_outlined),
              label: const Text('Save'),
            ),
          if (_status.isNotEmpty) ...[
            const SizedBox(height: 12),
            Text(_status),
          ],
        ],
      ),
    );
  }
}

class _ResultCard extends StatelessWidget {
  const _ResultCard({required this.title, required this.path});

  final String title;
  final String path;

  @override
  Widget build(BuildContext context) {
    final file = File(path);
    final bytes = file.existsSync() ? file.lengthSync() : 0;

    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, style: const TextStyle(fontWeight: FontWeight.w700)),
            const SizedBox(height: 10),
            Center(
              child: Image.file(
                file,
                height: 180,
                fit: BoxFit.contain,
                errorBuilder: (_, __, ___) =>
                    const Text('Unable to display image'),
              ),
            ),
            const SizedBox(height: 8),
            Text(
              'File size: ${(bytes / 1024).toStringAsFixed(1)} KB',
            ),
          ],
        ),
      ),
    );
  }
}
