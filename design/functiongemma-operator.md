# FunctionGemma as a micro-cognitive operator

Status: local proposal prototype; semantic accuracy, model inference and Android deployment not yet verified. Copyright (c) 2026 We Develop Software LLC. All rights reserved.

FunctionGemma (Gemma 3 270M architecture, specialized for tool selection) is not a knowledge authority or a substitute for the writing process. It translates an intention into a bounded operation. In Roots, Lily/tendrils sense meaning and develop candidate relationships; Tree storage supplies provenance, retained knowledge and evidence. These are design roles, not claims about biological equivalence.

## Semantic displacement

Working operational hypothesis: a property, action, relation or state is moved between semantic domains and evaluated in the receiving context. Example: "Your eyes disturb the sea when it feels too rough." A candidate might examine turbulence and calm as properties of water and emotional expression; this is an interpretation to compare with alternatives, not a fact or a complete formal definition. ChristEye's broader displacement method and judgments remain outside the model's authority.

Initial interface:
- search_knowledge(query): request retrieval, not hallucinated factual response.
- compare_meanings(left,right): propose two concepts for comparison.
- propose_displacement(source,target,property): propose a direction and transferred property; source/target may be ambiguous and must be checked.
- abstain(reason): explicit unsupported/insufficient-information result.

The prototype only returns typed proposals. Next, resolve terms into Tree node IDs, retrieve independent evidence, distinguish literal from figurative relationships, consider competing directions and alternative properties, label provenance and uncertainty, and submit candidate relationships for independent judgment. No model call itself commits knowledge or acts on an external system. Do not equate syntactic validity with semantic validity. Reject invalid or uncertain proposals, preserve existing relationships, and permit a human override.

## Evaluation before integration

Compare base model Q8_0 with a future task-specific fine-tune. Measure operation choice, argument exact/semantic agreement, direction and property agreement (multiple defensible answers), literal/irrelevant abstention, rejected malformed output, unsupported-claim rate, timing and thermal behavior. Split by semantic domain, not paraphrases alone; hold out motifs and examples, include adversarial and ambiguous cases. Human review should assess meaning independent of model agreement. Use retrieved knowledge for facts, rather than relying on a 270M model's memorized content. Long schemas/prompt prefill can dominate latency; only expand the operation vocabulary after evaluation.

Device examples: "Find evidence about sodium-dependent glutamate transport"; "Compare a river and a torrent"; "Examine the semantic displacement: his words were anchors"; "Examine: The river is full of water" (literal negative); unrelated requests (abstain); the original poetic eyes/sea example with several plausible interpretations.

Implementation: pinned ggml-org/functiongemma-270m-it-GGUF Q8_0, verified SHA-256, independent 2048-token resident context, bounded 160 generated tokens, one serialized native engine and shared queue. CPU first; compare GPU or alternate quantizations after quality baselines. No full graph retrieval or automatic inference from observed posts yet. Gemma terms apply to the model, distinct from Roots' source license.
