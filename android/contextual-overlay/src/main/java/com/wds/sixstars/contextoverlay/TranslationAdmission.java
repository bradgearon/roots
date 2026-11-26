package com.wds.sixstars.contextoverlay;

import java.util.HashSet;
import java.util.Set;

/** Main-thread request ownership; visibility never releases a provider slot. */
final class TranslationAdmission {
    private final Set<String> running=new HashSet<>();
    boolean hasRoom(int limit){return running.size()<limit;}
    boolean contains(String key){return running.contains(key);}
    boolean tryAcquire(String key,int limit){return hasRoom(limit)&&running.add(key);}
    boolean release(String key){return running.remove(key);}
}
