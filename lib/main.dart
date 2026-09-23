import 'dart:async';
import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';
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
      final result = await FilePicker.platform.pickFiles(
        type: FileType.image,
        allowMultiple: false,
      );

      if (result == null || result.files.isEmpty || !mounted) return;

      // Use XFile bytes instead of relying on PlatformFile.path. Android
      // document providers can return a content URI with no filesystem path.
      final picked = result.files.single;
      final bytes = await picked.xFile.readAsBytes();

      final dir = await getTemporaryDirectory();
      final extension = (picked.extension ?? 'jpg').toLowerCase();
      final imported = File(
        '${dir.path}/form_import_${DateTime.now().microsecondsSinceEpoch}.$extension',
      );
      await imported.writeAsBytes(bytes, flush: true);

      if (mounted) {
        await _openEditor(imported, CaptureMode.wholeForm);
      }
    } on PlatformException catch (error, stack) {
      debugPrint('File picker platform error: $error');
      debugPrintStack(stackTrace: stack);
      if (mounted) {
        await _showPermissionError(
          title: 'Unable to open image picker',
          message: error.message ?? 'Android could not return the selected image.',
        );
      }
    } catch (error, stack) {
      debugPrint('File selection error: $error');
      debugPrintStack(stackTrace: stack);
      if (mounted) {
        await _showPermissionError(
          title: 'Unable to import image',
          message:
              'The selected form image could not be read. Please choose a JPG or PNG image stored on the device.',
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
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 28),
        children: [
          Container(
            padding: const EdgeInsets.all(20),
            decoration: BoxDecoration(
              gradient: LinearGradient(
                colors: [
                  Theme.of(context).colorScheme.primaryContainer,
                  Theme.of(context).colorScheme.surfaceContainerHighest,
                ],
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
              ),
              borderRadius: BorderRadius.circular(24),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Container(
                      width: 48,
                      height: 48,
                      decoration: BoxDecoration(
                        color: Theme.of(context).colorScheme.primary,
                        borderRadius: BorderRadius.circular(14),
                      ),
                      child: Icon(
                        Icons.document_scanner_rounded,
                        color: Theme.of(context).colorScheme.onPrimary,
                      ),
                    ),
                    const SizedBox(width: 14),
                    const Expanded(
                      child: Text(
                        'FormSnap',
                        style: TextStyle(
                          fontSize: 25,
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 18),
                const Text(
                  'Capture forms. Extract photo & signature. Save ready-to-use files.',
                  style: TextStyle(
                    fontSize: 17,
                    fontWeight: FontWeight.w700,
                    height: 1.3,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  'Class 8 • 2026–27 • 300 DPI output',
                  style: TextStyle(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 22),
          const Text(
            'Quick actions',
            style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800),
          ),
          const SizedBox(height: 12),
          GridView.count(
            crossAxisCount: 2,
            shrinkWrap: true,
            physics: const NeverScrollableScrollPhysics(),
            crossAxisSpacing: 12,
            mainAxisSpacing: 12,
            childAspectRatio: 1.05,
            children: [
              _ActionTile(
                icon: Icons.document_scanner_rounded,
                title: 'Whole Form',
                subtitle: 'Capture & extract',
                onTap: _busy ? null : () => _capture(CaptureMode.wholeForm),
              ),
              _ActionTile(
                icon: Icons.photo_camera_rounded,
                title: 'Photo',
                subtitle: 'Capture photo',
                onTap: _busy ? null : () => _capture(CaptureMode.closePhoto),
              ),
              _ActionTile(
                icon: Icons.draw_rounded,
                title: 'Signature',
                subtitle: 'Capture signature',
                onTap:
                    _busy ? null : () => _capture(CaptureMode.closeSignature),
              ),
              _ActionTile(
                icon: Icons.photo_library_rounded,
                title: 'Import Form',
                subtitle: 'Choose existing image',
                onTap: _busy ? null : _pickFile,
              ),
            ],
          ),
          const SizedBox(height: 22),
          Card(
            elevation: 0,
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Row(
                children: [
                  Icon(
                    Icons.auto_awesome_rounded,
                    color: Theme.of(context).colorScheme.primary,
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Text(
                      'Smart extraction uses the Class 8 A4 template and refines the photo/signature box before cropping.',
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                        height: 1.35,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
          if (_busy)
            const Padding(
              padding: EdgeInsets.only(top: 24),
              child: Center(child: CircularProgressIndicator()),
            ),
        ],
      ),
    );
  }
}

class _ActionTile extends StatelessWidget {
  const _ActionTile({
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
    final scheme = Theme.of(context).colorScheme;
    return Card(
      elevation: 0,
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(15),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(icon, size: 30, color: scheme.primary),
              const Spacer(),
              Text(
                title,
                style: const TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.w800,
                ),
              ),
              const SizedBox(height: 3),
              Text(
                subtitle,
                style: TextStyle(
                  fontSize: 12,
                  color: scheme.onSurfaceVariant,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class Ediclass EditorPage extends StatefulWidget {
  const EditorPage({super.key, required this.file, required this.mode});

  final File file;
  final CaptureMode mode;

  @override
  State<EditorPage> createState() => _EditorPageState();
}

class _EditorPageState extends State<EditorPage> {
  final _service = ImageService();

  String? _photoPath;
  String? _signaturePath;
  String _status = 'Ready';
  bool _processing = false;
  bool _saving = false;
  ProcessedOutputs? _outputs;

  Future<void> _extract() async {
    if (_processing) return;

    setState(() {
      _processing = true;
      _status = 'Analyzing form and locating boxes…';
      _photoPath = null;
      _signaturePath = null;
      _outputs = null;
    });

    try {
      final template = await TemplateService.loadClass8Template();
      late final ProcessedOutputs result;

      if (widget.mode == CaptureMode.wholeForm) {
        result = await _service.processWholeForm(widget.file, template);
      } else if (widget.mode == CaptureMode.closePhoto) {
        result = await _service.processSingle(
          widget.file,
          region: template.photo,
          widthMm: 40,
          heightMm: 50,
          maxKb: 100,
          fileName: 'photo.jpg',
        );
      } else {
        result = await _service.processSingle(
          widget.file,
          region: template.signature,
          widthMm: 50,
          heightMm: 20,
          maxKb: 60,
          fileName: 'signature.jpg',
        );
      }

      if (!mounted) return;

      setState(() {
        _outputs = result;
        _photoPath = result.photoPath;
        _signaturePath = result.signaturePath;
        _status = widget.mode == CaptureMode.wholeForm
            ? 'Photo box: ${result.photoBox.detected ? 'detected' : 'template fallback'} • '
                'Signature box: ${result.signatureBox.detected ? 'detected' : 'template fallback'}'
            : 'Output ready • ${result.width} × ${result.height}px source';
      });
    } catch (error, stack) {
      debugPrint('Image processing error: $error');
      debugPrintStack(stackTrace: stack);
      if (mounted) {
        setState(() => _status = 'Extraction failed. Please try another image.');
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Extraction failed: $error')),
        );
      }
    } finally {
      if (mounted) setState(() => _processing = false);
    }
  }

  Future<void> _saveAll() async {
    if (_saving) return;

    final paths = <String>[
      if (_photoPath != null) _photoPath!,
      if (_signaturePath != null) _signaturePath!,
    ];
    if (paths.isEmpty) return;

    setState(() => _saving = true);

    try {
      final folder = await FilePicker.platform.getDirectoryPath(
        dialogTitle: 'Choose folder for FormSnap output',
      );

      if (folder == null || folder.trim().isEmpty) return;

      final stamp = DateTime.now();
      for (var i = 0; i < paths.length; i++) {
        final source = File(paths[i]);
        if (!await source.exists()) continue;

        final type = paths[i].contains('_photo.') ? 'photo' : 'signature';
        final target = File(
          '$folder/FormSnap_${stamp.year}${stamp.month.toString().padLeft(2, '0')}${stamp.day.toString().padLeft(2, '0')}_$type.jpg',
        );
        await source.copy(target.path);
      }

      if (mounted) {
        setState(() => _status = 'Saved successfully to selected folder');
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Photo and signature saved successfully.'),
          ),
        );
      }
    } catch (error, stack) {
      debugPrint('Save error: $error');
      debugPrintStack(stackTrace: stack);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text(
              'Could not save to that folder. Choose another writable folder.',
            ),
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final hasOutput = _photoPath != null || _signaturePath != null;

    return Scaffold(
      appBar: AppBar(
        title: const Text(
          'Preview & Extract',
          style: TextStyle(fontWeight: FontWeight.w800),
        ),
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 28),
        children: [
          Card(
            clipBehavior: Clip.antiAlias,
            elevation: 0,
            child: Column(
              children: [
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.fromLTRB(16, 14, 16, 12),
                  child: Row(
                    children: [
                      Icon(Icons.preview_rounded, color: scheme.primary),
                      const SizedBox(width: 10),
                      const Expanded(
                        child: Text(
                          'Form preview',
                          style: TextStyle(
                            fontSize: 17,
                            fontWeight: FontWeight.w800,
                          ),
                        ),
                      ),
                      Text(
                        'A4 • Class 8',
                        style: TextStyle(
                          fontSize: 12,
                          color: scheme.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                ),
                Container(
                  color: scheme.surfaceContainerHighest,
                  constraints: const BoxConstraints(minHeight: 280, maxHeight: 430),
                  child: Image.file(
                    widget.file,
                    width: double.infinity,
                    fit: BoxFit.contain,
                    cacheWidth: 1200,
                    filterQuality: FilterQuality.medium,
                    errorBuilder: (_, __, ___) => const Center(
                      child: Text('Unable to display this image'),
                    ),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          Card(
            elevation: 0,
            color: scheme.primaryContainer,
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Row(
                children: [
                  Icon(Icons.center_focus_strong_rounded, color: scheme.primary),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Text(
                      _processing
                          ? 'Finding the printed boxes and preparing the output…'
                          : 'Extraction runs in a background isolate, so the screen stays responsive.',
                      style: const TextStyle(
                        fontWeight: FontWeight.w600,
                        height: 1.3,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),
          SizedBox(
            height: 54,
            child: FilledButton.icon(
              onPressed: _processing ? null : _extract,
              icon: _processing
                  ? const SizedBox(
                      width: 20,
                      height: 20,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.auto_fix_high_rounded),
              label: Text(
                _processing ? 'Extracting…' : 'Extract Photo & Signature',
              ),
            ),
          ),
          const SizedBox(height: 12),
          Text(
            _status,
            textAlign: TextAlign.center,
            style: TextStyle(
              color: scheme.onSurfaceVariant,
              fontSize: 12,
            ),
          ),
          if (hasOutput) ...[
            const SizedBox(height: 18),
            const Text(
              'Output preview',
              style: TextStyle(fontSize: 19, fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: 10),
            if (_photoPath != null)
              _ResultCard(
                title: 'Photo',
                subtitle: '40 × 50 mm • 300 DPI • ≤100 KB',
                path: _photoPath!,
              ),
            if (_signaturePath != null)
              _ResultCard(
                title: 'Signature',
                subtitle: '50 × 20 mm • 300 DPI • ≤60 KB',
                path: _signaturePath!,
              ),
            const SizedBox(height: 4),
            SizedBox(
              height: 54,
              child: FilledButton.icon(
                onPressed: _saving ? null : _saveAll,
                icon: _saving
                    ? const SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.folder_copy_rounded),
                label: Text(
                  _saving ? 'Saving…' : 'Choose Folder & Save',
                ),
              ),
            ),
          ],
          if (_outputs != null) ...[
            const SizedBox(height: 12),
            Text(
              'Source: ${_outputs!.width} × ${_outputs!.height}px',
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 11,
                color: scheme.onSurfaceVariant,
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _ResultCard extends StatelessWidget {
  const _ResultCard({
    required this.title,
    required this.subtitle,
    required this.path,
  });

  final String title;
  final String subtitle;
  final String path;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      elevation: 0,
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Row(
          children: [
            ClipRRect(
              borderRadius: BorderRadius.circular(12),
              child: Image.file(
                File(path),
                width: 112,
                height: 112,
                fit: BoxFit.cover,
                cacheWidth: 360,
                errorBuilder: (_, __, ___) => Container(
                  width: 112,
                  height: 112,
                  color: scheme.surfaceContainerHighest,
                  child: const Icon(Icons.broken_image_outlined),
                ),
              ),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: const TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  const SizedBox(height: 5),
                  Text(
                    subtitle,
                    style: TextStyle(
                      fontSize: 12,
                      color: scheme.onSurfaceVariant,
                    ),
                  ),
                  const SizedBox(height: 10),
                  const Text(
                    'Ready to save',
                    style: TextStyle(fontWeight: FontWeight.w700),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
