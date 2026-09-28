#include <jni.h>
#include <android/log.h>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace {

constexpr int kMaxDimension = 1024;
constexpr int kMinAdaptiveBlock = 3;
constexpr int kMaxAdaptiveBlock = 99;
constexpr double kMinConstant = -32.0;
constexpr double kMaxConstant = 32.0;
constexpr int kJpegQuality = 92;
constexpr char kLogTag[] = "FormSnapProcessor";

int normalizedOddBlock(int requested) {
    const int bounded = std::clamp(
        requested,
        kMinAdaptiveBlock,
        kMaxAdaptiveBlock
    );
    return bounded % 2 == 0 ? bounded + 1 : bounded;
}

double normalizedConstant(double requested) {
    if (!std::isfinite(requested)) {
        return 8.0;
    }
    return std::clamp(
        requested,
        kMinConstant,
        kMaxConstant
    );
}

cv::Mat decodeColor(
    const jbyte* bytes,
    jsize size
) {
    if (bytes == nullptr || size <= 0) {
        return {};
    }

    std::vector<uchar> encoded(
        reinterpret_cast<const uchar*>(bytes),
        reinterpret_cast<const uchar*>(bytes) + size
    );

    cv::Mat decoded = cv::imdecode(
        encoded,
        cv::IMREAD_COLOR
    );
    encoded.clear();
    encoded.shrink_to_fit();
    return decoded;
}

cv::Mat resizePreservingAspect(
    const cv::Mat& source
) {
    if (source.empty()) {
        return {};
    }

    const int width = source.cols;
    const int height = source.rows;
    const int maxDimension = std::max(width, height);

    if (maxDimension <= kMaxDimension) {
        return source.clone();
    }

    const double scale =
        static_cast<double>(kMaxDimension) /
        static_cast<double>(maxDimension);

    const int targetWidth = std::max(
        1,
        static_cast<int>(
            std::lround(static_cast<double>(width) * scale)
        )
    );

    const int targetHeight = std::max(
        1,
        static_cast<int>(
            std::lround(static_cast<double>(height) * scale)
        )
    );

    cv::Mat resized;
    cv::resize(
        source,
        resized,
        cv::Size(targetWidth, targetHeight),
        0.0,
        0.0,
        cv::INTER_AREA
    );
    return resized;
}

jbyteArray encodeJpeg(
    JNIEnv* env,
    const cv::Mat& image
) {
    if (env == nullptr || image.empty()) {
        return nullptr;
    }

    std::vector<int> parameters{
        cv::IMWRITE_JPEG_QUALITY,
        kJpegQuality,
        cv::IMWRITE_JPEG_OPTIMIZE,
        1
    };

    std::vector<uchar> encoded;
    if (!cv::imencode(
        ".jpg",
        image,
        encoded,
        parameters
    )) {
        encoded.clear();
        encoded.shrink_to_fit();
        return nullptr;
    }

    const jsize outputSize =
        static_cast<jsize>(encoded.size());

    jbyteArray output =
        env->NewByteArray(outputSize);

    if (output == nullptr) {
        encoded.clear();
        encoded.shrink_to_fit();
        return nullptr;
    }

    env->SetByteArrayRegion(
        output,
        0,
        outputSize,
        reinterpret_cast<const jbyte*>(encoded.data())
    );

    encoded.clear();
    encoded.shrink_to_fit();

    if (env->ExceptionCheck()) {
        env->DeleteLocalRef(output);
        return nullptr;
    }

    return output;
}

void logFailure(const char* message) {
    __android_log_print(
        ANDROID_LOG_ERROR,
        kLogTag,
        "%s",
        message
    );
}

}  // namespace

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_org_techwithkaushik_formsnap_processor_ImageProcessor_processNativeForm(
    JNIEnv* env,
    jobject,
    jbyteArray input,
    jint blockSize,
    jdouble constant
) {
    if (env == nullptr || input == nullptr) {
        return nullptr;
    }

    const jsize inputSize = env->GetArrayLength(input);
    if (inputSize <= 0) {
        return nullptr;
    }

    jbyte* inputBytes =
        env->GetByteArrayElements(input, nullptr);

    if (inputBytes == nullptr) {
        return nullptr;
    }

    cv::Mat source;
    cv::Mat resized;
    cv::Mat gray;
    cv::Mat blurred;
    cv::Mat binary;

    try {
        source = decodeColor(
            inputBytes,
            inputSize
        );

        env->ReleaseByteArrayElements(
            input,
            inputBytes,
            JNI_ABORT
        );
        inputBytes = nullptr;

        if (source.empty()) {
            logFailure("Unable to decode input image.");
            source.release();
            return nullptr;
        }

        resized = resizePreservingAspect(source);
        source.release();

        if (resized.empty()) {
            logFailure("Unable to resize input image.");
            resized.release();
            return nullptr;
        }

        cv::cvtColor(
            resized,
            gray,
            cv::COLOR_BGR2GRAY
        );
        resized.release();

        if (gray.empty()) {
            logFailure("Unable to convert input image to grayscale.");
            gray.release();
            return nullptr;
        }

        cv::GaussianBlur(
            gray,
            blurred,
            cv::Size(5, 5),
            0.0,
            0.0,
            cv::BORDER_DEFAULT
        );
        gray.release();

        if (blurred.empty()) {
            logFailure("Unable to blur grayscale image.");
            blurred.release();
            return nullptr;
        }

        const int safeBlock =
            normalizedOddBlock(static_cast<int>(blockSize));

        const double safeConstant =
            normalizedConstant(static_cast<double>(constant));

        cv::adaptiveThreshold(
            blurred,
            binary,
            255,
            cv::ADAPTIVE_THRESH_GAUSSIAN_C,
            cv::THRESH_BINARY_INV,
            safeBlock,
            safeConstant
        );
        blurred.release();

        if (binary.empty()) {
            logFailure("Adaptive threshold produced an empty image.");
            binary.release();
            return nullptr;
        }

        jbyteArray output = encodeJpeg(
            env,
            binary
        );

        binary.release();
        return output;

    } catch (const cv::Exception& error) {
        __android_log_print(
            ANDROID_LOG_ERROR,
            kLogTag,
            "OpenCV processing failure: %s",
            error.what()
        );
    } catch (...) {
        logFailure(
            "Unexpected native processing failure."
        );
    }

    if (inputBytes != nullptr) {
        env->ReleaseByteArrayElements(
            input,
            inputBytes,
            JNI_ABORT
        );
    }

    source.release();
    resized.release();
    gray.release();
    blurred.release();
    binary.release();

    return nullptr;
}
