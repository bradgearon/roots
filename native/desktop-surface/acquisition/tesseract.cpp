#include "tesseract.h"
#include <algorithm>
#include <cstring>
#include <cwctype>
#include <utility>
#include <filesystem>
#include <string>
#include <vector>

namespace fs=std::filesystem;
namespace {
std::wstring environment(const wchar_t* name) {
    DWORD count=GetEnvironmentVariableW(name,nullptr,0);
    if(!count||count>32768)return {};
    std::wstring value(count,L'\0');
    DWORD actual=GetEnvironmentVariableW(name,value.data(),count);
    if(!actual||actual>=count)return {};
    value.resize(actual);return value;
}
std::string utf8(const std::wstring& value) {
    int n=WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,value.data(),static_cast<int>(value.size()),nullptr,0,nullptr,nullptr);
    if(n<=0)return {};
    std::string out(n,'\0');
    if(!WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,value.data(),static_cast<int>(value.size()),out.data(),n,nullptr,nullptr))return {};
    return out;
}
std::wstring wide(const char* value) {
    if(!value)return {};
    const auto len=std::strlen(value);
    if(!len||len>8192)return {};
    int n=MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,value,static_cast<int>(len),nullptr,0);
    if(n<=0||n>2000)return {};
    std::wstring result(n,L'\0');
    if(!MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,value,static_cast<int>(len),result.data(),n))return {};
    while(!result.empty()&&(result.back()==L'\n'||result.back()==L'\r'))result.pop_back();
    return result;
}
}
struct TesseractOcr::Impl {
    HMODULE library{};
    void* api{};
    using Create=void* (__cdecl*)();
    using Delete=void (__cdecl*)(void*);
    using Init=int (__cdecl*)(void*,const char*,const char*);
    using Version=const char* (__cdecl*)();
    using SetMode=void (__cdecl*)(void*,int);
    using SetImage=void (__cdecl*)(void*,const unsigned char*,int,int,int,int);
    using SetVariable=int (__cdecl*)(void*,const char*,const char*);
    using Recognize=int (__cdecl*)(void*,void*);
    using Iterator=void* (__cdecl*)(void*);
    using IteratorNext=int (__cdecl*)(void*,int);
    using Text=char* (__cdecl*)(const void*,int);
    using DeleteText=void (__cdecl*)(const char*);
    using Page=const void* (__cdecl*)(const void*);
    using Box=int (__cdecl*)(const void*,int,int*,int*,int*,int*);
    Create create{};Delete destroy{},clear{},deleteIterator{};Init init{};Version version{};
    SetMode mode{},resolution{};SetImage image{};SetVariable variable{};Recognize run{};
    Iterator iterator{};IteratorNext next{};Text text{};DeleteText deleteText{};Page page{};Box box{};
    template<class T> bool symbol(T& fn,const char* name) {
        fn=reinterpret_cast<T>(GetProcAddress(library,name));return fn!=nullptr;
    }
    bool bind() {
        return symbol(create,"TessBaseAPICreate")&&symbol(destroy,"TessBaseAPIDelete")&&
            symbol(clear,"TessBaseAPIClear")&&symbol(init,"TessBaseAPIInit3")&&
            symbol(version,"TessVersion")&&symbol(mode,"TessBaseAPISetPageSegMode")&&
            symbol(resolution,"TessBaseAPISetSourceResolution")&&symbol(image,"TessBaseAPISetImage")&&symbol(variable,"TessBaseAPISetVariable")&&
            symbol(run,"TessBaseAPIRecognize")&&symbol(iterator,"TessBaseAPIGetIterator")&&
            symbol(deleteIterator,"TessResultIteratorDelete")&&symbol(next,"TessResultIteratorNext")&&
            symbol(text,"TessResultIteratorGetUTF8Text")&&symbol(deleteText,"TessDeleteText")&&
            symbol(page,"TessResultIteratorGetPageIteratorConst")&&symbol(box,"TessPageIteratorBoundingBox");
    }
    void close() noexcept {
        if(api&&destroy)destroy(api);api=nullptr;
        if(library)FreeLibrary(library);library=nullptr;
    }
    ~Impl(){close();}
    Impl(){
        try{
            auto configured=environment(L"ROOTS_TESSERACT_DIR");
            fs::path dir;
            if(!configured.empty())dir=configured;
            else{
                auto programFiles=environment(L"ProgramFiles");
                if(programFiles.empty())return;
                dir=fs::path(programFiles)/L"Tesseract-OCR";
            }
            if(!dir.is_absolute()||!fs::is_directory(dir))return;
            const auto data=utf8((dir/L"tessdata").wstring());
            if(data.empty()||!fs::is_directory(dir/L"tessdata"))return;
            auto language=utf8(environment(L"ROOTS_OCR_LANG"));
            if(language.empty())language="eng";
            if(language.size()>96||language.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_+")!=std::string::npos)return;
            std::vector<fs::path> candidates;
            for(const auto& entry:fs::directory_iterator(dir)){
                if(!entry.is_regular_file())continue;
                auto name=entry.path().filename().wstring();
                for(auto& ch:name)ch=static_cast<wchar_t>(towlower(ch));
                if(name.ends_with(L".dll")&&(name.starts_with(L"libtesseract")||name.starts_with(L"tesseract")))
                    candidates.push_back(entry.path());
            }
            std::sort(candidates.begin(),candidates.end());
            for(const auto& candidate:candidates){
                // Never search the working directory or load an arbitrary DLL
                // named in captured text. Only the explicitly configured/installed directory.
                library=LoadLibraryExW(candidate.c_str(),nullptr,LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR|LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);
                if(!library)continue;
                if(bind()&&version()&&std::strncmp(version(),"5.",2)==0){
                    api=create();
                    if(api&&init(api,data.c_str(),language.c_str())==0){
                        mode(api,11); // sparse text, suitable for UI/screens
                        variable(api,"tessedit_write_images","0");
                        variable(api,"debug_file","NUL");
                        return;
                    }
                }
                close();
            }
        }catch(...){close();}
    }
    bool recognize(const uint8_t* pixels,int w,int h,int stride,std::vector<SurfaceText>& rows){
        if(!api||!pixels||w<=0||h<=0||stride<w)return false;
        image(api,pixels,w,h,1,stride);resolution(api,144);
        struct Cleanup{Impl* owner;void* it{};~Cleanup(){if(it)owner->deleteIterator(it);owner->clear(owner->api);}} cleanup{this};
        if(run(api,nullptr)!=0)return false;
        cleanup.it=iterator(api);
        if(!cleanup.it)return true;
        constexpr int line=2; // RIL_TEXTLINE
        do{
            int l=0,t=0,r=0,b=0;
            const void* pi=page(cleanup.it);
            if(!pi||!box(pi,line,&l,&t,&r,&b)||l<0||t<0||r>w||b>h||r<=l||b<=t)continue;
            char* result=text(cleanup.it,line);
            if(result){
                std::wstring value;
                try{value=wide(result);}catch(...){deleteText(result);throw;}
                deleteText(result);
                if(!value.empty())rows.push_back({std::move(value),{l,t,r,b}});
            }
            if(rows.size()>=512)break;
        }while(next(cleanup.it,line));
        return true;
    }
};
TesseractOcr::TesseractOcr():impl(std::make_unique<Impl>()){}
TesseractOcr::~TesseractOcr()=default;
bool TesseractOcr::available()const noexcept{return impl&&impl->api;}
bool TesseractOcr::recognize(const uint8_t* pixels,int width,int height,int stride,std::vector<SurfaceText>& rows){
    return impl->recognize(pixels,width,height,stride,rows);
}
