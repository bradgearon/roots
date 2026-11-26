# Roots economy — creator payments, gifts, bounties and optional attestations

Status: proposal for optional capabilities of the open Roots specification. No payment integration, custody, token or live financial service exists. Providers and jurisdictions are independent of knowledge interoperability. No money is required to use Roots, receive Doxa, form relationships or participate in Whirlwind.

## Social and creative economy

Support voluntary one-time tips to creators, gifts to friends/family, optional Doxa with a gift, paid commissions, subscriptions/sponsorship, shared collaborator income, licensed knowledge sales, community fundraising, and paid compute/storage/bandwidth/research/translation work. Free contributions and recognition remain equally meaningful; no pay-to-rank, paid relationship access, obligatory gift or general social score based on wealth. Public organic discovery and labeled paid sponsorship are distinct. Gift/award state and transaction state are independent; a payment reversal does not silently erase appreciation. Users may opt out of offers/requests and hide amounts; no public balance or income inference.

A root:// creator/support, project/bounty or community/fund address resolves a resource/offer; resolving it must not execute payment. Verify recipient and provider, amount/currency, fee, expected delivery, recurring terms, cancellation/refund rules and any exchange rate before independent explicit authorization. No arbitrary agent spending; an optional later mandate requires separate scoped limits, expiry, revocation and user review. Gifts are not obligations or implicit romantic consent.

## Bounty system

Bounties are explicit requests for outcomes, not automatic rewards inferred from graph weight. Examples: software fixes, original music/art, translation, research with reproducible evidence, educational material, verifying a knowledge claim, improving a Branch, benchmark implementation, and approved Whirlwind computation. Support open calls, invited/assigned work, first valid submission, best accepted result after deadline, milestone-based contracts, and shared or sponsored prizes as distinct declared award modes. No anonymous targeted harassment, privacy-invasive investigations, harmful tasks, manufactured engagement or academic dishonesty. Moderation and eligibility before publication. No assumption of escrow in v1.

| Record | Proposed fields |
| --- | --- |
| Bounty | stable ID, issuer, purpose, deliverables, acceptance rubric/test refs, scope, eligibility, license/IP terms, reward/currency, award mode, deadline, claim/entry limit, reviewer/dispute process, funding state, version |
| Funding commitment | funder, amount/currency, provider reference, funding authorization, verified status, expiry, refund terms |
| Claim / entry | worker, versioned terms, assignment/entry mode, time, status, permitted collaboration |
| Submission | author, immutable artifact/evidence refs, revision, provenance, licenses, declared collaborators, submitted time |
| Judgment | reviewer, rubric version, test results, reasons, conflicts/uncertainty, decision, appeal status |
| Allocation | accepted submission/milestone, explicitly agreed shares, amounts and fees, settlement obligations |
| Receipt | provider transaction ID, idempotency key, status, timestamps, amount/currency, reversal/dispute refs |

Lifecycle: draft -> published (funding status disclosed) -> open -> claimed/entries -> submitted -> under review -> accepted/rejected/revision requested -> settlement pending -> paid/closed. Cancellation, expiry, dispute and refund branches are explicit. Freeze terms for each accepted claim; materially changed terms require new agreement. Distinguish reward promised from verified reserved funds and settled payment. Do not guarantee payouts without verified provider funding and agreed terms. Contest mode specifies winner count, evaluation window and nonwinner treatment. Prevent duplicate awards by stable ID and atomic allocation; partial/milestone acceptance, shared submissions and multiple winners require predeclared allocation rules. Use provider-held escrow only if available and lawfully supported; WDS should not take custody by default.

Judgment uses actual deliverables, pinned test versions, reproducibility and human/authorized independent reviewers; models may assist, not unilaterally decide financial entitlement or establish truth. Preserve appeal/dispute channels, conflicts of interest, anti-Sybil/collusion, stolen work and refund controls. Whirlwind work receipts are not automatically proof of useful outcome; verify independently before acceptance. Accepted knowledge contributions may be grafted under agreed rights, but payment alone does not transfer copyright or grant redistribution. Public bounty search may match declared skills, interests and permitted evidence; personal scores or dating must not control eligibility by default.

## Payment and income service

PaymentProvider, RecipientRegistry, Offer/Quote, Authorization, Settlement, Receipt/Reconciliation, AllocationAgreement, IncomeLedger, Dispute/Refund. Use conventional payment processors first; optional Bitcoin/Lightning, Ethereum-compatible networks and stablecoin providers are candidates subject to jurisdiction, provider terms and fees. No proprietary Roots currency required. Prefer noncustodial/provider-hosted checkout initially. Maintain exact decimal/minor units per currency, explicit fees and net/gross accounting, idempotent callbacks, signed webhook verification, replay protection, reconciliation, pending/failure/reversal/refund states. Never store seed phrases in Trees or model context. Show income/expenses, gifts, payouts, royalties and exportable accounting records; not tax/legal advice. Revenue shares are explicit signed agreements, not inferred creative lineage or relationship weights; support rounding and multi-currency rules. Transfers require user confirmation, separate auth and recheck against current agreement.

Legal and operational launch gates: relevant jurisdiction, KYC/AML/sanctions as applicable, money transmission/custody, consumer protection, minors, taxes, chargebacks, fundraising and payout rules, fraud/security testing and app-store payment requirements. Use licensed providers and obtain appropriate professional review before launch. Avoid automatic irreversible settlement on unverifiable work.

## Optional blockchain and living attestations

Default signed off-chain records. Optional external blockchain adapters can anchor selected public attestations, provenance or settlement receipts, never the whole graph. Living scores remain recalculable from permitted, versioned, revocable attestations; a ledger is evidence of an event, not truth, merit or current consent. Public attestations must be deliberately opt-in, minimally identifying and lawful; keep raw private ratings, personal associations, precise geography, dating and conversation histories off public chains. Even hashed identifiers can link individuals; immutability prevents full erasure. Validate issuer, signature, scope, expiry/revocation and chain finality where applicable; handle reorgs and pending state. Avoid public links between wallets and private identities unless explicitly chosen. Payment provider and attestation provider are independently optional. No chain needed for file transfer, search, Whirlwind or grafting.

Candidate operations: bounty.create, bounty.publish, bounty.search, bounty.claim, bounty.submit, bounty.evaluate, bounty.accept, bounty.appeal, bounty.cancel; contribution.offer/request/evaluate/accept; payment.quote/authorize/settle/verify/refund; income.record/report; royalty.distribute; attestation.issue/verify/revoke. Names are proposals. Forge carries authorized calls, while Roots defines resource semantics. No implicit payment through resolution, browsing, Doxa or knowledge import.

## Acceptance and extensibility

Start with mock providers and signed records; test open/invited/milestone bounties, stale terms, duplicate submissions and settlement, multi-party split, unverified funding, refunds/disputes, provider timeout, replay, private ledger isolation, revocation, missing network, fraud and malicious links. Then use provider sandbox and independent legal/security review. Keep award rules, evidence validators and provider types extensible; later refinements can add specialized bounty categories and work workflows without coupling core knowledge runtime to money.
