# 0061 — An agent is no SKOS concept: identity has its own relations, and each export writes its own term

- Status: Accepted
- Date: 2026-11-11
- Amends: [ADR 0057](./0057-the-identity-relation-between-persons.md) §2
  and §3 (the predicate of an identity claim, and what each target
  writes), [ADR 0032](./0032-graded-alignments.md) §1, §2 and §4 (SKOS
  mapping claims; no `owl:sameAs`)
- Builds on: [ADR 0059](./0059-record-identity-is-not-entity-identity.md)
  (record identity is not entity identity), [ADR 0042](./0042-facts-only-by-default.md)
  (a plain export states facts only), [ADR 0029](./0029-named-graph-datasets.md)
  (graphs by status), [ADR 0028](./0028-curation-decisions-as-annotations.md)
  (decisions as annotations)

## Context

Palomar wrote `skos:exactMatch` and `skos:closeMatch` between agents:
from a record's local agent (an `E21_Person` or an `E74_Group`) to an
authority, and from a local agent to its batch's person node. ADR 0057
kept SKOS for its transitivity: the closure of a batch's memberships is
its clusters.

SKOS says more than that. The SKOS Reference (W3C Recommendation, 2009)
states:

- S19: the `rdfs:domain` of `skos:semanticRelation` is `skos:Concept`;
- S20: its `rdfs:range` is `skos:Concept`;
- S39: `skos:mappingRelation` is a sub-property of `skos:semanticRelation`;
- S40: `skos:closeMatch` is a sub-property of `skos:mappingRelation`;
- S42: `skos:exactMatch` is a sub-property of `skos:closeMatch`;
- S44: `closeMatch` and `exactMatch` are symmetric;
- S45: `skos:exactMatch` is transitive.

So an RDFS reasoner types every person and group Palomar linked as a
`skos:Concept`. This is no contradiction: CRM does not make `E39_Actor`
disjoint with `skos:Concept`. But it is an entailment nobody wants in data
meant to be reused. Linked Art says the same of its own choice: its
`equivalent` is "similar to skos:exactMatch", without "the same inference
issue as exactMatch, whereby the domain and range become skos:Concept"
(`la:equivalent`, Linked Art terms; domain and range `E1_CRM_Entity`, no
super-property).

## Decision

### 1. The IR states identity between agents with Palomar's own relations

The relations are in the `palomar:` vocabulary, `urn:palomar:vocab#`,
where the dataset's graph classes and the PROV-O terms already are. In the
IR they are `:palomar/*` keywords. `palomar.identity/relations` holds them
as data. Three kinds are kept apart:

| | relation | between | IRI |
|-|-|-|-|
| (a) reconciliation, an identity claim | `:palomar/matchesAuthority` | a local agent (a mention's node) and an authority | `urn:palomar:vocab#matchesAuthority` |
| (a) reconciliation, a candidate for review | `:palomar/resemblesAuthority` | the same | `urn:palomar:vocab#resemblesAuthority` |
| (b) two authority records of one entity (ADR 0059) | `:palomar/sameEntityAs` | two authorities | `urn:palomar:vocab#sameEntityAs` |
| (c) the clustering's result | `:palomar/inCluster` | a local agent and its batch's person node | `urn:palomar:vocab#inCluster` |

They replace `skos:exactMatch` and `skos:closeMatch` one for one: an
outright proposal is a `matchesAuthority`, a review candidate and every
name-only proposal a `resemblesAuthority`, a membership an `inCluster`.
Status, evidence and provenance are kept as before. A curator accepts,
rejects or confirms them as before (`reconcile/confirm-identity`
supersedes a `resemblesAuthority` by an accepted `matchesAuthority`).

(b) names the clustering's equivalence links (`:predicate` on each). It is
not written into the records: the statement is the authority records'
own, recomputed in each batch (ADR 0059 §5), and no curator decides it.

Concepts keep SKOS. Nothing Palomar writes about a subject or a vocabulary
changes, and the authority reader still reads `skos:exactMatch` in a
record as a `sameAs` (ADR 0059 §1): there it is the data's own statement.

### 2. The guarantees of ADR 0057 are stated on the internal relations

No relation is declared transitive for a reasoner. Each guarantee is kept
by the code, and pinned:

- **The memberships close to the clusters, and nothing more.** Grouping a
  batch's local agents by the person node they are `inCluster` gives its
  clusters, and no cluster holds two cannot-linked agents.
  `palomar.identity-test/the-memberships-close-to-the-clusters-and-nothing-more`,
  `palomar.agent-cluster-test/two-cannot-linked-items-are-never-in-one-cluster`.
- **Palomar's code never closes `matchesAuthority`.** Two accepted claims
  to one authority whose agents cannot be one person are a conflict:
  reported by `cluster`, and refused by `apply-agent-links` (`:conflict`).
  `palomar.agent-cluster-test` (the conflict tests) and
  `palomar.reconcile-test/apply-agent-links-never-joins-two-incompatible-mentions-on-one-node`.
- **A `resemblesAuthority` joins nothing** until a curator confirms it.
  `palomar.agent-cluster-test/a-curator-confirms-a-review-candidate-by-superseding-it`.
- **`sameEntityAs` is the union of what the records state**, refused on an
  ISNI clash through a chain or a pseudonym in the component (ADR 0059
  §3). `palomar.agent-equivalence-test`.

### 3. Each export writes the term of its destination, by one rule

`palomar.identity/export-rule` is the rule, as data. The RDF exporters read
it through `identity/rdf-predicate` (in `export/triples`, which every RDF
view renders), and Linked Art through `identity/linked-art-equivalent?`.
The status policy still comes first (`convert/export-records`, ADR 0042):
a plain export holds no proposal.

| destination | an identity claim that passes every gate | otherwise |
|-|-|-|
| N-Triples, Turtle, JSON-LD, CRM, pure CRM, N-Quads, TriG | `owl:sameAs` | the internal relation: in the facts when in force; in a dataset's proposals or history graph, or with `--with-proposals`, when not; nothing of a proposal in a plain export |
| Linked Art | `equivalent` on the actor, the authority's IRI | nothing |
| PROV-O | the claim as the IR holds it, with its status | the same |
| MARC21, DC, MODS, IIIF | nothing (they never read the SKOS links) | nothing |

A review candidate, a membership and an equivalence are always the
internal relation in RDF, never `owl:sameAs`.

**The gates of `owl:sameAs`.** `owl:sameAs` says that two IRIs name one
individual, so that every statement about one is true of the other. It is
written for a `matchesAuthority` only when all five hold, in this order
(`:when` in the rule):

1. **`:in-force`**: the claim is `:accepted` or `:asserted`.
2. **`:curated`**: it is `:asserted`, or the decision that last put it in
   force is a curator's. The code tells the two apart by the decision
   annotation (ADR 0028). Only `curate/decide-one` (one claim at a time)
   and `curate/supersede` (`confirm-identity`) write `:curated true` on an
   acceptance. `curate/curate` and `curate-record`, which apply one policy
   to a batch (`accept-all`, `accept-when`, the CLI's `--policy accept`),
   never write it, whatever the policy returns and whatever the context
   holds: the context gives only `:by` and `:at`. The decision must also
   be signed: `:by` is set. So `apply-repairs --policy accept --by x`
   gives `palomar:matchesAuthority`, never `owl:sameAs`. The last
   acceptance counts: a claim rejected, reopened and accepted again by a
   policy is not curated any more. An `:asserted` claim needs no decision:
   it is stated by the source (ADR 0042). Palomar's own alignments are
   never asserted. A decision recorded before this lot has no marker, so
   an old accepted claim is no `owl:sameAs` until a curator decides it
   again.
3. **`:no-conflict`**: no conflict names it. Two sources count:
   - **the last clustering run**, read from the record: the
     `:reconcile/identity-conflict` and `:reconcile/identity-events-conflict`
     diagnostics `cluster-records` wrote (ADR 0057; lot M7). They hold what
     only the clustering knows: stated equivalences between authorities
     (ADR 0059), memberships, the events given to it as input;
   - **the export's own check**, over the records it writes together
     (`palomar.identity-check`, run by `convert/export-records` for these
     targets). Two in-force claims to one authority IRI whose local agents'
     life dates are incompatible (`agent-dates/compatibility`), or whose
     typed events, from the records' mentions, cannot be the other's life
     (`agent-events/compatibility`), are both in conflict. So is a claim
     whose agent's dates cannot be the authority's, as the claim compared
     them, and one local agent with in-force claims to two authority IRIs.
     Each is a `:reconcile/export-identity-conflict` diagnostic (a warning,
     no loss), returned with the export's diagnostics (`export-records`'
     `:loss`) and named in the conversion report (`:identity-conflicts`);
     the records are not changed. It needs no
     clustering, so a record curated after its clustering, or never
     clustered, is still checked. A streamed conversion writes one record
     at a time, so it checks within each record only.

   Two accepted claims in conflict stay facts (ADR 0057), as the internal
   relation: `owl:sameAs` would close two persons into one.
4. **`:kind-agrees`**: the type the authority states, when it states one,
   is the agent's kind (`identity/agent-types`). A person agent
   (`E21_Person`) goes only to a `"person"`; a group (`E74_Group`) only to a
   `"corporateBody"` or a `"family"`. An undifferentiated person (a GND Tn,
   `"UndifferentiatedPerson"`), a work, or any other stated type disagrees.
   An unstated type cannot disagree. The authority entity carries its
   stated type (`:entity-type` on the `:reconcile/authority` entity, from
   the proposal's).
5. **`:entity-iri`**: the authority's IRI names the entity, not a record
   (`identity/entity-iri-schemes`): an ISNI, a VIAF cluster, a Wikidata
   item, a GND IRI (`d-nb.info/gnd/`), a LoC `rwo/agents` IRI, a
   `data.bnf.fr` `#about` IRI, a ULAN `-agent` IRI. A BnF catalogue ARK
   (with or without `#about`), a LoC name authority or a ULAN subject
   names a record or a concept about the person, and the person is not
   that record (ADR 0059). Such a claim stays the internal relation. A
   scheme that also names works, places or subjects (GND, VIAF, Wikidata,
   data.bnf.fr) needs a stated type. **When the type is unknown**, only a
   scheme that names agents alone (ISNI, LoC `rwo/agents`, ULAN `-agent`)
   gives `owl:sameAs`: a GND IRI of unknown type may be a Tn, a work or a
   place, so it stays `palomar:matchesAuthority`.

A local agent is scoped to its record (ADR 0041). So the claim says this
record's agent is the person, not that every same name is.

**Linked Art.** Linked Art's own pattern is `equivalent`, an external IRI
for the same entity (Linked Art actor model). It takes gates 1 to 4, not 5:
Linked Art's own example gives a ULAN subject IRI as the equivalent of a
person, and `equivalent` entails no identity of the two. It is written on
the actor whose `type` is the claim's: a `Person` for a person agent, a
`Group` for a group. The actor is the one that bears the agent's IRI, or,
bearing none, its name. When the name is on actors of several types (a
creator `Person` and a publisher `Group` of one name), no `equivalent` is
written on any, and a loss says so. An actor that already is the authority
(an applied link) gets none. A proposal is never written. A written
`equivalent` is no loss.

**A known inconsistency, left to lot O3.** `apply-agent-links` gives an
accepted link the authority's node: an `E21_Person` (or `E74_Group`) whose
IRI is the authority id. When that id names a record (a BnF catalogue
ARK), the export types the record's IRI as the person, while the same
claim stays `palomar:matchesAuthority` by gate 5. The two say different
things of one IRI. This lot does not change `apply-agent-links`. Lot O3,
which re-models the agent nodes (the occurrence, the local agent, the
entity), takes it as input. (Resolved by lot O3, ADR 0063 §4: an applied
link keeps the local agent's node and links it to an untyped authority.)

### 4. Older records still load

`palomar.identity/upgrade` rewrites the SKOS identity claims of a record an
older Palomar wrote: `skos:exactMatch` to an authority becomes
`matchesAuthority`, `skos:closeMatch` to an authority `resemblesAuthority`,
`skos:exactMatch` to a person node `inCluster`. The claim keeps its status,
confidence and provenance. Its id changes (ADR 0027: the predicate is part
of it), so the curation decisions about it, the replacement a decision
names and the claims a conflict names are moved to the new id.
`palomar.ir/read-records` applies it. An old IR document, its decisions
and conflict diagnostics included, reads back as the records the new code
writes, and curates, clusters and exports the same
(`palomar.identity-test/an-old-ir-document-with-skos-identity-claims-still-loads`).
A SKOS mapping to anything else is left as it is.

**A document that holds a fact twice**, as an old SKOS claim and a new one,
holds two claims with one id after the upgrade. They are made one, and the
record holds one claim per fact again (`model/one-claim-per-fact?`). Their
decisions are already one history: they share the id.
- When the two agree (one status), one claim is kept, the in-force one
  first.
- When they differ (accepted and rejected), the status is the one the
  latest decision by time (`:at`) gave. When a decision has no time, the
  claim goes to `:needs-review`, with a `:palomar/identity-upgrade-collision`
  diagnostic.

(`palomar.identity-test/an-upgrade-collision-of-an-old-and-a-new-claim-is-one-claim`.)

### 5. No agent ever gets a SKOS mapping property, and every sameAs is sound

`palomar.no-skos-on-agents-property-test` checks it. Over every source
fixture that converts (41 of the 43 spoke fixtures; two give no record),
as converted and with its agents reconciled, curated and clustered by a
fixed plan, and over generated reconciliations of one fixture per spoke,
it exports to every RDF target and to Linked Art, with and without
proposals.

- No subject or object typed `E21_Person`, `E74_Group`, `E39_Actor` (or
  `E40_Legal_Body`, `F11_Corporate_Body`, a RiC-O agent) is in a triple
  whose predicate is `skos:semanticRelation` or a sub-property of it. No
  Linked Art `Person` or `Group` holds a SKOS key (`exact_match`,
  `close_match`, ...). A companion test shows the check catches a SKOS
  identity claim in every RDF export.
- Every `owl:sameAs` comes from an in-force `matchesAuthority`, asserted
  or accepted by a signed per-claim decision, to an entity IRI, the stated
  type agreeing, no conflict naming it. This is checked with the
  clustering run last, and again after a bulk `accept-all` of what is
  left, which adds no `owl:sameAs`. A curator's acceptance of a review
  candidate is shown to give none.

## Alternatives considered

- **Keep SKOS, and type the agents' IRIs apart.** The entailment would
  still be drawn: a reasoner does not ask.
- **`owl:sameAs` in the IR.** A proposal is no identity, and an authority
  IRI is often a record's. The IR states the claim; the export decides the
  term, where it knows the destination.
- **`owl:sameAs` for every accepted claim.** It would make a person the
  same individual as a catalogue record, and close two persons whose
  accepted claims conflict.
- **`owl:sameAs` for any acceptance with `:by`.** A policy run with a
  curator's name (`--policy accept --by`) would then publish identities no
  one read. The `:curated` marker is written only by per-claim decisions.
- **Trust the stored clustering diagnostics alone.** A record curated
  after its clustering, or never clustered, would carry no conflict.
- **No `owl:sameAs` at all, as before.** A CRM consumer then has no
  standard term for an accepted identity. The condition keeps it to where
  it is true.
- **RiC-O `isEquivalentTo` or PROV `alternateOf`.** Each is one
  vocabulary's term, for things that are not agents in CRM (ADR 0057).
- **`la:equivalent` in every RDF export.** It is Linked Art's term. A plain
  CRM consumer does not read it.
- **Write (b) into the records.** The equivalence is the authority
  records' statement, recomputed per batch. A claim of it would be a copy
  to keep in step.

## Consequences

- No RDF export puts a SKOS mapping on a person or a group. Concepts keep
  SKOS.
- A curator's accepted identity to an entity's IRI is now an `owl:sameAs`
  in the facts. A bulk acceptance, a record's IRI, a disagreeing type or a
  conflict leaves it a `palomar:matchesAuthority`. Linked Art gains
  `equivalent` on the actors of curated claims.
- `export-records` returns the conflicts the export found with its
  diagnostics, and `convert`'s report names them (`:identity-conflicts`).
- The claim ids of identity claims change. Old IR documents are upgraded
  when read. Code holding records in memory from an older version calls
  `identity/upgrade`.
- The eval numbers do not move: the evals read the claims through
  `accepted-links` and `cluster-records`, which read the new relations.
- No CLI change.
