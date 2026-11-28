#pragma once
#include <windows.h>
#include <memory>
#include <string>
#include <vector>

struct SurfaceText {
    std::wstring text;
    RECT bounds{};
    bool operator==(const SurfaceText& r) const {
        return text == r.text && EqualRect(&bounds, &r.bounds);
    }
};
class OcrLane {
public:
    OcrLane();
    ~OcrLane();
    OcrLane(const OcrLane&) = delete;
    OcrLane& operator=(const OcrLane&) = delete;
    void reset() noexcept;
    // Returns false when there was no new readable frame. Never writes a bitmap.
    // desktopBounds is nonempty only when the capture-to-screen transform
    // was verified. OCR output itself retains capture-frame coordinates.
    bool read(HWND window, std::vector<SurfaceText>& output, RECT& desktopBounds);
private:
    struct Impl;
    std::unique_ptr<Impl> impl;
};
