# 0057 — Persons are clustered under constraints, and `skos:exactMatch` is written only where its transitivity holds

- Status: Accepted
- Date: 2026-11-09
- Amends: [ADR 0032](./0032-graded-alignments.md) (what an alignment's
  predicate commits to; a name-only proposal is a close match), [ADR 0018](./0018-entity-resolution-at-scale.md)
  §2 and §4 (the equivalence verdicts and their revision)
- Amended by: [ADR 0061](./0061-an-agent-is-no-skos-concept.md) (the
  identity claims are Palomar's own relations, not SKOS; each export writes
  its own term)
- Builds on: [ADR 0041](./0041-resemblance-identity-shared-node.md)
  (name-only agents scoped to their record), ADR 0005 (statuses), ADR 0028
  (decisions as annotations), ADR 0029 (graphs by status)

## Context

Lots M2 to M4 decide, mention by mention, which authority a name may be
(`reconcile/agent-candidates`), and `reconcile/align-agents` writes the
decision between a record's local agent and the authority:
`skos:exactMatch`, `:proposed`, for a certifiable proposal made outright
(and, before this ADR, for a name-only score of 1.0); `skos:closeMatch`,
`:proposed`, for one for review; a curator then accepts or rejects it. Nothing decides who is the
same person *across* mentions. Where Palomar groups agents today, it closes
through the authority with no check:

- `reconcile-agents` groups identified agents by their authority IRI;
- `apply-agent-links` gives every record whose accepted link names an
  authority that authority's node;
- the eval's `:top-1` rules, and any reader who accepts every proposal,
  group mentions by the authority they are linked to.

And the predicate itself closes: the SKOS Reference (W3C Recommendation,
2009, §10) makes `skos:exactMatch` a sub-property of `skos:closeMatch`
(S42), symmetric (S44) and transitive (S45) — numbering checked against
the published text. Two mentions exactMatch one undated authority
are, for a SKOS reasoner, exactMatch each other, though one was born in
1802 and the other in 1824. A chain A~B, B~C where A and C cannot be one
person ends with the three merged. That transitivity is exactly what a
constrained clustering must not assume.

The predicates on the table:

| predicate | what it commits to | for a proposal | for an accepted identity |
|-----------|--------------------|----------------|--------------------------|
| `owl:sameAs` | identity: every statement about one is true of the other; transitive, symmetric, reflexive | far too strong: a score is no identity | still a reasoning hazard: identity is already the shared node (ADR 0032) |
| `skos:exactMatch` | interchangeable "across a wide range of applications"; symmetric, transitive; its domain (S19, via `skos:semanticRelation`) makes both ends `skos:Concept` | its closure over proposals is the naive clustering | sound when the set of in-force ones is an equivalence the constraints allow |
| `skos:closeMatch` | interchangeable in some applications; symmetric, **not** transitive, "to avoid compound errors" | right for a doubt | not an identity |
| schema.org `sameAs` | a URL that identifies the item (a reference page); no logical consequence | no | possible for a schema.org target, which Palomar has not |
| Linked Art `equivalent` (`la:equivalent`) | an external URI for the same entity, "similar to `skos:exactMatch`" without making it a `skos:Concept` (Linked Art terms) | no: Linked Art has no proposal | the authority's URI on the actor |
| CIDOC CRM `P130_shows_features_of` | similarity of two `E70_Thing`s | not applicable: an `E21_Person` is an `E39_Actor`, not an `E70_Thing` | — |
| RiC-O `rico:isEquivalentTo` | two Things considered equivalent; an `owl:SymmetricProperty`, sub-property of `rico:isRelatedTo`, declared nothing more — not transitive (RiC-O 1.1, `test/fixtures/documentary/rico/RiC-O_1-1.rdf`) | possible, untested by any consumer of ours | the RiC view has no reconciled agent yet |
| PROV `prov:alternateOf` / `specializationOf` | aspects of one *entity* (`prov:Entity`), not of an agent | no | no |

## Decision

### 1. Clusters are built under constraints, never by closure

`palomar.agent-cluster/cluster` groups items (local agents, mentions,
authorities, accepted person nodes) from their pairwise decisions:

- **Inputs.** Links, each with a basis: `:accepted` (a curator accepted the
  alignment, or the membership), `:propose` (the matcher proposed it
  outright, M4's `:decision :propose`, with its match weight), `:review`.
  Cannot-links: those given — a candidate rejected for its dates (M3), a
  **rejected alignment** — and two derived on every merge: **two different
  authorities** (an authority record is one person; a mention accepted for
  one therefore never joins another's) and **incompatible dates** between
  any two dated items, the authority's dates included
  (`agent-dates/compatibility`). Two mentions of one record with different
  roles are *not* a cannot-link: a local agent is keyed by the record and
  the name, not the field or the role, and a record's author may be its
  illustrator; nothing in the IR tells the two cases apart.
- **A curator's decision outranks the machine.** Accepted links — an
  accepted exact match to an authority, an accepted membership (and through
  it the authority its cluster was keyed by) — are **hard must-links**, not
  the strongest of the proposals. Besides them, only outright proposals
  join. A link for review (a `closeMatch`, a competing candidate, a
  name-only proposal) is listed under `:candidates`, a merge for a curator;
  it is never applied.
- **Algorithm**, in two phases. (1) The accepted links are closed into
  components; a component holding two cannot-linked items is a
  **conflict**: none of its accepted links is applied — neither side wins,
  by id or otherwise — and all are reported with the pairs and reasons.
  (2) Greedy constrained agglomeration (union-find guarded by the
  cannot-links) of the outright proposals, by decreasing weight: a link
  whose merge would put two cannot-linked items in one cluster is refused
  and reported, with the pair and the reason, so in a chain A~B~C with
  A ⊥ C, B goes with the stronger side. **On a tie the lower id wins, and it
  is reported**: the refused link and the link that won carry `:tie? true`.
  No cluster holds two cannot-linked items, whatever the order of the
  links. Correlation clustering was not chosen: it optimises a global
  agreement score but treats a constraint as a cost, so it may break one;
  ours are hard.

### 2. A cluster is claims in the IR, revised through curation

`cluster-records` reads the alignments and memberships a batch holds and
writes, for each local agent in a cluster of two or more, its
**membership**:

    local-agent  skos:exactMatch  person-node      ; :proposed

- **The person node** is a `:reconcile/person` entity minted from the
  cluster's key (its authority's IRI, or its first agent) **and the
  batch** (`batch-fingerprint`, the batch's record ids). It has no `:iri`:
  it identifies no one, `accepted-links` ignores it, and it never becomes a
  shared node (ADR 0041). A node already named by a membership is kept by
  the cluster that holds it (as an accepted node, by its anchor, or by most
  of its members), so a growing batch does not churn its memberships.
- **Every claim the machine writes is `:proposed`**, its provenance naming
  the rule (`:reconcile/cluster-agents`), the cluster's key (`?cluster`)
  and size (`?members`). A curator accepts it like any claim. Local agent
  ids never change.
- **Re-clustering revises pending claims only.** A pending membership that
  no longer holds — after a new date, or a curator's rejection of a link —
  is rejected through `palomar.curate`, with the reason (the cannot-link
  that split it); the agent's membership in its new cluster is proposed. A
  claim proposed again (a re-merge) is **reopened** (`curate/reopen`, back
  to `:proposed`, in its own history), never copied: a record never holds
  two assertions with one `assertion-id` that the clustering or curation wrote
  (`model/one-claim-per-fact?` checks it; it is not part of
  `record-consistent?`, since an importer may state a native value once per
  source occurrence, as the INTERMARC flat natives do). An accepted claim is never superseded or rejected by the
  machine: new evidence that contradicts it is a conflict (§1), reported.
  A re-run with the same evidence changes nothing.
- **Conflicts and refusals live in the IR.** A refused alignment that is
  still pending goes to `:needs-review`, with the reason as a curation
  decision in its history: its status is the curator's queue, the reason
  travels with the claim (PROV-O), and a curator who then accepts it does
  so over a recorded refusal, which the next clustering reports as a
  conflict. A conflict among accepted claims cannot be recorded by moving
  their status (§1: the curator's decision stands), so it is a diagnostic
  (`:reconcile/identity-conflict`, a warning) on each record it involves,
  naming the pairs, the reason and the claims, restated on every run and
  gone once the conflict is resolved.
- **A curator confirms a review candidate** with `reconcile/confirm-identity`:
  the `closeMatch` is accepted and superseded by an accepted `exactMatch`
  between the same two (`curate/supersede`), with the reason; clustering
  and `accepted-links` read the accepted `exactMatch` only.
- **`apply-agent-links` checks the same constraint**: two accepted links
  to one authority whose mentions' dates cannot be one person's are both
  refused (`:conflict`), never joined on the authority's node.

**Constraints are enforced within a batch only.** There is no store across
batches (ADR 0018 §5): two batches clustered apart mint two person nodes
for one authority, so Palomar's own memberships never close them, but
their alignments to one *undated* authority still meet in any store that
loads both — a reasoner closing `exactMatch` there merges what neither
batch could check. Against a *dated* authority the M3 date block applies
at matching: a mention whose dates are incompatible with the authority's
is rejected, never aligned, in whichever batch it is.

### 3. What Palomar writes for an identity

| | between a local agent and an authority (`align-agents`) | between a local agent and its person node (`cluster-records`) |
|-|-|-|
| accepted identity | `skos:exactMatch`, `:accepted` (an outright proposal accepted, or a review candidate confirmed); applied as the authority's shared node (`apply-agent-links`) | `skos:exactMatch`, `:accepted` (a curator's) |
| proposed identity (outright, `:decision :propose`) | `skos:exactMatch`, `:proposed` | `skos:exactMatch`, `:proposed` |
| review candidate (`:decision :review`, and every name-only proposal) | `skos:closeMatch`, `:proposed` | none: a candidate merge, listed, never a claim |
| refused by a constraint | the same claim, `:needs-review`, with the reason | — |
| rejected / superseded | the same predicate, out of force | the same, out of force |

A name-only proposal is a close match whatever its score: no model decided
it, and an `exactMatch` Palomar writes without a curator is only ever a
matcher's outright decision.

**`skos:exactMatch` stays**, for two reasons. Between a local agent and its
person node its transitivity is *true*: a cluster is an equivalence class
the constraints allow, so the closure of a batch's memberships is its
clusters and nothing more. Between a local agent and an authority,
curation moves one claim's status, never its predicate (the assertion id
is its subject, predicate and value, ADR 0027), so a proposal accepted must
be the claim it was; a review candidate becomes an identity only by a
curator's supersession. The closure SKOS licenses is then kept within
bounds by where each claim is written: a target that states facts writes
only in-force claims unless asked for proposals (ADR 0042), and a dataset
puts a proposal in the proposals graph, which quotes and does not assert
(ADR 0029), so no reasoner over the facts closes a proposal; among the
facts are only accepted claims.

The facts can still close two persons into one in two ways, and both are
now refused or reported. (1) **One local agent accepted for two
authorities**: `cluster` reports the conflict (two authorities in one
must-link component) and `apply-agent-links` refuses it as `:ambiguous`.
(2) **Two local agents accepted for one authority whose dates cannot be one
person's**: through the authority, their accepted `exactMatch`es close the
two into one. `cluster` reports the conflict (and records it as a
diagnostic), and `apply-agent-links` refuses both links as `:conflict`
instead of giving both records the authority's node. The accepted claims
stay in the facts until the curator resolves the conflict; the diagnostic
says so. Across batches (§2) neither check can run. **Palomar's code never
closes `skos:exactMatch`**: every grouping goes through `cluster`. No
`owl:sameAs` is written (ADR 0032), and no `exactMatch` is ever written
between two records' local agents directly.

What each target gets:

- **N-Triples, Turtle, JSON-LD, CRM**: the SKOS claims above, as the RDF
  view writes them today (`skos:` is a view namespace), the person node as
  its `urn:palomar:` IRI — the accepted ones by default, the proposals only
  with `--with-proposals` (ADR 0042); pure CRM keeps them (no CRM property
  says identity of actors; `P130` does not apply to them).
- **N-Quads / TriG**: each claim in the graph of its status (ADR 0029):
  proposals (with those for review), facts, history.
- **PROV-O**: each claim's provenance — the matcher's evidence (M3, M4), the
  clustering's key and size, every curation decision and its reason — as
  today.
- **Linked Art**: no proposal and no membership (Linked Art has no status
  to hold one); an accepted identity is the actor's `id`, the authority's
  URI, once `apply-agent-links` applied it — what `equivalent` would say,
  said by the node. Unchanged.
- **MARC21**: nothing of a proposal or a cluster (MARC has no proposal). An
  accepted identity to an authority would be a `$1` (the URI of the person,
  e.g. an ISNI) or a `$0` (the authority record); the bibliographic MARC21
  exporters write neither today, and this ADR does not add them.

What changed in the output: a name-only proposal is now a `closeMatch`
(it was an `exactMatch` at a score of 1.0), a refused alignment goes to
`:needs-review`, a conflict is a diagnostic, and the membership claim is
new. The predicates of outright and review proposals stand.

## Alternatives considered

- **Demote every proposal to `skos:closeMatch` and promote on acceptance.**
  It would keep the proposals graph free of transitive claims, but
  acceptance would then be a new claim, not a status change of the one a
  curator read, and the proposals graph does not entail anyway. It is what
  a review candidate does (`confirm-identity`), since a curator there makes
  the identity rather than accepts the matcher's.
- **Let the strongest link win a conflict among accepted claims, or the
  lower id.** It would keep a cluster, but choose for the curator; a
  conflict is held back and reported instead.
- **Supersede an accepted membership the evidence contradicts.** It would
  overrule a curator by machine; the conflict is reported instead.
- **One person node per authority, across batches.** It would let two
  batches' accepted memberships close two persons no check ever compared;
  a node per batch keeps each closure to what was checked.
- **`owl:sameAs` between members of a cluster.** Rejected as in ADR 0032:
  the shared node already says it where it is attested; for a proposal it
  is false confidence.
- **A Palomar property (`palomar:samePersonAs`).** No consumer reads it;
  `skos:exactMatch` says the same where its transitivity is true.
- **RiC-O `isEquivalentTo` or Linked Art `equivalent` as the IR predicate.**
  Neither is transitive by declaration, which suits a proposal, but each
  belongs to one target; the IR keeps SKOS and a target maps it when it has
  a place for it.
- **Destructive merge (one agent id per cluster).** Rejected by ADR 0018
  §2: a wrong merge is not cleanly reversible. Memberships are.

## Consequences

- Measured on the M2 gold (`docs/eval/person-reconciliation.md`, M5; no
  curated link in that eval, so phase 1 is empty there): with every
  proposal joining, the naive closure makes 464 false merges (Férat with
  Verne, Stahl with Hetzel, three "Víctor Hugo" authors, through their
  competing candidates); the constrained clustering of the same links
  makes none and gives M4's top-1 numbers. Under the policy (outright
  proposals only), the two agree and the 25 mentions for review stay
  alone: recall falls from 0.999 to 0.829, the cost of not merging what a
  curator has not decided. No refusal on M2 is a tie. On 9 synthetic chains
  the closure merges every time, the constrained clustering never; each of
  those 9 refusals is a tie (equal weights) and is reported as one.
- A curator now has four lists: proposals per mention (M4), candidate
  merges (`:candidates`), refused alignments (`:needs-review`, with their
  reason) and conflicts among accepted claims (diagnostics).
- Clusters live in the records that hold the agents: there is still no
  store across batches (ADR 0018 §5); re-clustering a batch is the revision.
- No spoke and no CLI command runs the clustering yet; it is an API
  (`agent-cluster/cluster`, `cluster-records`, `reconcile/confirm-identity`),
  as the M3/M4 matchers are.
- Pinned by `palomar.agent-cluster-test` and, for `apply-agent-links`,
  `palomar.reconcile-test/apply-agent-links-never-joins-two-incompatible-mentions-on-one-node`.

## Amendment (lot O2, ADR 0061, 2026-11-11): an agent is no SKOS concept

SKOS gives `skos:semanticRelation`, and so `skos:exactMatch` and
`skos:closeMatch`, the domain and range `skos:Concept` (SKOS Reference
S19, S20, S39, S40, S42). Every person and group linked by the claims of
§2 and §3 was a concept for an RDFS reasoner. ADR 0061 replaces the
predicates one for one, and keeps everything else of this ADR:

- an outright proposal, and an accepted identity, to an authority:
  `palomar:matchesAuthority` (was `skos:exactMatch`);
- a review candidate, and every name-only proposal:
  `palomar:resemblesAuthority` (was `skos:closeMatch`);
- a membership: `palomar:inCluster` (was `skos:exactMatch` to the person
  node);
- the equivalence links of ADR 0059: `palomar:sameEntityAs`.

The transitivity this ADR relied on is now a guarantee of the code, stated
on the internal relations and pinned by tests (ADR 0061 §2): the
memberships close to the clusters and nothing more, and Palomar never
closes `matchesAuthority`.

What each target writes (§3) changes: a `matchesAuthority` a curator
accepted, one claim at a time and signed, is an `owl:sameAs` in the RDF
targets when no conflict names it (the last clustering's, or one the export
finds itself), the authority's stated type agrees with the agent's kind,
and its IRI names the person (an ISNI, not a catalogue record); else the
internal relation (ADR 0061 §3). Linked Art writes an accepted identity as the
actor's `equivalent`. The "no `owl:sameAs`" of §3 holds for proposals,
memberships, review candidates and record IRIs. Old IR documents are
upgraded when read.
