import 'dart:io';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:path_provider/path_provider.dart';
import 'services/image_service.dart';
import 'services/template_service.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const FormSnapApp());
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
  bool _busy = false;

  Future<void> _capture(CaptureMode mode) async {
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
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _pickFile() async {
    setState(() => _busy = true);
    try {
      final result = await FilePicker.platform.pickFiles(
        type: FileType.image,
        allowMultiple: false,
      );
      final path = result?.files.single.path;
      if (path != null && mounted) {
        await _openEditor(File(path), CaptureMode.wholeForm);
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _openEditor(File file, CaptureMode mode) async {
    await Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => EditorPage(file: file, mode: mode),
      ),
    );
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
            style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant),
          ),
          const SizedBox(height: 24),
          _ActionCard(
            icon: Icons.document_scanner_outlined,
            title: 'Capture Whole Form',
            subtitle: 'Take the complete page and extract photo + signature using the form template.',
            onTap: _busy ? null : () => _capture(CaptureMode.wholeForm),
          ),
          const SizedBox(height: 12),
          _ActionCard(
            icon: Icons.photo_camera_outlined,
            title: 'Capture Photo',
            subtitle: 'Capture only the photograph and prepare it at the configured size.',
            onTap: _busy ? null : () => _capture(CaptureMode.closePhoto),
          ),
          const SizedBox(height: 12),
          _ActionCard(
            icon: Icons.draw_outlined,
            title: 'Capture Signature',
            subtitle: 'Capture only the signature and prepare it at the configured size.',
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
                  const Text('Class 8 • 2026–27 template',
                      style: TextStyle(fontWeight: FontWeight.w700)),
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
                    Text(title, style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700)),
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
    } catch (e) {
      if (mounted) setState(() => _status = 'Processing failed: $e');
    }
  }

  Future<void> _saveAll() async {
    final dir = await getApplicationDocumentsDirectory();
    final folder = Directory('${dir.path}/FormSnap');
    await folder.create(recursive: true);

    for (final path in [_photoPath, _signaturePath]) {
      if (path == null) continue;
      final name = path.split(Platform.pathSeparator).last;
      await File(path).copy('${folder.path}/${DateTime.now().millisecondsSinceEpoch}_$name');
    }

    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Saved to ${folder.path}')),
      );
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
            _ResultCard(title: 'Signature • 50 × 20 mm', path: _signaturePath!),
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
    final bytes = File(path).lengthSync();
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
                File(path),
                height: 180,
                fit: BoxFit.contain,
              ),
            ),
            const SizedBox(height: 8),
            Text('File size: ${(bytes / 1024).toStringAsFixed(1)} KB'),
          ],
        ),
      ),
    );
  }
}
