package com.wds.sixstars.contextoverlay;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
public final class AxonPlannerChecks {
    static void need(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static boolean near(double a,double b){return Math.abs(a-b)<1e-9;}
    public static void main(String[] args){
        var shares=AxonPlanner.allocate(List.of(new AxonPlanner.Need("music",1,0.003),new AxonPlanner.Need("science",1,0.001)));
        need(near(shares.get(0).fraction(),.625)&&near(shares.get(1).fraction(),.375),"normalize mapped flow independently");
        var cold=AxonPlanner.allocate(List.of(new AxonPlanner.Need("music",3,0),new AxonPlanner.Need("science",1,0)));
        need(near(cold.get(0).fraction(),.75),"knowledge-only cold start");
        List<AxonPlanner.Offer> offers=new ArrayList<>();
        for(int i=0;i<40;i++){
            offers.add(new AxonPlanner.Offer("m"+i,"music",2,0,true));
            offers.add(new AxonPlanner.Offer("s"+i,"science",2,0,true));
        }
        offers.add(new AxonPlanner.Offer("m-rare","music",1,0,true));
        offers.add(new AxonPlanner.Offer("m-demand","music",1,3,true));
        offers.add(new AxonPlanner.Offer("excluded","not subscribed",1,100,true));
        offers.add(new AxonPlanner.Offer("m-demand","music",1,3,true));
        List<String> order=AxonPlanner.order(offers,shares,32);
        need(order.size()==32&&new HashSet<>(order).size()==32,"bounded unique requests");
        need(!order.contains("excluded"),"subscription filtering");
        need(order.get(0).equals("m-demand")&&order.indexOf("m-rare")<order.indexOf("m0"),"rarest then demand inside lane");
        long music=order.stream().filter(id->id.startsWith("m")).count();
        need(music==20,"62.5 percent receives 20 of 32");
        var fallback=AxonPlanner.shares(List.of(new AxonPlanner.Lane("*",1),new AxonPlanner.Lane("music",1),new AxonPlanner.Lane("music theory",1)));
        need(AxonPlanner.lane("music theory and harmony",fallback).equals("music theory"),"most specific lane");
        need(AxonPlanner.lane("other topic",fallback).equals("*"),"explicit fallback");
        need(AxonPlanner.order(offers,shares,0).isEmpty(),"zero budget");
        System.out.println("Axon allocation contract checks passed.");
    }
}
