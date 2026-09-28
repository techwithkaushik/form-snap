#include <jni.h>
#include <android/log.h>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include <algorithm>
#include <vector>

namespace {
constexpr int kMaxDimension = 1024;
constexpr const char* kLogTag = "FormSnapProcessor";

cv::Mat decodeBgr(const jbyte* bytes, jsize size) {
    std::vector<uchar> encoded(
        reinterpret_cast<const uchar*>(bytes),
        reinterpret_cast<const uchar*>(bytes) + size);
    return cv::imdecode(encoded, cv::IMREAD_COLOR);
}

cv::Mat resizeToLimit(const cv::Mat& source) {
    if (source.empty()) return {};
    const int maxDimension = std::max(source.cols, source.rows);
    if (maxDimension <= kMaxDimension) return source.clone();

    const double scale =
        static_cast<double>(kMaxDimension) / static_cast<double>(maxDimension);
    const int width = std::max(1, static_cast<int>(std::lround(source.cols * scale)));
    const int height = std::max(1, static_cast<int>(std::lround(source.rows * scale)));

    cv::Mat resized;
    cv::resize(source, resized, cv::Size(width, height), 0.0, 0.0, cv::INTER_AREA);
    return resized;
}
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_org_techwithkaushik_formsnap_processor_ImageProcessor_processNativeForm(
    JNIEnv* env,
    jobject,
    jbyteArray input,
    jint blockSize,
    jdouble constant) {
    if (input == nullptr) return nullptr;

    const jsize inputSize = env->GetArrayLength(input);
    if (inputSize <= 0) return nullptr;

    jboolean isCopy = JNI_FALSE;
    jbyte* inputBytes = env->GetByteArrayElements(input, &isCopy);
    if (inputBytes == nullptr) return nullptr;

    cv::Mat source;
    cv::Mat resized;
    cv::Mat gray;
    cv::Mat blurred;
    cv::Mat binary;
    cv::Mat encoded;

    try {
        source = decodeBgr(inputBytes, inputSize);

        env->ReleaseByteArrayElements(input, inputBytes, JNI_ABORT);
        inputBytes = nullptr;

        if (source.empty()) return nullptr;

        resized = resizeToLimit(source);
        source.release();

        if (resized.empty()) {
            resized.release();
            return nullptr;
        }

        cv::cvtColor(resized, gray, cv::COLOR_BGR2GRAY);
        resized.release();

        cv::GaussianBlur(gray, blurred, cv::Size(5, 5), 0.0);
        gray.release();

        const int safeBlock = std::max(3, std::min(99, static_cast<int>(blockSize) | 1));
        cv::adaptiveThreshold(
            blurred,
            binary,
            255,
            cv::ADAPTIVE_THRESH_GAUSSIAN_C,
            cv::THRESH_BINARY_INV,
            safeBlock,
            std::clamp(static_cast<double>(constant), -32.0, 32.0));
        blurred.release();

        std::vector<int> params = {cv::IMWRITE_JPEG_QUALITY, 92};
        if (!cv::imencode(".jpg", binary, encoded, params)) {
            binary.release();
            return nullptr;
        }
        binary.release();

        jbyteArray output = env->NewByteArray(static_cast<jsize>(encoded.total()));
        if (output == nullptr) {
            encoded.release();
            return nullptr;
        }

        env->SetByteArrayRegion(
            output,
            0,
            static_cast<jsize>(encoded.total()),
            reinterpret_cast<const jbyte*>(encoded.data));
        encoded.release();
        return output;
    } catch (const cv::Exception& error) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", error.what());
    }

    if (inputBytes != nullptr) {
        env->ReleaseByteArrayElements(input, inputBytes, JNI_ABORT);
    }
    source.release();
    resized.release();
    gray.release();
    blurred.release();
    binary.release();
    encoded.release();
    return nullptr;
}
