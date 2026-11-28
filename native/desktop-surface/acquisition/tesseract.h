#pragma once
#include "ocr.h"
#include <cstdint>
#include <memory>

// Optional resident local OCR. Uses the installed Tesseract 5 C ABI;
// no temporary image files, subprocess per frame, or network calls.
class TesseractOcr {
public:
    TesseractOcr();
    ~TesseractOcr();
    TesseractOcr(const TesseractOcr&)=delete;
    TesseractOcr& operator=(const TesseractOcr&)=delete;
    bool available() const noexcept;
    bool recognize(const uint8_t* pixels,int width,int height,int stride,
                   std::vector<SurfaceText>& rows);
private:
    struct Impl;
    std::unique_ptr<Impl> impl;
};
