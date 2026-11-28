package com.wds.sixstars.contextoverlay;

/** Public nearby discovery does not require a private LAN subscription.
 * Explicit invitation input always stays on the private LAN path. */
final class EncounterReceivePolicy {
    private EncounterReceivePolicy(){}
    static boolean nearby(boolean noDraft,boolean lanSelected,int nearbyBindings,boolean nearbyEnabled){
        return noDraft&&(nearbyBindings>0||(!lanSelected&&nearbyEnabled));
    }
}
