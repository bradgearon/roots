#include "ocr.h"
#include <d3d11.h>
#include <dxgi.h>
#include <windows.graphics.capture.interop.h>
#include <windows.graphics.directx.direct3d11.interop.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Graphics.Capture.h>
#include <winrt/Windows.Graphics.DirectX.h>
#include <winrt/Windows.Graphics.DirectX.Direct3D11.h>
#include <winrt/Windows.Graphics.Imaging.h>
#include <winrt/Windows.Media.Ocr.h>
#include <algorithm>
#include <cmath>

using namespace winrt;
using namespace winrt::Windows::Graphics::Capture;
using namespace winrt::Windows::Graphics::DirectX;
using namespace winrt::Windows::Graphics::DirectX::Direct3D11;
using namespace winrt::Windows::Graphics::Imaging;
using namespace winrt::Windows::Media::Ocr;

struct OcrLane::Impl {
    IDirect3DDevice device{nullptr};
    OcrEngine engine{nullptr};
    GraphicsCaptureItem item{nullptr};
    Direct3D11CaptureFramePool pool{nullptr};
    GraphicsCaptureSession session{nullptr};
    winrt::Windows::Graphics::SizeInt32 size{};
    HWND window{};
    Impl() {
        if (!GraphicsCaptureSession::IsSupported()) return;
        com_ptr<ID3D11Device> d3d;
        check_hresult(D3D11CreateDevice(nullptr,D3D_DRIVER_TYPE_HARDWARE,nullptr,
            D3D11_CREATE_DEVICE_BGRA_SUPPORT,nullptr,0,D3D11_SDK_VERSION,d3d.put(),nullptr,nullptr));
        auto dxgi = d3d.as<IDXGIDevice>();
        com_ptr<::IInspectable> inspectable;
        check_hresult(CreateDirect3D11DeviceFromDXGIDevice(dxgi.get(), inspectable.put()));
        device = inspectable.as<IDirect3DDevice>();
        engine = OcrEngine::TryCreateFromUserProfileLanguages();
    }
    bool fits(winrt::Windows::Graphics::SizeInt32 s) const {
        return s.Width>0 && s.Height>0 && s.Width<=3840 && s.Height<=2160 &&
            static_cast<uint32_t>(s.Width)<=OcrEngine::MaxImageDimension() &&
            static_cast<uint32_t>(s.Height)<=OcrEngine::MaxImageDimension();
    }
    void reset() noexcept {
        try { if(session) session.Close(); } catch(...) {}
        try { if(pool) pool.Close(); } catch(...) {}
        session=nullptr; pool=nullptr; item=nullptr; window=nullptr;
    }
    bool begin(HWND hwnd) {
        reset();
        if(!device || !engine) return false;
        auto factory=get_activation_factory<GraphicsCaptureItem, IGraphicsCaptureItemInterop>();
        check_hresult(factory->CreateForWindow(hwnd,guid_of<IGraphicsCaptureItem>(),put_abi(item)));
        size=item.Size();
        if(!fits(size)) { reset(); return false; }
        pool=Direct3D11CaptureFramePool::CreateFreeThreaded(device,
            DirectXPixelFormat::B8G8R8A8UIntNormalized,2,size);
        session=pool.CreateCaptureSession(item);
        // Do not disable the system capture indicator.
        session.IsCursorCaptureEnabled(false);
        session.StartCapture(); window=hwnd;
        return true;
    }
    bool read(HWND hwnd,std::vector<SurfaceText>& output) {
        if(window!=hwnd && !begin(hwnd)) return false;
        Direct3D11CaptureFrame frame{nullptr};
        for(int i=0;i<2;++i) {
            auto next=pool.TryGetNextFrame();
            if(!next) break;
            if(frame) frame.Close();
            frame=next;
        }
        if(!frame) return false;
        const auto actual=frame.ContentSize();
        if(actual.Width!=size.Width || actual.Height!=size.Height) {
            frame.Close();
            if(!fits(actual)) { reset(); return false; }
            size=actual;
            pool.Recreate(device,DirectXPixelFormat::B8G8R8A8UIntNormalized,2,size);
            return false;
        }
        // One bounded readback per OCR tick; no encoding, images, or video storage.
        auto bitmap=SoftwareBitmap::CreateCopyFromSurfaceAsync(frame.Surface()).get();
        frame.Close();
        auto gray=SoftwareBitmap::Convert(bitmap,BitmapPixelFormat::Gray8);
        bitmap.Close();
        auto result=engine.RecognizeAsync(gray).get();
        gray.Close();
        for(const auto& line:result.Lines()) {
            if(output.size()>=64) break;
            auto words=line.Words();
            if(words.Size()==0) continue;
            float l=static_cast<float>(size.Width), t=static_cast<float>(size.Height), r=0,b=0;
            for(const auto& word:words) {
                auto box=word.BoundingRect();
                l=std::min(l,box.X); t=std::min(t,box.Y);
                r=std::max(r,box.X+box.Width); b=std::max(b,box.Y+box.Height);
            }
            std::wstring text=line.Text().c_str();
            if(!text.empty() && text.size()<=2000)
                output.push_back({std::move(text),{static_cast<LONG>(std::floor(l)),
                    static_cast<LONG>(std::floor(t)),static_cast<LONG>(std::ceil(r)),static_cast<LONG>(std::ceil(b))}});
        }
        return true;
    }
};
OcrLane::OcrLane():impl(std::make_unique<Impl>()){}
OcrLane::~OcrLane(){ reset(); }
void OcrLane::reset() noexcept { impl->reset(); }
bool OcrLane::read(HWND window,std::vector<SurfaceText>& output) {
    try { return impl->read(window,output); } catch(...) { reset(); output.clear(); return false; }
}
