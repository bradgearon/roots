#include "ocr.h"
#include "tesseract.h"
#include <d3d11.h>
#include <dxgi.h>
#include <dwmapi.h>
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
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <stdexcept>
#include <utility>
#include <vector>

using namespace winrt;
using namespace winrt::Windows::Graphics::Capture;
using namespace winrt::Windows::Graphics::DirectX;
using namespace winrt::Windows::Graphics::DirectX::Direct3D11;
using namespace winrt::Windows::Graphics::Imaging;
using namespace winrt::Windows::Media::Ocr;

namespace {
// Documented WinRT byte-access COM interface for IMemoryBufferReference.
// Access a bitmap plane only while the exclusive BitmapBuffer lock is held.
struct __declspec(uuid("5B0D3235-4DBA-4D44-865E-8F1D0E4FD04D")) MemoryBytes : ::IUnknown {
    virtual HRESULT __stdcall GetBuffer(uint8_t** value,uint32_t* capacity)=0;
};
template<class F> void withGray(SoftwareBitmap const& image,BitmapBufferAccessMode access,F&& fn) {
    auto lock=image.LockBuffer(access);
    auto reference=lock.CreateReference();
    auto bytes=reference.as<MemoryBytes>();
    uint8_t* data=nullptr;uint32_t capacity=0;
    check_hresult(bytes->GetBuffer(&data,&capacity));
    const auto plane=lock.GetPlaneDescription(0);
    const int w=image.PixelWidth(),h=image.PixelHeight();
    if(!data||w<=0||h<=0||plane.StartIndex<0||plane.Stride<w||
       plane.Width<w||plane.Height<h||
       static_cast<uint64_t>(plane.StartIndex)+static_cast<uint64_t>(plane.Stride)*(h-1)+w>capacity)
        throw std::runtime_error("Invalid grayscale plane");
    fn(data+plane.StartIndex,w,h,plane.Stride);
}
struct Pixels {
    int width{},height{};
    std::vector<uint8_t> data;
    explicit Pixels(SoftwareBitmap const& image) {
        withGray(image,BitmapBufferAccessMode::Read,[&](uint8_t* p,int w,int h,int stride){
            width=w;height=h;
            if(static_cast<uint64_t>(w)*h>16777216)throw std::runtime_error("Frame exceeds pixel budget");
            data.resize(static_cast<size_t>(w)*h);
            for(int y=0;y<h;++y)std::memcpy(data.data()+static_cast<size_t>(y)*w,p+static_cast<size_t>(y)*stride,w);
        });
    }
    void fill(RECT r){
        RECT bounds{0,0,width,height},clip{};
        if(!IntersectRect(&clip,&r,&bounds))return;
        for(LONG y=clip.top;y<clip.bottom;++y)
            std::fill_n(data.data()+static_cast<size_t>(y)*width+clip.left,clip.right-clip.left,uint8_t{255});
    }
};
BOOL CALLBACK collectMonitor(HMONITOR,HDC,LPRECT rect,LPARAM user){
    if(rect)reinterpret_cast<std::vector<RECT>*>(user)->push_back(*rect);
    return TRUE;
}
// Only pixels actually on a display are eligible; do not read the offscreen
// portion of a partially moved window or the holes in a multi-monitor layout.
bool clipToDisplays(Pixels& pixels,RECT const& desktop){
    std::vector<RECT> monitors;
    RECT query=desktop;
    if(!EnumDisplayMonitors(nullptr,&query,collectMonitor,reinterpret_cast<LPARAM>(&monitors))||monitors.empty())return false;
    for(auto& r:monitors)OffsetRect(&r,-desktop.left,-desktop.top);
    for(int y=0;y<pixels.height;++y){
        std::vector<std::pair<int,int>> intervals;
        for(const auto& r:monitors){
            if(y>=r.top&&y<r.bottom){
                int l=std::clamp<int>(r.left,0,pixels.width),right=std::clamp<int>(r.right,0,pixels.width);
                if(l<right)intervals.emplace_back(l,right);
            }
        }
        std::sort(intervals.begin(),intervals.end());
        auto* row=pixels.data.data()+static_cast<size_t>(y)*pixels.width;
        int at=0;
        for(const auto& [left,right]:intervals){
            if(left>at)std::fill(row+at,row+left,uint8_t{255});
            at=std::max(at,right);
        }
        if(at<pixels.width)std::fill(row+at,row+pixels.width,uint8_t{255});
    }
    return true;
}
uint64_t fingerprint(Pixels const& image){
    uint64_t value=14695981039346656037ull;
    for(auto byte:image.data){value^=byte;value*=1099511628211ull;}
    value^=static_cast<uint64_t>(image.width)<<32|static_cast<uint32_t>(image.height);
    return value;
}
bool overlaps(RECT const& a,RECT const& b){RECT out{};return IntersectRect(&out,&a,&b)!=FALSE;}
bool duplicate(SurfaceText const& a,SurfaceText const& b){
    if(a.text!=b.text)return false;
    RECT overlap{};
    if(!IntersectRect(&overlap,&a.bounds,&b.bounds))return false;
    auto area=[](RECT const& r){return static_cast<double>(r.right-r.left)*(r.bottom-r.top);};
    const auto smallerArea=std::min(area(a.bounds),area(b.bounds));
    return smallerArea>0&&area(overlap)>=0.55*smallerArea;
}
std::vector<int> starts(int extent,int tile){
    if(extent<=tile)return {0};
    int step=std::max(1,tile-96);
    std::vector<int> result;
    for(int x=0;x<extent-tile;x+=step)result.push_back(x);
    if(result.empty()||result.back()!=extent-tile)result.push_back(extent-tile);
    return result;
}
}

struct OcrLane::Impl {
    IDirect3DDevice device{nullptr};
    OcrEngine engine{nullptr};
    TesseractOcr portable;
    GraphicsCaptureItem item{nullptr};
    Direct3D11CaptureFramePool pool{nullptr};
    GraphicsCaptureSession session{nullptr};
    winrt::Windows::Graphics::SizeInt32 size{};
    HWND window{};
    const char* state="initializing";
    bool haveFingerprint=false;
    uint64_t lastFingerprint=0;
    Impl() {
        // Windows.Media.Ocr is best-effort for an unpackaged Win32 app.
        // Tesseract is a supported, resident, optional native fallback.
        try{engine=OcrEngine::TryCreateFromUserProfileLanguages();}catch(...){engine=nullptr;}
        if(!portable.available()&&!engine){
            state="no-ocr-engine";
            std::cerr<<"Surface: no OCR engine available; install local Tesseract 5 or a supported Windows OCR language. UIA remains active.\n";
            return;
        }
        std::cerr<<"Surface: OCR backend "<<(portable.available()?"Tesseract 5":"Windows OCR (best effort)")<<"\n";
        try{
            if (!GraphicsCaptureSession::IsSupported()){state="wgc-unavailable";return;}
            com_ptr<ID3D11Device> d3d;
            HRESULT hr=D3D11CreateDevice(nullptr,D3D_DRIVER_TYPE_HARDWARE,nullptr,
                D3D11_CREATE_DEVICE_BGRA_SUPPORT,nullptr,0,D3D11_SDK_VERSION,d3d.put(),nullptr,nullptr);
            if(FAILED(hr)){d3d=nullptr;hr=D3D11CreateDevice(nullptr,D3D_DRIVER_TYPE_WARP,nullptr,
                D3D11_CREATE_DEVICE_BGRA_SUPPORT,nullptr,0,D3D11_SDK_VERSION,d3d.put(),nullptr,nullptr);}
            check_hresult(hr);
            auto dxgi=d3d.as<IDXGIDevice>();
            com_ptr<::IInspectable> inspectable;
            check_hresult(CreateDirect3D11DeviceFromDXGIDevice(dxgi.get(),inspectable.put()));
            device=inspectable.as<IDirect3DDevice>();
            state="ready";
        }catch(...){state="wgc-device-failed";}
    }
    bool fits(winrt::Windows::Graphics::SizeInt32 s) const {
        return s.Width>0&&s.Height>0&&s.Width<=7680&&s.Height<=4320&&
            static_cast<uint64_t>(s.Width)*s.Height<=16777216;
    }
    void reset() noexcept {
        try { if(session)session.Close(); }catch(...){}
        try { if(pool)pool.Close(); }catch(...){}
        session=nullptr;pool=nullptr;item=nullptr;window=nullptr;
        haveFingerprint=false;
    }
    bool begin(HWND hwnd) {
        reset();
        if(!engine&&!portable.available()){state="no-ocr-engine";return false;}
        if(!device){state="wgc-unavailable";return false;}
        auto factory=get_activation_factory<GraphicsCaptureItem,IGraphicsCaptureItemInterop>();
        check_hresult(factory->CreateForWindow(hwnd,guid_of<IGraphicsCaptureItem>(),put_abi(item)));
        size=item.Size();
        if(!fits(size)){state="window-over-pixel-budget";reset();return false;}
        pool=Direct3D11CaptureFramePool::CreateFreeThreaded(device,
            DirectXPixelFormat::B8G8R8A8UIntNormalized,2,size);
        session=pool.CreateCaptureSession(item);
        // Keep the OS capture indicator and normal capture restrictions.
        session.IsCursorCaptureEnabled(false);
        session.StartCapture();window=hwnd;state="waiting-for-frame";
        return true;
    }
    RECT screenBounds(HWND hwnd) const {
        RECT rect{};
        auto fitsFrame=[&](){return rect.right-rect.left==size.Width&&rect.bottom-rect.top==size.Height;};
        if(SUCCEEDED(DwmGetWindowAttribute(hwnd,DWMWA_EXTENDED_FRAME_BOUNDS,&rect,sizeof(rect)))&&fitsFrame())return rect;
        if(GetWindowRect(hwnd,&rect)&&fitsFrame())return rect;
        return {};
    }
    bool windowsRecognize(const uint8_t* p,int width,int height,int stride,std::vector<SurfaceText>& out){
        if(!engine)return false;
        auto tile=SoftwareBitmap(BitmapPixelFormat::Gray8,width,height,BitmapAlphaMode::Ignore);
        withGray(tile,BitmapBufferAccessMode::Write,[&](uint8_t* dest,int w,int h,int step){
            for(int y=0;y<h;++y)std::memcpy(dest+static_cast<size_t>(y)*step,p+static_cast<size_t>(y)*stride,w);
        });
        auto result=engine.RecognizeAsync(tile).get();
        tile.Close();
        for(const auto& line:result.Lines()){
            if(out.size()>=512)break;
            auto words=line.Words();
            if(words.Size()==0)continue;
            float l=static_cast<float>(width),t=static_cast<float>(height),r=0,b=0;
            for(const auto& word:words){
                auto box=word.BoundingRect();
                l=std::min(l,box.X);t=std::min(t,box.Y);r=std::max(r,box.X+box.Width);b=std::max(b,box.Y+box.Height);
            }
            std::wstring text=line.Text().c_str();
            RECT bounds{static_cast<LONG>(std::floor(l)),static_cast<LONG>(std::floor(t)),
                static_cast<LONG>(std::ceil(r)),static_cast<LONG>(std::ceil(b))};
            if(!text.empty()&&text.size()<=2000&&bounds.left>=0&&bounds.top>=0&&bounds.right<=width&&bounds.bottom<=height&&!IsRectEmpty(&bounds))
                out.push_back({std::move(text),bounds});
        }
        return true;
    }
    bool read(HWND hwnd,std::vector<SurfaceText>& output,RECT& desktopBounds,const std::vector<RECT>& masks) {
        desktopBounds={};output.clear();
        if(window!=hwnd&&!begin(hwnd))return false;
        RECT before=screenBounds(hwnd);
        if(IsRectEmpty(&before)){state="unverified-window-geometry";return false;}
        // Consume the newest available capture; drain both buffers and wait
        // briefly for a fresh frame if they contain old images. Never reuse
        // an old screenshot with newly measured sensitive-control bounds.
        Direct3D11CaptureFrame frame{nullptr};
        for(int attempt=0;attempt<9;++attempt){
            for(int i=0;i<2;++i){
                auto next=pool.TryGetNextFrame();
                if(!next)break;
                if(frame)frame.Close();
                frame=next;
            }
            if(frame){
                LARGE_INTEGER now{},frequency{};
                if(QueryPerformanceCounter(&now)&&QueryPerformanceFrequency(&frequency)&&frequency.QuadPart>0){
                    const double age=static_cast<double>(now.QuadPart)/frequency.QuadPart-
                        static_cast<double>(frame.SystemRelativeTime().count())/10000000.0;
                    if(age>=-0.05&&age<=0.35)break;
                }
                frame.Close();frame=nullptr;
            }
            if(attempt<8)Sleep(15);
        }
        if(!frame){state="waiting-for-fresh-frame";return false;}
        const auto actual=frame.ContentSize();
        if(actual.Width!=size.Width||actual.Height!=size.Height){
            frame.Close();
            if(!fits(actual)){state="window-over-pixel-budget";reset();return false;}
            size=actual;pool.Recreate(device,DirectXPixelFormat::B8G8R8A8UIntNormalized,2,size);
            haveFingerprint=false;state="resized";return false;
        }
        if(GetForegroundWindow()!=hwnd){frame.Close();state="source-changed";return false;}
        auto bitmap=SoftwareBitmap::CreateCopyFromSurfaceAsync(frame.Surface()).get();
        frame.Close();
        auto gray=SoftwareBitmap::Convert(bitmap,BitmapPixelFormat::Gray8);
        bitmap.Close();
        Pixels pixels(gray);gray.Close();
        const RECT afterCopy=screenBounds(hwnd);
        if(!EqualRect(&before,&afterCopy)||pixels.width!=size.Width||pixels.height!=size.Height||GetForegroundWindow()!=hwnd){
            state="geometry-changed";return false;
        }
        if(!clipToDisplays(pixels,before)){state="not-on-visible-display";return false;}
        // Redact identified sensitive rectangles in the pixel buffer before
        // either OCR backend receives it. No image or raw pixel file exists.
        std::vector<RECT> frameMasks;
        for(RECT mask:masks){
            OffsetRect(&mask,-before.left,-before.top);
            InflateRect(&mask,8,8);
            frameMasks.push_back(mask);pixels.fill(mask);
        }
        const auto hash=fingerprint(pixels);
        if(haveFingerprint&&hash==lastFingerprint){state="unchanged";return false;}
        int limit=2200;
        if(engine){
            const auto maximum=OcrEngine::MaxImageDimension();
            if(maximum>0)limit=std::min(limit,static_cast<int>(maximum));
        }
        if(limit<256){state="unsupported-ocr-dimension";return false;}
        bool anyBackend=false,allTiles=true;
        // Full-resolution overlapping tiles keep 4K/ultrawide windows
        // readable instead of silently rejecting them at the OCR size limit.
        for(int y:starts(pixels.height,limit))for(int x:starts(pixels.width,limit)){
            if(GetForegroundWindow()!=hwnd){output.clear();state="source-changed";return false;}
            const int width=std::min(limit,pixels.width-x),height=std::min(limit,pixels.height-y);
            const auto* src=pixels.data.data()+static_cast<size_t>(y)*pixels.width+x;
            std::vector<SurfaceText> rows;
            bool ok=portable.available()&&portable.recognize(src,width,height,pixels.width,rows);
            if(!ok&&engine){
                rows.clear();
                try{ok=windowsRecognize(src,width,height,pixels.width,rows);}
                catch(...){engine=nullptr;rows.clear();}
            }
            if(!ok){allTiles=false;continue;}
            anyBackend=true;
            for(auto& row:rows){
                OffsetRect(&row.bounds,x,y);
                if(std::any_of(frameMasks.begin(),frameMasks.end(),[&](const RECT& m){return overlaps(m,row.bounds);}))continue;
                if(std::none_of(output.begin(),output.end(),[&](const auto& prior){return duplicate(prior,row);}))
                    output.push_back(std::move(row));
                if(output.size()>=512)break;
            }
            if(output.size()>=512)break;
        }
        const RECT after=screenBounds(hwnd);
        if(!EqualRect(&before,&after)||GetForegroundWindow()!=hwnd){output.clear();state="source-changed";return false;}
        if(!anyBackend){state="ocr-engine-failed";return false;}
        if(allTiles){haveFingerprint=true;lastFingerprint=hash;}
        desktopBounds=before;
        state=allTiles?(portable.available()?"tesseract":"windows-ocr"):"partial-ocr";
        return true;
    }
};
OcrLane::OcrLane():impl(std::make_unique<Impl>()){}
OcrLane::~OcrLane(){reset();}
void OcrLane::reset()noexcept{impl->reset();}
const char* OcrLane::status()const noexcept{return impl->state;}
bool OcrLane::read(HWND window,std::vector<SurfaceText>& output,RECT& desktopBounds,const std::vector<RECT>& masks){
    try{return impl->read(window,output,desktopBounds,masks);}
    catch(...){impl->reset();impl->state="capture-or-ocr-error";output.clear();desktopBounds={};return false;}
}
