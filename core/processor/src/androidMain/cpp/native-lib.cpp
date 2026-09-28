#include "native-lib.h"
#include <algorithm>
#include <cmath>
#include <memory>
#include <vector>
#include <android/log.h>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>

namespace {
constexpr int kPhoto = 0;
constexpr int kSignature = 1;

struct Result { cv::Rect bounds; double score = 0.0; };

double ratioFit(double ratio, int kind) {
    const double target = kind == kPhoto ? 0.80 : 2.50;
    const double span = kind == kPhoto ? 0.65 : 1.65;
    return std::max(0.0, 1.0 - std::abs(ratio - target) / span);
}

Result detectIndependent(const cv::Mat& source, int kind, double bias, int blockSize, double localC) {
    if (source.empty() || source.type() != CV_8UC3) return {};
    cv::Mat gray;
    cv::cvtColor(source, gray, cv::COLOR_BGR2GRAY);
    const double scale = std::min(1.0, 1800.0 / static_cast<double>(std::max(source.cols, source.rows)));
    cv::Mat work;
    if (scale < 1.0) cv::resize(gray, work, cv::Size(), scale, scale, cv::INTER_AREA);
    else gray.copyTo(work);
    cv::Mat blurred;
    cv::GaussianBlur(work, blurred, cv::Size(5, 5), 0.0);
    const int safeBlock = std::max(3, blockSize | 1);
    const double thresholdC = std::clamp(localC + bias, -32.0, 32.0);
    cv::Mat thresholded;
    cv::adaptiveThreshold(blurred, thresholded, 255, cv::ADAPTIVE_THRESH_GAUSSIAN_C,
                          cv::THRESH_BINARY_INV, safeBlock, thresholdC);
    cv::Mat mask;
    if (kind == kSignature) {
        const auto kernel = cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(3, 3));
        cv::morphologyEx(thresholded, mask, cv::MORPH_CLOSE, kernel);
    } else {
        const int kx = std::max(3, work.cols / 18);
        const int ky = std::max(3, work.rows / 26);
        cv::Mat horizontal;
        cv::Mat vertical;
        cv::morphologyEx(thresholded, horizontal, cv::MORPH_OPEN,
                         cv::getStructuringElement(cv::MORPH_RECT, cv::Size(kx, 1)));
        cv::morphologyEx(thresholded, vertical, cv::MORPH_OPEN,
                         cv::getStructuringElement(cv::MORPH_RECT, cv::Size(1, ky)));
        cv::bitwise_or(horizontal, vertical, mask);
        cv::dilate(mask, mask, cv::Mat(), cv::Point(-1, -1), 1);
    }
    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(mask, contours, cv::RETR_LIST, cv::CHAIN_APPROX_SIMPLE);
    const double imageArea = static_cast<double>(work.cols) * static_cast<double>(work.rows);
    Result best;
    for (const auto& contour : contours) {
        const cv::Rect rect = cv::boundingRect(contour);
        const double rectArea = static_cast<double>(rect.area());
        const double areaRatio = rectArea / std::max(1.0, imageArea);
        if (areaRatio < 0.0015 || areaRatio > 0.55) continue;
        const double ratio = static_cast<double>(rect.width) / std::max(1, rect.height);
        if (kind == kPhoto) {
            if (ratio < 0.55 || ratio > 1.20 || rect.width < 80 || rect.height < 100) continue;
        } else if (ratio < 1.45 || ratio > 5.0 || rect.width < 120 || rect.height < 18) continue;
        const double rectangularity = std::abs(cv::contourArea(contour)) / std::max(1.0, rectArea);
        cv::Mat roi = work(rect);
        cv::Mat edges;
        cv::Canny(roi, edges, 45, 140);
        const double edgeDensity = static_cast<double>(cv::countNonZero(edges)) / std::max(1.0, rectArea);
        const double score = ratioFit(ratio, kind) * 0.38 +
                             std::clamp(rectangularity, 0.0, 1.0) * 0.22 +
                             std::clamp(edgeDensity / 0.28, 0.0, 1.0) * 0.20 +
                             std::clamp(areaRatio / 0.18, 0.0, 1.0) * 0.20;
        if (score > best.score) best = Result{rect, score};
    }
    if (best.score < 0.20) return {};
    if (scale < 1.0) {
        const int left = std::clamp(static_cast<int>(std::lround(best.bounds.x / scale)), 0, source.cols - 1);
        const int top = std::clamp(static_cast<int>(std::lround(best.bounds.y / scale)), 0, source.rows - 1);
        const int right = std::clamp(static_cast<int>(std::lround((best.bounds.x + best.bounds.width) / scale)), left + 1, source.cols);
        const int bottom = std::clamp(static_cast<int>(std::lround((best.bounds.y + best.bounds.height) / scale)), top + 1, source.rows);
        best.bounds = cv::Rect(left, top, right - left, bottom - top);
    }
    const int padX = std::max(2, static_cast<int>(std::lround(best.bounds.width * (kind == kPhoto ? 0.035 : 0.08))));
    const int padY = std::max(2, static_cast<int>(std::lround(best.bounds.height * (kind == kPhoto ? 0.045 : 0.18))));
    const int left = std::max(0, best.bounds.x - padX);
    const int top = std::max(0, best.bounds.y - padY);
    const int right = std::min(source.cols, best.bounds.x + best.bounds.width + padX);
    const int bottom = std::min(source.rows, best.bounds.y + best.bounds.height + padY);
    best.bounds = cv::Rect(left, top, std::max(1, right - left), std::max(1, bottom - top));
    return best;
}
}

extern "C" JNIEXPORT jlong JNICALL Java_org_techwithkaushik_formsnap_core_processor_NativeProcessor_nativeDetect(
    JNIEnv*, jobject, jlong bgrMatAddr, jint kind, jdouble bias, jint blockSize, jdouble localC) {
    try {
        auto* source = reinterpret_cast<cv::Mat*>(bgrMatAddr);
        if (source == nullptr || source->empty()) return 0;
        auto result = std::make_unique<Result>(detectIndependent(*source, static_cast<int>(kind),
            static_cast<double>(bias), static_cast<int>(blockSize), static_cast<double>(localC)));
        if (result->score < 0.20) return 0;
        return reinterpret_cast<jlong>(result.release());
    } catch (const cv::Exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, "FormSnapProcessor", "OpenCV: %s", e.what());
        return 0;
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, "FormSnapProcessor", "Unexpected native detection failure");
        return 0;
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL Java_org_techwithkaushik_formsnap_core_processor_NativeProcessor_nativeReadResult(
    JNIEnv* env, jobject, jlong resultAddr) {
    auto* result = reinterpret_cast<Result*>(resultAddr);
    if (result == nullptr) return nullptr;
    const float values[] = {
        static_cast<float>(result->bounds.x),
        static_cast<float>(result->bounds.y),
        static_cast<float>(result->bounds.x + result->bounds.width),
        static_cast<float>(result->bounds.y + result->bounds.height),
        static_cast<float>(std::clamp(result->score, 0.0, 1.0)),
    };
    jfloatArray array = env->NewFloatArray(5);
    if (array == nullptr) return nullptr;
    env->SetFloatArrayRegion(array, 0, 5, values);
    return array;
}

extern "C" JNIEXPORT jlong JNICALL Java_org_techwithkaushik_formsnap_core_processor_NativeProcessor_nativeReleaseResult(
    JNIEnv*, jobject, jlong resultAddr) {
    delete reinterpret_cast<Result*>(resultAddr);
    return 0;
}
