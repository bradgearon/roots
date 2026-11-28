// Copyright (c) 2026 We Develop Software LLC. All rights reserved.
#include <windows.h>
#include <objbase.h>
#include <ole2.h>
#include <oleauto.h>
#include <wtsapi32.h>
#include <UIAutomation.h>
#include <wrl/client.h>
#include <algorithm>
#include <chrono>
#include <cwctype>
#include <cwchar>
#include <cmath>
#include <limits>
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
static Target target(const std::set<std::wstring>& excluded) {
    if(!interactive())return {};
    HWND hwnd=GetForegroundWindow(); DWORD pid=0;
    if(!hwnd || !IsWindowVisible(hwnd) || IsIconic(hwnd))return {};
    GetWindowThreadProcessId(hwnd,&pid); if(!pid || pid==GetCurrentProcessId())return {};
    DWORD session=0, own=0;
    if(!ProcessIdToSessionId(pid,&session)||!ProcessIdToSessionId(GetCurrentProcessId(),&own)||session!=own)return {};
    HANDLE p=OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION,FALSE,pid);if(!p)return {};
    wchar_t buf[32768]; DWORD len=32768; FILETIME c{},e{},k{},u{};
    bool ok=QueryFullProcessImageNameW(p,0,buf,&len)&&GetProcessTimes(p,&c,&e,&k,&u);
    CloseHandle(p);if(!ok)return {};
    std::wstring name(buf,len);auto slash=name.find_last_of(L"\\/");
    name=lower(name.substr(slash==std::wstring::npos?0:slash+1));
    // No built-in process-name exclusions. Only explicit user exclusions apply.
    if(name.size()<=4||name.size()>128||!name.ends_with(L".exe")||excluded.contains(name))return {};
    DWORD affinity=0;
    // This query can fail on ordinary non-layered windows; WGC still honors OS capture protection.
    if(GetWindowDisplayAffinity(hwnd,&affinity)&&affinity!=WDA_NONE)return {};
    ULARGE_INTEGER time{};time.LowPart=c.dwLowDateTime;time.HighPart=c.dwHighDateTime;
    if(GetForegroundWindow()!=hwnd)return {};
    return {hwnd,pid,time.QuadPart,std::move(name)};
}
// Capture visible, non-editable accessibility text without reading values,
// raw keystrokes or hidden nodes. Unsafe subtrees are excluded individually;
// incomplete inspection blocks OCR, not safe text already acquired.
static std::wstring nameOf(IUIAutomationElement* element) {
    BSTR value=nullptr;
    if(FAILED(element->get_CurrentName(&value))||!value){if(value)SysFreeString(value);return {};}
    std::wstring result(value,SysStringLen(value));SysFreeString(value);return result;
}
static bool ownViewName(const std::wstring& name) {
    // Prevent our rendered knowledge from becoming fresh captured knowledge.
    // This is a presentation identity, not a process-name exclusion.
    const std::wstring title=L"Roots \u00b7 Tree";
    return name==title||name.starts_with(title+L" - ");
}
static bool usableText(const std::wstring& value) {
    return !value.empty()&&value.size()<=2000&&value.find(L'\0')==std::wstring::npos&&
        std::any_of(value.begin(),value.end(),[](wchar_t ch){return !iswspace(ch);});
}
static std::wstring textKey(const std::wstring& value) {
    std::wstring out;bool space=false;
    for(auto ch:value){
        if(iswspace(ch)){space=!out.empty();continue;}
        if(space){out+=L' ';space=false;}
        out+=static_cast<wchar_t>(towlower(ch));
    }
    return out;
}
static bool sameTextAt(const SurfaceText& a,const SurfaceText& b) {
    if(textKey(a.text)!=textKey(b.text))return false;
    RECT overlap{};
    if(!IntersectRect(&overlap,&a.bounds,&b.bounds))return false;
    auto area=[](const RECT& r){return static_cast<double>(r.right-r.left)*(r.bottom-r.top);};
    const auto small=std::min(area(a.bounds),area(b.bounds));
    return small>0&&area(overlap)>=small*0.6;
}
static void addText(std::vector<SurfaceText>& rows,std::wstring value,const RECT& bounds) {
    if(rows.size()>=256||!usableText(value))return;
    SurfaceText row{std::move(value),bounds};
    if(std::none_of(rows.begin(),rows.end(),[&](const auto& prior){return sameTextAt(prior,row);}))
        rows.push_back(std::move(row));
}
static bool editableDocument(IUIAutomationElement* element) {
    // Never read the value. A writable ValuePattern or TextEditPattern marks
    // the document as an editable subtree, not ordinary browser prose.
    ComPtr<IUnknown> raw;
    if(SUCCEEDED(element->GetCurrentPattern(UIA_ValuePatternId,&raw))&&raw){
        ComPtr<IUIAutomationValuePattern> value;
        BOOL readOnly=FALSE;
        if(FAILED(raw.As(&value))||FAILED(value->get_CurrentIsReadOnly(&readOnly))||!readOnly)return true;
    }
    raw.Reset();
    return SUCCEEDED(element->GetCurrentPattern(UIA_TextEditPatternId,&raw))&&raw;
}
// Some read-only consoles and documents expose their text as a TextPattern
// rather than child labels. Read only the provider's visible, read-only
// ranges; never DocumentRange, selections, or editable values.
static bool visibleRangeBounds(IUIAutomationTextRange* range,const RECT& window,RECT& result) {
    SAFEARRAY* rectangles=nullptr;
    if(FAILED(range->GetBoundingRectangles(&rectangles))||!rectangles)return false;
    LONG low=0,high=-1;bool ok=false;
    if(SafeArrayGetDim(rectangles)==1&&SafeArrayGetElemsize(rectangles)==sizeof(double)&&
       SUCCEEDED(SafeArrayGetLBound(rectangles,1,&low))&&SUCCEEDED(SafeArrayGetUBound(rectangles,1,&high))){
        const auto count=static_cast<long long>(high)-low+1;
        double* values=nullptr;
        if(count>0&&count<=512&&count%4==0&&SUCCEEDED(SafeArrayAccessData(rectangles,reinterpret_cast<void**>(&values)))){
            ok=true;RECT combined{};bool first=true;
            for(long long i=0;i<count;i+=4){
                const double x=values[i],y=values[i+1],w=values[i+2],h=values[i+3];
                if(!std::isfinite(x)||!std::isfinite(y)||!std::isfinite(w)||!std::isfinite(h)||
                   w<=0||h<=0||x<window.left-1.0||y<window.top-1.0||
                   x+w>window.right+1.0||y+h>window.bottom+1.0){ok=false;break;}
                RECT bounds{static_cast<LONG>(std::max<double>(window.left,std::floor(x))),
                    static_cast<LONG>(std::max<double>(window.top,std::floor(y))),
                    static_cast<LONG>(std::min<double>(window.right,std::ceil(x+w))),
                    static_cast<LONG>(std::min<double>(window.bottom,std::ceil(y+h)))};
                if(IsRectEmpty(&bounds)){ok=false;break;}
                if(first){combined=bounds;first=false;}else UnionRect(&combined,&combined,&bounds);
            }
            SafeArrayUnaccessData(rectangles);
            if(ok&&!first)result=combined;else ok=false;
        }
    }
    SafeArrayDestroy(rectangles);
    return ok;
}
static void readVisibleRanges(IUIAutomationElement* element,const RECT& window,
        std::vector<SurfaceText>& rows,Clock::time_point deadline) {
    if(Clock::now()>=deadline)return;
    ComPtr<IUnknown> raw;
    if(FAILED(element->GetCurrentPattern(UIA_TextPatternId,&raw))||!raw)return;
    ComPtr<IUIAutomationTextPattern> pattern;
    if(FAILED(raw.As(&pattern)))return;
    ComPtr<IUIAutomationTextRangeArray> ranges;
    int length=0;
    if(FAILED(pattern->GetVisibleRanges(&ranges))||!ranges||FAILED(ranges->get_Length(&length)))return;
    for(int i=0;i<std::min(length,32)&&Clock::now()<deadline&&rows.size()<256;++i){
        ComPtr<IUIAutomationTextRange> range;
        if(FAILED(ranges->GetElement(i,&range))||!range)continue;
        VARIANT attr;VariantInit(&attr);
        const bool readOnly=SUCCEEDED(range->GetAttributeValue(UIA_IsReadOnlyAttributeId,&attr))&&
            attr.vt==VT_BOOL&&attr.boolVal==VARIANT_TRUE;
        VariantClear(&attr);
        if(!readOnly)continue;
        RECT bounds{};
        if(!visibleRangeBounds(range.Get(),window,bounds))continue;
        BSTR value=nullptr;
        if(SUCCEEDED(range->GetText(2000,&value))&&value)addText(rows,std::wstring(value,SysStringLen(value)),bounds);
        if(value)SysFreeString(value);
    }
}
static bool labelKind(CONTROLTYPEID kind) {
    return kind==UIA_TextControlTypeId||kind==UIA_HyperlinkControlTypeId||
        kind==UIA_ButtonControlTypeId||kind==UIA_MenuItemControlTypeId||
        kind==UIA_TabItemControlTypeId||kind==UIA_CheckBoxControlTypeId||
        kind==UIA_RadioButtonControlTypeId||kind==UIA_HeaderItemControlTypeId;
}
struct UiaScan {
    std::vector<SurfaceText> rows;
    int visits=0;
    bool complete=true,sensitive=false,ownView=false;
    Clock::time_point deadline=Clock::now()+std::chrono::milliseconds(850);
    bool within(int depth) const {
        return depth<=28&&visits<1600&&Clock::now()<deadline;
    }
};
static void walk(IUIAutomationTreeWalker* walker,IUIAutomationElement* parent,
        const RECT& window,UiaScan& scan,int depth) {
    if(!scan.within(depth)){scan.complete=false;return;}
    ComPtr<IUIAutomationElement> node;
    if(FAILED(walker->GetFirstChildElement(parent,&node))){scan.complete=false;return;}
    while(node&&!scan.ownView){
        if(!scan.within(depth)){scan.complete=false;return;}
        ++scan.visits;
        BOOL hidden=TRUE,password=TRUE;CONTROLTYPEID kind=0;
        if(FAILED(node->get_CurrentIsOffscreen(&hidden))||FAILED(node->get_CurrentIsPassword(&password))||
           FAILED(node->get_CurrentControlType(&kind)))scan.complete=false;
        else if(!hidden){
            if(password||kind==UIA_EditControlTypeId||
               (kind==UIA_DocumentControlTypeId&&editableDocument(node.Get())))scan.sensitive=true;
            else {
                if(kind==UIA_DocumentControlTypeId&&ownViewName(nameOf(node.Get()))) {
                    scan.ownView=true;scan.rows.clear();return;
                }
                if(labelKind(kind)&&scan.rows.size()<256){
                    RECT bounds{},clipped{};
                    if(SUCCEEDED(node->get_CurrentBoundingRectangle(&bounds))&&IntersectRect(&clipped,&bounds,&window))
                        addText(scan.rows,nameOf(node.Get()),clipped);
                }
                const auto before=scan.rows.size();
                walk(walker,node.Get(),window,scan,depth+1);
                if(kind==UIA_DocumentControlTypeId&&scan.rows.size()==before&&scan.complete&&!scan.sensitive&&!scan.ownView)
                    readVisibleRanges(node.Get(),window,scan.rows,scan.deadline);
            }
        }
        if(scan.ownView)return;
        ComPtr<IUIAutomationElement> next;
        if(FAILED(walker->GetNextSiblingElement(node.Get(),&next))){scan.complete=false;return;}
        node=std::move(next);
    }
}
static UiaScan uia(IUIAutomation* automation,HWND hwnd) {
    UiaScan scan;
    RECT rect{};
    ComPtr<IUIAutomationElement> root;ComPtr<IUIAutomationTreeWalker> walker;
    if(!automation||!GetWindowRect(hwnd,&rect)||
       FAILED(automation->ElementFromHandle(hwnd,&root))||!root||
       FAILED(automation->get_ControlViewWalker(&walker))||!walker){
        scan.complete=false;return scan;
    }
    BOOL pw=TRUE;CONTROLTYPEID kind=0;
    if(FAILED(root->get_CurrentIsPassword(&pw))||FAILED(root->get_CurrentControlType(&kind))||
       pw||kind==UIA_EditControlTypeId||(kind==UIA_DocumentControlTypeId&&editableDocument(root.Get()))){
        scan.sensitive=true;scan.complete=false;return scan;
    }
    if(ownViewName(nameOf(root.Get()))){scan.ownView=true;return scan;}
    walk(walker.Get(),root.Get(),rect,scan,0);
    if(scan.rows.empty()&&scan.complete&&!scan.sensitive&&!scan.ownView)
        readVisibleRanges(root.Get(),rect,scan.rows,scan.deadline);
    return scan;
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
    std::set<std::wstring> excluded;
#ifdef ROOTS_SURFACE_OCR
    bool useOcr=true;
#else
    bool useOcr=false;
#endif
    for(int i=1;i<argc;++i){
        std::wstring a=lower(argv[i]);
        if(a==L"--ocr")useOcr=true;
        else if(a==L"--no-ocr")useOcr=false;
        else if(a==L"--exclude" && i+1<argc){
            std::wstring name=lower(argv[++i]);
            if(name.empty() || name.size()>128 || !name.ends_with(L".exe") ||
               name.find_first_of(L"\\/:*?")!=std::wstring::npos) return 2;
            excluded.insert(std::move(name));
        } else return 2;
    }
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
    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
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
    bool wasIdle=false;std::vector<SurfaceText> seenUia,seenOcr;
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
        auto now=Clock::now();auto wall=utc();auto next=target(excluded);bool isIdle=idle();
        const auto elapsed=std::chrono::duration_cast<std::chrono::milliseconds>(now-previous).count();
        const bool gap=elapsed>3000||wall<lastUtc||std::llabs((wall-lastUtc)-elapsed)>3000;
        if(gap||!(next==current)||isIdle!=wasIdle) {
            close(previous,lastUtc);current=next;++session;spanStart=now;startUtc=wall;seenUia.clear();seenOcr.clear();wasIdle=isIdle;
#ifdef ROOTS_SURFACE_OCR
            if(ocr)ocr->reset();
#endif
        }
        if(current.hwnd && now-spanStart>=std::chrono::seconds(5))close(now,wall);
        previous=now;lastUtc=wall;
        // Idle is an activity label, not a reason to stop observing an unlocked screen.
        if(!current.hwnd)continue;
        auto scan=uia(automation.Get(),current.hwnd);
        std::vector<SurfaceText> optical;RECT captureDesktop{};bool opticalReady=false;
        // Structured text and OCR are complementary, not mutually exclusive.
        // Do not OCR windows containing visible sensitive controls or where
        // inspection could not determine whether such controls are present.
#ifdef ROOTS_SURFACE_OCR
        if(ocr&&scan.complete&&!scan.sensitive&&!scan.ownView)
            opticalReady=ocr->read(current.hwnd,optical,captureDesktop);
        else if(ocr){ocr->reset();seenOcr.clear();}
#endif
        if(scan.ownView||!(target(excluded)==current)){
            seenUia.clear();seenOcr.clear();
#ifdef ROOTS_SURFACE_OCR
            if(ocr)ocr->reset();
#endif
            continue;
        }
        for(const auto& row:scan.rows){
            if(std::none_of(seenUia.begin(),seenUia.end(),[&](const auto& prior){return sameTextAt(prior,row);}))
                emit("uia",session,current,wall,wall,now,now,row.bounds,row.text);
        }
        if(opticalReady){
            std::vector<SurfaceText> unique;
            for(const auto& row:optical){
                SurfaceText mapped=row;
                const bool known=captureDesktop.right>captureDesktop.left&&captureDesktop.bottom>captureDesktop.top;
                if(known)OffsetRect(&mapped.bounds,captureDesktop.left,captureDesktop.top);
                if(known&&std::any_of(scan.rows.begin(),scan.rows.end(),[&](const auto& semantic){return sameTextAt(semantic,mapped);}))continue;
                if(std::none_of(seenOcr.begin(),seenOcr.end(),[&](const auto& prior){return sameTextAt(prior,row);}))
                    emit("ocr",session,current,wall,wall,now,now,row.bounds,row.text);
                unique.push_back(row);
            }
            seenOcr=std::move(unique);
        }
        if(scan.complete)seenUia=std::move(scan.rows);
        else {
            // Partial scans cannot prove a disappearance. Keep bounded
            // recent visible occurrences to prevent repeated partial reads.
            for(auto& row:scan.rows)
                if(std::none_of(seenUia.begin(),seenUia.end(),[&](const auto& prior){return sameTextAt(prior,row);}))
                    seenUia.push_back(std::move(row));
            if(seenUia.size()>512)seenUia.erase(seenUia.begin(),seenUia.begin()+(seenUia.size()-512));
        }
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
