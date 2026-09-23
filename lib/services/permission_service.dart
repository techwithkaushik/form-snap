import 'package:flutter/foundation.dart';
import 'package:permission_handler/permission_handler.dart';

enum CameraPermissionResult {
  granted,
  denied,
  permanentlyDenied,
  restricted,
  unavailable,
  error,
}

class PermissionService {
  Future<PermissionStatus> cameraStatus() async {
    return Permission.camera.status;
  }

  Future<CameraPermissionResult> requestCamera({
    bool showRationale = true,
  }) async {
    try {
      var status = await Permission.camera.status;

      if (status.isGranted) {
        return CameraPermissionResult.granted;
      }

      if (status.isRestricted) {
        return CameraPermissionResult.restricted;
      }

      if (status.isPermanentlyDenied) {
        return CameraPermissionResult.permanentlyDenied;
      }

      if (showRationale && await Permission.camera.shouldShowRequestRationale) {
        // The UI layer presents the rationale. We intentionally do not request
        // twice here so Android gets one clear request flow.
        return CameraPermissionResult.denied;
      }

      status = await Permission.camera.request();

      if (status.isGranted) {
        return CameraPermissionResult.granted;
      }

      // Android may report a permanent denial only from the request result.
      if (status.isPermanentlyDenied) {
        return CameraPermissionResult.permanentlyDenied;
      }

      if (status.isRestricted) {
        return CameraPermissionResult.restricted;
      }

      if (status.isDenied) {
        return CameraPermissionResult.denied;
      }

      return CameraPermissionResult.unavailable;
    } catch (error, stackTrace) {
      debugPrint('Camera permission error: $error');
      debugPrintStack(stackTrace: stackTrace);
      return CameraPermissionResult.error;
    }
  }

  Future<bool> openSettings() async {
    try {
      return await openAppSettings();
    } catch (error, stackTrace) {
      debugPrint('Unable to open app settings: $error');
      debugPrintStack(stackTrace: stackTrace);
      return false;
    }
  }
}
