#include <jni.h>
#include <android/log.h>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>

#include <algorithm>
#include <cmath>
#include <vector>

namespace {

constexpr int kMaxDimension = 1024;
constexpr int kMinBlockSize = 3;
constexpr int kMaxBlockSize = 99;
constexpr double kDefaultConstant = 8.0;
constexpr double kMinConstant = -32.0;
constexpr double kMaxConstant = 32.0;
constexpr int kJpegQuality = 92;
constexpr char kTag[] = "FormSnapProcessor";

int safeBlockSize(int requested) {
    const int bounded = std::clamp(
        requested,
        kMinBlockSize,
        kMaxBlockSize
    );
    return (bounded % 2 == 0) ? bounded + 1 : bounded;
}

double safeConstant(double requested) {
    if (!std::isfinite(requested)) {
        return kDefaultConstant;
    }
    return std::clamp(
        requested,
        kMinConstant,
        kMaxConstant
    );
}

cv::Mat resizePreservingAspectRatio(const cv::Mat& src) {
    if (src.empty()) {
        return {};
    }

    const int sourceWidth = src.cols;
    const int sourceHeight = src.rows;
    const int largestSide = std::max(
        sourceWidth,
        sourceHeight
    );

    if (largestSide <= kMaxDimension) {
        return src.clone();
    }

    const double scale =
        static_cast<double>(kMaxDimension) /
        static_cast<double>(largestSide);

    const int targetWidth = std::max(
        1,
        static_cast<int>(
            std::lround(
                static_cast<double>(sourceWidth) * scale
            )
        )
    );

    const int targetHeight = std::max(
        1,
        static_cast<int>(
            std::lround(
                static_cast<double>(sourceHeight) * scale
            )
        )
    );

    cv::Mat working_img;
    cv::resize(
        src,
        working_img,
        cv::Size(targetWidth, targetHeight),
        0.0,
        0.0,
        cv::INTER_AREA
    );
    return working_img;
}

jbyteArray encodeJpeg(
    JNIEnv* env,
    const cv::Mat& image
) {
    if (env == nullptr || image.empty()) {
        return nullptr;
    }

    std::vector<uchar> encoded;
    const std::vector<int> parameters{
        cv::IMWRITE_JPEG_QUALITY,
        kJpegQuality
    };

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

    const jsize size =
        static_cast<jsize>(encoded.size());

    jbyteArray output =
        env->NewByteArray(size);

    if (output == nullptr) {
        encoded.clear();
        encoded.shrink_to_fit();
        return nullptr;
    }

    env->SetByteArrayRegion(
        output,
        0,
        size,
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

void logError(const char* message) {
    __android_log_print(
        ANDROID_LOG_ERROR,
        kTag,
        "%s",
        message
    );
}

} 

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

    const jsize inputSize =
        env->GetArrayLength(input);

    if (inputSize <= 0) {
        return nullptr;
    }

    jbyte* inputBytes =
        env->GetByteArrayElements(input, nullptr);

    if (inputBytes == nullptr) {
        return nullptr;
    }

    cv::Mat src;
    cv::Mat working_img;
    cv::Mat gray;
    cv::Mat blurred;
    cv::Mat binary;

    jbyteArray output = nullptr;

    try {
        std::vector<uchar> encoded(
            reinterpret_cast<const uchar*>(inputBytes),
            reinterpret_cast<const uchar*>(inputBytes) + inputSize
        );

        src = cv::imdecode(
            encoded,
            cv::IMREAD_COLOR
        );

        encoded.clear();
        encoded.shrink_to_fit();

        env->ReleaseByteArrayElements(
            input,
            inputBytes,
            JNI_ABORT
        );
        inputBytes = nullptr;

        if (src.empty()) {
            logError("Input image decoding failed.");
            throw std::runtime_error(
                "Unable to decode input image."
            );
        }

        working_img =
            resizePreservingAspectRatio(src);

        src.release();

        if (working_img.empty()) {
            logError("Aspect-ratio preserving resize failed.");
            throw std::runtime_error(
                "Unable to resize input image."
            );
        }

        cv::cvtColor(
            working_img,
            gray,
            cv::COLOR_BGR2GRAY
        );

        working_img.release();

        if (gray.empty()) {
            logError("Grayscale conversion failed.");
            throw std::runtime_error(
                "Unable to convert image to grayscale."
            );
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
            logError("Gaussian blur failed.");
            throw std::runtime_error(
                "Unable to blur grayscale image."
            );
        }

        cv::adaptiveThreshold(
            blurred,
            binary,
            255,
            cv::ADAPTIVE_THRESH_GAUSSIAN_C,
            cv::THRESH_BINARY_INV,
            safeBlockSize(
                static_cast<int>(blockSize)
            ),
            safeConstant(
                static_cast<double>(constant)
            )
        );

        blurred.release();

        if (binary.empty()) {
            logError("Adaptive threshold failed.");
            throw std::runtime_error(
                "Adaptive threshold produced no output."
            );
        }

        output = encodeJpeg(
            env,
            binary
        );

        binary.release();

        if (output == nullptr) {
            throw std::runtime_error(
                "Unable to encode processed image."
            );
        }

        return output;
    } catch (const cv::Exception& error) {
        __android_log_print(
            ANDROID_LOG_ERROR,
            kTag,
            "OpenCV exception: %s",
            error.what()
        );
    } catch (const std::exception& error) {
        __android_log_print(
            ANDROID_LOG_ERROR,
            kTag,
            "Native processing exception: %s",
            error.what()
        );
    } catch (...) {
        logError(
            "Unknown native processing exception."
        );
    }

    if (inputBytes != nullptr) {
        env->ReleaseByteArrayElements(
            input,
            inputBytes,
            JNI_ABORT
        );
        inputBytes = nullptr;
    }

    if (src.data != nullptr) {
        src.release();
    }

    if (working_img.data != nullptr) {
        working_img.release();
    }

    if (gray.data != nullptr) {
        gray.release();
    }

    if (blurred.data != nullptr) {
        blurred.release();
    }

    if (binary.data != nullptr) {
        binary.release();
    }

    if (output != nullptr) {
        env->DeleteLocalRef(output);
        output = nullptr;
    }

    return nullptr;
}
