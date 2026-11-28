#include "ocr_pixels.h"
#include <iostream>
#include <stdexcept>
#include <vector>
using namespace roots::surface;
static void require(bool ok){if(!ok)throw std::runtime_error("Surface pixel fixture failed");}
int main(){
    // Synthetic frame only. Redact a single editable rectangle without
    // blanking the text elsewhere on the screen.
    std::vector<uint8_t> frame(12*8,42);
    redact(frame,12,8,{3,1,8,4});
    for(int y=0;y<8;++y)for(int x=0;x<12;++x)
        require(frame[y*12+x]==((x>=3&&x<8&&y>=1&&y<4)?255:42));
    redact(frame,12,8,{-100,-100,2,2});
    require(frame[0]==255&&frame[13]==255&&frame[2]==42);
    auto before=frame;
    redact(frame,12,8,{100,100,200,200});
    require(frame==before);
    for(int extent:{200,2200,2201,3840,5120,7680}){
        const int tile=2200;
        auto starts=tileStarts(extent,tile);
        require(starts.front()==0);
        require(starts.back()==std::max(0,extent-tile));
        std::vector<bool> covered(extent);
        for(size_t i=0;i<starts.size();++i){
            if(i)require(starts[i]-starts[i-1]<=tile-96&&starts[i]>starts[i-1]);
            for(int p=starts[i];p<std::min(extent,starts[i]+tile);++p)covered[p]=true;
        }
        for(bool yes:covered)require(yes);
    }
    std::cout<<"Surface pixel checks passed\n";
}
