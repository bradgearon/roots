// Copyright (c) 2026 We Develop Software LLC. All rights reserved.
#include <windows.h>
#include <wtsapi32.h>
#include <UIAutomation.h>
#include <wrl/client.h>
#include <algorithm>
#include <chrono>
#include <cwctype>
#include <cstdint>
#include <cstdlib>
#ifdef ROOTS_SURFACE_OCR
#include <roapi.h>
#endif
#include <iostream>
#include <memory>
#include <set>
#include <string>
#include <thread>
#include <vector>
#include "../acquisition/ocr.h"
#ifdef ROOTS_SURFACE_OCR
#include <winrt/base.h>
#endif

using Microsoft::WRL::ComPtr;
using Clock=std::chrono::steady_clock;
constexpr UINT Tick=WM_APP+1, Stop=WM_APP+2;
static DWORD ownerThread;
static bool locked=false;
static HWND signalWindow=nullptr;
static std::wstring lower(std::wstring s) {
    for(auto& c:s) c=static_cast<wchar_t>(towlower(c)); return s;
}
static std::string hex(const std::wstring& s) {
    if(s.empty())return {};
    int n=WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,s.data(),static_cast<int>(s.size()),nullptr,0,nullptr,nullptr);
    if(n<=0 || n>8192) return {};
    std::string utf8(n,'\0'),out; out.reserve(n*2);
    if(!WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,s.data(),static_cast<int>(s.size()),utf8.data(),n,nullptr,nullptr))return {};
    constexpr char digits[]="0123456789abcdef";
    for(unsigned char c:utf8){out+=digits[c>>4];out+=digits[c&15];}return out;
}
static long long utc() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
}
static long long mono(Clock::time_point t) {
    return std::chrono::duration_cast<std::chrono::microseconds>(t.time_since_epoch()).count();
}
static bool interactive() {
    if(locked)return false;
    HDESK d=OpenInputDesktop(0,FALSE,DESKTOP_READOBJECTS);
    if(!d)return false;
    wchar_t name[64]{}; DWORD needed=0;
    bool ok=GetUserObjectInformationW(d,UOI_NAME,name,sizeof(name),&needed) && _wcsicmp(name,L"Default")==0;
    CloseDesktop(d);return ok;
}
static bool idle() {
    LASTINPUTINFO info{sizeof(info)};
    return GetLastInputInfo(&info) && DWORD(GetTickCount()-info.dwTime)>300000;
}
struct Target {
    HWND hwnd{}; DWORD pid{}; unsigned long long created{}; std::wstring process;
    bool operator==(const Target& r)const {
        return hwnd==r.hwnd && pid==r.pid && created==r.created && process==r.process;
    }
};
static Target target(const std::set<std::wstring>& allow) {
    if(!interactive())return {};
    HWND hwnd=GetForegroundWindow(); DWORD pid=0;
    if(!hwnd || !IsWindowVisible(hwnd) || IsIconic(hwnd))return {};
    GetWindowThreadProcessId(hwnd,&pid); if(!pid || pid==GetCurrentProcessId())return {};
    DWORD session=0, own=0;
    if(!ProcessIdToSessionId(pid,&session)||!ProcessIdToSessionId(GetCurrentProcessId(),&own)||session!=own)return {};
    HANDLE p=OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION,FALSE,pid);if(!p)return {};
    wchar_t buf[32768]; DWORD len=32768; FILETIME c,e,k,u;
    bool ok=QueryFullProcessImageNameW(p,0,buf,&len)&&GetProcessTimes(p,&c,&e,&k,&u);
    CloseHandle(p);if(!ok)return {};
    std::wstring name(buf,len);auto slash=name.find_last_of(L"\\/");
    name=lower(name.substr(slash==std::wstring::npos?0:slash+1));
    static const std::set<std::wstring> denied={L"chrome.exe",L"msedge.exe",L"firefox.exe",L"brave.exe",L"opera.exe",L"vivaldi.exe",
        L"roots-surface.exe",L"wds.roots.host.exe",L"wds.roots.experience.exe",L"dotnet.exe",L"windowsterminal.exe",L"conhost.exe",L"openconsole.exe",
        L"credentialuibroker.exe",L"logonui.exe",L"keepass.exe",L"keepassxc.exe",L"1password.exe",L"bitwarden.exe"};
    if(!allow.contains(name)||denied.contains(name))return {};
    ULARGE_INTEGER time{};time.LowPart=c.dwLowDateTime;time.HighPart=c.dwHighDateTime;
    if(GetForegroundWindow()!=hwnd)return {};
    return {hwnd,pid,time.QuadPart,std::move(name)};
}
// UIA reads display names of static text only, never value/text patterns,
// passwords, edit or document subtrees. A failure fences the OCR fallback.
static bool walk(IUIAutomationTreeWalker* walker,IUIAutomationElement* parent,const RECT& window,
        std::vector<SurfaceText>& rows,int& visits,int depth,bool& sensitive) {
    if(depth>18||visits>=384){sensitive=true;return false;}
    ComPtr<IUIAutomationElement> node;
    if(FAILED(walker->GetFirstChildElement(parent,&node))){sensitive=true;return false;}
    while(node && visits<384) {
        ++visits;
        BOOL hidden=TRUE,password=TRUE; CONTROLTYPEID kind=0;
        if(FAILED(node->get_CurrentIsOffscreen(&hidden))||FAILED(node->get_CurrentIsPassword(&password))||
           FAILED(node->get_CurrentControlType(&kind))){sensitive=true;return false;}
        if(password||kind==UIA_EditControlTypeId||kind==UIA_DocumentControlTypeId) sensitive=true;
        else if(!hidden) {
            if(kind==UIA_TextControlTypeId && rows.size()<64) {
                RECT bounds{},clipped{}; BSTR name=nullptr;
                if(SUCCEEDED(node->get_CurrentBoundingRectangle(&bounds))&&IntersectRect(&clipped,&bounds,&window)&&
                   SUCCEEDED(node->get_CurrentName(&name))&&name) {
                    std::wstring text(name,SysStringLen(name)); SysFreeString(name);
                    if(!text.empty()&&text.size()<=2000&&text.find(L'\0')==std::wstring::npos&&
                       std::any_of(text.begin(),text.end(),[](wchar_t ch){return !iswspace(ch);}))
                        rows.push_back({std::move(text),clipped});
                } else if(name) SysFreeString(name);
            }
            if(!walk(walker,node.Get(),window,rows,visits,depth+1,sensitive))return false;
        }
        ComPtr<IUIAutomationElement> next;
        if(FAILED(walker->GetNextSiblingElement(node.Get(),&next))){sensitive=true;return false;}
        node=std::move(next);
    }
    if(node){sensitive=true;return false;}return true;
}
static bool uia(IUIAutomation* automation,HWND hwnd,std::vector<SurfaceText>& rows,bool& sensitive) {
    if(!automation){sensitive=true;return false;}
    RECT rect{}; if(!GetWindowRect(hwnd,&rect)){sensitive=true;return false;}
    ComPtr<IUIAutomationElement> root; ComPtr<IUIAutomationTreeWalker> walker;
    if(FAILED(automation->ElementFromHandle(hwnd,&root))||!root||
       FAILED(automation->get_ControlViewWalker(&walker))||!walker){sensitive=true;return false;}
    BOOL pw=TRUE;
    if(FAILED(root->get_CurrentIsPassword(&pw))||pw){sensitive=true;return false;}
    int visits=0;return walk(walker.Get(),root.Get(),rect,rows,visits,0,sensitive);
}
static void CALLBACK foreground(HWINEVENTHOOK,DWORD,HWND,LONG,LONG,DWORD,DWORD) {
    PostThreadMessageW(ownerThread,Tick,0,0);
}
static LRESULT CALLBACK signalProc(HWND hwnd,UINT message,WPARAM w,LPARAM l) {
    if(message==WM_WTSSESSION_CHANGE) {
        if(w==WTS_SESSION_LOCK || w==WTS_CONSOLE_DISCONNECT || w==WTS_REMOTE_DISCONNECT) locked=true;
        if(w==WTS_SESSION_UNLOCK || w==WTS_CONSOLE_CONNECT || w==WTS_REMOTE_CONNECT) locked=false;
        PostThreadMessageW(ownerThread,Tick,0,0);return 0;
    }
    if(message==WM_TIMER){PostThreadMessageW(ownerThread,Tick,0,0);return 0;}
    return DefWindowProcW(hwnd,message,w,l);
}
// Pipe schema: kind, session, pid, creation, hwnd, UTC start/end, monotonic us
// start/end, process UTF8-hex, bounds (source coordinate space), text UTF8-hex.
static void emit(const char* kind,unsigned long long session,const Target& t,long long startUtc,long long endUtc,
        Clock::time_point start,Clock::time_point end,const RECT& b,const std::wstring& content) {
    const auto text=hex(content),process=hex(t.process);
    if(process.empty()||text.empty()||std::none_of(content.begin(),content.end(),[](wchar_t ch){return !iswspace(ch);}))return;
    std::cout<<kind<<'\t'<<session<<'\t'<<t.pid<<'\t'<<t.created<<'\t'<<reinterpret_cast<uintptr_t>(t.hwnd)
        <<'\t'<<startUtc<<'\t'<<endUtc<<'\t'<<mono(start)<<'\t'<<mono(end)<<'\t'<<process
        <<'\t'<<b.left<<','<<b.top<<','<<b.right<<','<<b.bottom<<'\t'<<text<<'\n'<<std::flush;
}
int wmain(int argc,wchar_t** argv) {
    std::set<std::wstring> allow;bool useOcr=false;
    for(int i=1;i<argc;++i){std::wstring a=lower(argv[i]);if(a==L"--ocr")useOcr=true;
        else if(a.ends_with(L".exe")&&a.find_first_of(L"\\/:")==std::wstring::npos)allow.insert(a);
        else return 2;}
    if(allow.empty())return 2;
    // Refuse a direct terminal run; the approved receiver owns all textual output.
    DWORD consoleMode=0;if(GetConsoleMode(GetStdHandle(STD_OUTPUT_HANDLE),&consoleMode))return 2;
    if(GetFileType(GetStdHandle(STD_OUTPUT_HANDLE))!=FILE_TYPE_PIPE ||
       GetFileType(GetStdHandle(STD_INPUT_HANDLE))!=FILE_TYPE_PIPE)return 2;
    if(FAILED(CoInitializeEx(nullptr,COINIT_MULTITHREADED)))return 3;
#ifdef ROOTS_SURFACE_OCR
    if(useOcr && FAILED(RoInitialize(RO_INIT_MULTITHREADED))) { CoUninitialize(); return 4; }
#endif
    ComPtr<IUIAutomation> automation;
    CoCreateInstance(CLSID_CUIAutomation,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&automation));
    if(!automation){CoUninitialize();return 3;}
    ComPtr<IUIAutomation2> automation2;
    if(SUCCEEDED(automation.As(&automation2))){automation2->put_ConnectionTimeout(500);automation2->put_TransactionTimeout(500);}
#ifdef ROOTS_SURFACE_OCR
    std::unique_ptr<OcrLane> ocr;
    if(useOcr){try{ocr=std::make_unique<OcrLane>();}catch(...){return 4;}}
#else
    if(useOcr)return 4;
#endif
    ownerThread=GetCurrentThreadId(); MSG msg{};
    PeekMessageW(&msg,nullptr,WM_USER,WM_USER,PM_NOREMOVE);
    WNDCLASSW wc{};wc.lpfnWndProc=signalProc;wc.hInstance=GetModuleHandleW(nullptr);wc.lpszClassName=L"RootsSurfaceSignals";
    if(!RegisterClassW(&wc))return 3;
    signalWindow=CreateWindowExW(0,wc.lpszClassName,L"",WS_POPUP,0,0,0,0,nullptr,nullptr,wc.hInstance,nullptr);
    if(!signalWindow||!WTSRegisterSessionNotification(signalWindow,NOTIFY_FOR_THIS_SESSION))return 3;
    auto hook=SetWinEventHook(EVENT_SYSTEM_FOREGROUND,EVENT_SYSTEM_FOREGROUND,nullptr,foreground,0,0,WINEVENT_OUTOFCONTEXT|WINEVENT_SKIPOWNPROCESS);
    if(!hook||!SetTimer(signalWindow,1,1000,nullptr))return 3;
    // One byte or EOF from the supervising receiver stops acquisition.
    std::thread([]{char c;DWORD n=0;ReadFile(GetStdHandle(STD_INPUT_HANDLE),&c,1,&n,nullptr);PostThreadMessageW(ownerThread,Stop,0,0);}).detach();
    Target current;unsigned long long session=0;
    auto previous=Clock::now(), spanStart=previous;long long startUtc=utc(),lastUtc=startUtc;
    bool wasIdle=false;std::vector<SurfaceText> seen;std::string seenKind;
    auto close=[&](Clock::time_point end,long long endUtc){
        if(current.hwnd&&end>spanStart&&endUtc>=startUtc)
            emit(wasIdle?"idle":"focused",session,current,startUtc,endUtc,spanStart,end,{},L"Foreground activity");
        spanStart=end;startUtc=endUtc;
    };
    PostThreadMessageW(ownerThread,Tick,0,0);
    while(GetMessageW(&msg,nullptr,0,0)>0) {
        if(msg.message==Stop){close(previous,lastUtc);break;}
        if(msg.message!=Tick){TranslateMessage(&msg);DispatchMessageW(&msg);continue;}
        // Coalesce timer / foreground notifications; no background capture queue.
        MSG redundant{};while(PeekMessageW(&redundant,nullptr,Tick,Tick,PM_REMOVE)){}
        auto now=Clock::now();auto wall=utc();auto next=target(allow);bool isIdle=idle();
        const auto elapsed=std::chrono::duration_cast<std::chrono::milliseconds>(now-previous).count();
        const bool gap=elapsed>3000||wall<lastUtc||std::llabs((wall-lastUtc)-elapsed)>3000;
        if(gap||!(next==current)||isIdle!=wasIdle) {
            close(previous,lastUtc);current=next;++session;spanStart=now;startUtc=wall;seen.clear();seenKind.clear();wasIdle=isIdle;
#ifdef ROOTS_SURFACE_OCR
            if(ocr)ocr->reset();
#endif
        }
        if(current.hwnd && now-spanStart>=std::chrono::seconds(5))close(now,wall);
        previous=now;lastUtc=wall;
        if(!current.hwnd || isIdle)continue;
        std::vector<SurfaceText> rows;bool sensitive=false;
        bool valid=uia(automation.Get(),current.hwnd,rows,sensitive);const char* kind="uia";
#ifdef ROOTS_SURFACE_OCR
        if(ocr && valid && !sensitive && rows.empty()){
            if(!ocr->read(current.hwnd,rows))continue;
            kind="ocr";
        } else if(ocr)ocr->reset();
#endif
        if(!valid||sensitive||!(target(allow)==current)){seen.clear();seenKind.clear();continue;}
        if(seenKind!=kind){seen.clear();seenKind=kind;}
        for(const auto& row:rows){
            // Compare the previous visible snapshot, not a global text history.
            // Reappearance after a disappearance receives a fresh occurrence.
            if(std::find(seen.begin(),seen.end(),row)==seen.end())
                emit(kind,session,current,wall,wall,now,now,row.bounds,row.text);
        }
        seen=std::move(rows);
    }
#ifdef ROOTS_SURFACE_OCR
    ocr.reset();
#endif
    KillTimer(signalWindow,1);UnhookWinEvent(hook);
    WTSUnRegisterSessionNotification(signalWindow);DestroyWindow(signalWindow);
    automation2.Reset();automation.Reset();
#ifdef ROOTS_SURFACE_OCR
    if(useOcr)RoUninitialize();
#endif
    CoUninitialize();return 0;
}
