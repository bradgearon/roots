#pragma once
#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <vector>

namespace roots::surface {
// Coordinates are physical capture pixels, not logical desktop units.
struct PixelRect { int left,top,right,bottom; };
inline void redact(std::vector<uint8_t>& pixels,int width,int height,PixelRect r){
    if(width<=0||height<=0||static_cast<uint64_t>(width)*height!=pixels.size())
        throw std::invalid_argument("Invalid grayscale frame");
    const int left=std::clamp(r.left,0,width),right=std::clamp(r.right,0,width);
    const int top=std::clamp(r.top,0,height),bottom=std::clamp(r.bottom,0,height);
    if(left>=right||top>=bottom)return;
    for(int y=top;y<bottom;++y)
        std::fill_n(pixels.data()+static_cast<size_t>(y)*width+left,right-left,uint8_t{255});
}
inline std::vector<int> tileStarts(int extent,int tile,int overlap=96){
    if(extent<=0||tile<=0||overlap<0||overlap>=tile)throw std::invalid_argument("Invalid tile geometry");
    if(extent<=tile)return {0};
    const int step=tile-overlap;
    // Evenly distribute tiles instead of creating a near-duplicate final
    // tile for an extent just slightly bigger than a single tile.
    const int span=extent-tile;
    const int gaps=(span+step-1)/step;
    std::vector<int> result;
    for(int i=0;i<=gaps;++i)
        result.push_back(static_cast<int>(static_cast<int64_t>(span)*i/gaps));
    return result;
}
}
