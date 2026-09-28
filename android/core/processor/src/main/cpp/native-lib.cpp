#include "native-lib.h"

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <memory>

namespace {
constexpr int kPhoto = 0;
constexpr int kSignature = 1;
constexpr double kMinAreaRatio = 0.0015;
constexpr double kMaxAreaRatio = 0.55;

struct Result {
    cv::Rect bounds;
    double score;
};

static double intersectionOverUnion(const cv::Rect& a, const cv::Rect& b) {
    const int left = std::max(a.x, b.x);
    const int top = std::max(a.y, b.y);
    const int right = std::min(a.x + a.width, b.x + b.width);
    const int bottom = std::min(a.y + a.height, b.y + b.height);
    if (right <= left || bottom <= top) return 0.0;
    const double inter = static_cast<double>(right - left) * static_cast<double>(bottom - top);
    const double areaA = static_cast<double>(a.area());
    const double areaB = static_cast<double>(b.area());
    return inter / std::max(1.0, areaA + areaB - inter);
}

static double ratioFit(double ratio, int kind) {
    const double target = kind == kPhoto ? 0.80 : 2.50;
    const double span = kind == kPhoto ? 0.65 : 1.65;
    return std::max(0.0, 1.0 - std::abs(ratio - target) / span);
}

static Result detectIndependent(const cv::Mat& source, int kind, double bias, int blockSize, double localC) {
    if (source.empty()) return {};
    CV_Assert(source.type() == CV_8UC3);

    cv::Mat gray;
    cv::cvtColor(source, gray, cv::COLOR_BGR2GRAY);

    cv::Mat workGray;
    const double scale = std::min(1.0, 1800.0 / static_cast<double>(std::max(source.cols, source.rows)));
    if (scale < 1.0) {
        cv::resize(gray, workGray, cv::Size(), scale, scale, cv::INTER_AREA);
    } else {
        workGray = gray;
    }

    cv::Mat smooth;
    cv::GaussianBlur(workGray, smooth, cv::Size(5, 5), 0.0);

    int safeBlock = std::max(3, blockSize | 1);
    cv::Mat thresholded;
    const double c = std::clamp(localC + bias, -32.0, 32.0);
    cv::adaptiveThreshold(
        smooth,
        thresholded,
        255,
        cv::ADAPTIVE_THRESH_GAUSSIAN_C,
        cv::THRESH_BINARY_INV,
        safeBlock,
        c);

    cv::Mat horizontal;
    cv::Mat vertical;
    const int kx = std::max(3, workGray.cols / (kind == kPhoto ? 18 : 24));
    const int ky = std::max(3, workGray.rows / (kind == kPhoto ? 26 : 50));
    cv::morphologyEx(thresholded, horizontal, cv::MORPH_OPEN,
                     cv::getStructuringElement(cv::MORPH_RECT, cv::Size(kx, 1)));
    cv::morphologyEx(thresholded, vertical, cv::MORPH_OPEN,
                     cv::getStructuringElement(cv::MORPH_RECT, cv::Size(1, ky)));

    cv::Mat connected;
    cv::bitwise_or(horizontal, vertical, connected);
    cv::dilate(connected, connected, cv::Mat(), cv::Point(-1, -1), 1);

    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(connected, contours, cv::RETR_LIST, cv::CHAIN_APPROX_SIMPLE);

    const double imageArea = static_cast<double>(workGray.cols) * static_cast<double>(workGray.rows);
    Result best{cv::Rect(), -1.0};

    for (const auto& contour : contours) {
        const cv::Rect rect = cv::boundingRect(contour);
        const double area = static_cast<double>(rect.area());
        const double areaRatio = area / std::max(1.0, imageArea);
        if (areaRatio < kMinAreaRatio || areaRatio > kMaxAreaRatio) continue;

        const double ratio = static_cast<double>(rect.width) / std::max(1, rect.height);
        if (kind == kPhoto) {
            if (ratio < 0.55 || ratio > 1.20 || rect.width < 80 || rect.height < 100) continue;
        } else {
            if (ratio < 1.45 || ratio > 5.0 || rect.width < 120 || rect.height < 18) continue;
        }

        const double contourArea = std::abs(cv::contourArea(contour));
        const double rectangularity = contourArea / std::max(1.0, area);

        cv::Mat roi = workGray(rect);
        cv::Mat roiEdges;
        cv::Canny(roi, roiEdges, 45, 140);
        const double edgeDensity =
            static_cast<double>(cv::countNonZero(roiEdges)) / std::max(1.0, area);

        const double score =
            ratioFit(ratio, kind) * 0.38 +
            std::clamp(rectangularity, 0.0, 1.0) * 0.22 +
            std::clamp(edgeDensity / 0.28, 0.0, 1.0) * 0.20 +
            std::clamp(areaRatio / 0.18, 0.0, 1.0) * 0.20;

        if (score > best.score) {
            best = Result{rect, score};
        }
    }

    if (best.score < 0.20) return {};

    if (scale < 1.0) {
        const int x = static_cast<int>(std::round(best.bounds.x / scale));
        const int y = static_cast<int>(std::round(best.bounds.y / scale));
        const int r = static_cast<int>(std::round((best.bounds.x + best.bounds.width) / scale));
        const int b = static_cast<int>(std::round((best.bounds.y + best.bounds.height) / scale));
        best.bounds = cv::Rect(
            std::clamp(x, 0, source.cols - 1),
            std::clamp(y, 0, source.rows - 1),
            std::max(1, std::min(source.cols - std::clamp(x, 0, source.cols - 1), r - x)),
            std::max(1, std::min(source.rows - std::clamp(y, 0, source.rows - 1), b - y)));
    }

    const int padX = std::max(2, static_cast<int>(best.bounds.width * (kind == kPhoto ? 0.035 : 0.08)));
    const int padY = std::max(2, static_cast<int>(best.bounds.height * (kind == kPhoto ? 0.045 : 0.18)));
    const int left = std::max(0, best.bounds.x - padX);
    const int top = std::max(0, best.bounds.y - padY);
    const int right = std::min(source.cols, best.bounds.x + best.bounds.width + padX);
    const int bottom = std::min(source.rows, best.bounds.y + best.bounds.height + padY);
    best.bounds = cv::Rect(left, top, std::max(1, right - left), std::max(1, bottom - top));
    return best;
}
}

extern "C"
JNIEXPORT jlong JNICALL
Java_org_techwithkaushik_formsnap_core_processor_NativeProcessor_nativeDetect(
    JNIEnv* env,
    jobject,
    jlong bgrMatAddr,
    jint kind,
    jdouble bias,
    jint blockSize,
    jdouble localC) {
    try {
        auto* source = reinterpret_cast<cv::Mat*>(bgrMatAddr);
        if (source == nullptr || source->empty()) return 0;
        auto result = std::make_unique<Result>(
            detectIndependent(*source, static_cast<int>(kind), static_cast<double>(bias),
                              static_cast<int>(blockSize), static_cast<double>(localC)));
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

extern "C"
JNIEXPORT jlong JNICALL
Java_org_techwithkaushik_formsnap_core_processor_NativeProcessor_nativeReleaseResult(
    JNIEnv*,
    jobject,
    jlong resultAddr) {
    auto* result = reinterpret_cast<Result*>(resultAddr);
    delete result;
    return 0;
}
