package com.wds.sixstars.contextoverlay;

import android.content.Context;

/** General language model tiers. Translation and function proposals are separate. */
enum IntelligenceModel {
    TINY("Gemma 3 270M · Q8 (292 MB)","gemma-3-270m-it-GGUF","795e608","gemma-3-270m-it-Q8_0.gguf","0ef57d2c838458a1952664260dcba38e5bdda37494f3af732f06e4add24068e3",350_000_000L),
    BALANCED("Qwen3.5 0.8B · Q4 (563 MB)","Qwen3.5-0.8B-GGUF","9447f74","Qwen3.5-0.8B-Q4_0.gguf","57d1997790d1744fba5b40a7317df71ea5e2acee28c47e78f0cce39c0703f8cf",650_000_000L),
    ALTERNATE("Gemma 3 1B · Q4 (806 MB)","gemma-3-1b-it-GGUF","61333ba","gemma-3-1b-it-Q4_K_M.gguf","8ccc5cd1f1b3602548715ae25a66ed73fd5dc68a210412eea643eb20eb75a135",900_000_000L);
    final String label,repository,revision,file,sha;
    final long maxBytes;
    IntelligenceModel(String label,String repository,String revision,String file,String sha,long maxBytes){
        this.label=label;this.repository=repository;this.revision=revision;this.file=file;this.sha=sha;this.maxBytes=maxBytes;
    }
    String url(){return "https://huggingface.co/ggml-org/"+repository+"/resolve/"+revision+"/"+file;}
    String terms(){return this==BALANCED?"https://huggingface.co/Qwen/Qwen3.5-0.8B":"https://ai.google.dev/gemma/terms";}
    String prompt(String user){
        if(this==BALANCED)return "<|im_start|>user\n"+user+"<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n";
        return "<start_of_turn>user\n"+user+"<end_of_turn>\n<start_of_turn>model\n";
    }
    static IntelligenceModel selected(Context c){
        try{return valueOf(c.getSharedPreferences("roots_intelligence",0).getString("model",BALANCED.name()));}
        catch(IllegalArgumentException e){return BALANCED;}
    }
    void select(Context c){c.getSharedPreferences("roots_intelligence",0).edit().putString("model",name()).apply();}
    static String[] labels(){return java.util.Arrays.stream(values()).map(m->m.label).toArray(String[]::new);}
}
