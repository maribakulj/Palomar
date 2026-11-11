# 0059 — Record identity is not entity identity

- Status: Accepted
- Date: 2026-11-11
- Amends: [ADR 0057](./0057-the-identity-relation-between-persons.md) §1
  (the `:distinct-authorities` constraint)
- Builds on: [ADR 0018](./0018-entity-resolution-at-scale.md) §1 and §5
  (reconcile to an authority; no store across batches), [ADR 0038](./0038-authority-files.md)
  (authority files as pools, and its M6 amendment: snapshots), [ADR 0032](./0032-graded-alignments.md)
  (a close match is alike, not the same)

## Context

An authority record has an identity: its IRI in its file. A BnF record is
`http://catalogue.bnf.fr/ark:/12148/cb119010630`. A GND record is
`https://d-nb.info/gnd/118528068`. The person each describes is another
thing. Two records in two files can describe one person: the BnF and the
GND both have a record for Alexandre Dumas père. One file can also hold
two records for one person: a duplicate.

ADR 0057 made "two different authority records" a cannot-link
(`:distinct-authorities`): an authority record is one person, so two
mentions linked to two records are never one cluster. That is right inside
one file that holds no duplicate. It is wrong as soon as a batch reads two
files, or a file with duplicates: it treats the record's identity as the
entity's.

The records often say who they are the same as. What Palomar already reads:

- an INTERMARC authority (BnF) gives its ISNIs in `031 $a`. The pool now
  keeps every one, as a vector (`:isni`); a cancelled ISNI (`$z`) is not
  read. (Amended by lot N5's review: `$2` says where the ISNI came from,
  "ISNI" or "VIAF" or "ISNI chargé à partir de VIAF", not its scheme. Every
  `$a` that passes the ISNI check is an ISNI, and `$2` is kept as its
  source, `:isni-sources`. Until then a `$a` was read only when its `$2`
  began "ISNI".);
- a lobid GND record gives `sameAs` links to VIAF, ISNI, Wikidata, the
  Library of Congress and the BnF (`plugins/gnd`, `:same-as`). It was read
  as data, never as a match feature;
- a MADS or SKOS record could give `owl:sameAs`, `skos:exactMatch` or
  `madsrdf:hasExactExternalAuthority`. These were not read.

The M6 eval used two of these as **bridges** to judge the matcher
(`docs/eval/person-reconciliation.md`, M6). Nothing used them to decide.

## Decision

### 1. What is evidence that two records describe one entity

Only what the records themselves state:

- **a shared ISNI**: both records state the same ISNI;
- **a shared VIAF id**: both records state the same VIAF cluster;
- **a `sameAs`**: one record gives the other's id as the same entity
  (`owl:sameAs`, `skos:exactMatch`, a MADS exact external authority, a GND
  `sameAs`).

An id is read in one form whatever its spelling
(`agent-equivalence/identifier-key`):

- an ISNI with or without spaces, hyphens, an `ISNI` or `isni:` prefix, or
  as an isni.org or isni-url.oclc.nl URI. Its MOD 11-2 check character must
  be right. An ISNI that fails the check is no id, and it is reported;
- a VIAF URI, with or without a language segment (`viaf.org/en/viaf/N`);
- a BnF ARK from the catalogue, data.bnf.fr or n2t, with or without the
  slash after `ark:`, in either case;
- a data.bnf.fr page URL (`data.bnf.fr/11901063/alexandre_dumas/`). It
  names a record number, not an ARK. It is read as `bnf-record:N`, and it
  matches only a BnF record whose own ARK holds that number. It joins
  nothing else;
- a GND IRI from d-nb.info or lobid.org, `x` and `X` alike;
- a LoC name authority (`authorities/names`, `rwo/agents`, `info:lc`);
- a Wikidata item, `entity/`, `wiki/` or `Special:EntityPage/`, `q` and
  `Q` alike.

Each id ends where the IRI's path ends (`/`, `#`, `?`, `.` or the end), so
`gnd/111910582` and `gnd/111910582X` are two ids. An id is read at the
start of the string only, never inside another IRI. The MADS reader now
keeps the three `sameAs` predicates as `:same-as`. The authority entries
are matched with the clustering's authorities by this one form, so the
catalogue and data.bnf.fr spellings of one ARK meet.

### 2. What Palomar never infers

- No name or date similarity makes two records one entity. The matcher
  compares names and dates with mentions; it never compares two
  authorities.
- A close match (`skos:closeMatch`, a MADS close external authority) is no
  equivalence: it says alike, not the same (ADR 0032).
- A pseudonym relation is no equivalence. It links two identities of one
  person, and both files keep them as two records on purpose. It is read
  the other way: as evidence that two records are **not** one entity
  (§3).
- A Wikidata pair from outside the records (the eval's CSV) is no
  equivalence. A Wikidata id the records state is read like any `sameAs`.
- Nothing is looked up on the network.

### 3. Transitivity: the union of what is stated, refused on an ISNI clash

The records and the ids they state form a graph. Two records in one
component are one entity: if A states ISNI X, B states X, and C gives B's
ARK as `sameAs`, then A, B and C are one entity. This is the union of the
stated equivalences.

A component is **refused** in three cases. Palomar does not choose which
statement is wrong. No equivalence of a refused component is used: every
record in it stays its own entity, and `:distinct-authorities` holds
between them as before.

- **An ISNI clash through a chain.** Two records each state ISNIs, share
  none, and are joined only through other ids. ISNI says they are two
  identities, so the chain is wrong somewhere. This holds whatever the
  files: a GND and a BnF record with different ISNIs are as much two
  identities as two BnF records.
- **A pseudonym and its real identity in one component.** The records
  themselves say the two are different identities.
- **Two components, each the other's pseudonym.** The records say C1 is a
  pseudonym of C2, and C2 a pseudonym of C1. Both cannot hold. Both
  components are refused.

The pseudonym statements read are the GND's `pseudonym` and
`realIdentity`, and the BnF's 331. A 331's second indicator says which way
it points: 1, the linked record is a pseudonym of this one; 2, it is this
record's real identity. A 331 without one of these indicators gives no
direction and is not read for this. Each statement is lifted to the
components that hold its two records. Nothing is inferred: only stated
relations between records of the batch are read.

**A direct sameAs against disjoint ISNIs is kept.** When one record names
the other by a sameAs, the pair is one entity even if their ISNIs differ.
ISNI holds many duplicates, and a record that names another record is a
stronger statement than an ISNI assignment. The disagreement is reported.

**Other disagreements are reported, never refused.** Disjoint ids of
another scheme (VIAF, GND, LCNAF, Wikidata), and two records of one file
made one (a duplicate), are warnings. VIAF clusters split and merge often.
A Wikidata item can hold two GND ids for one person. Two records of one
file describing one person is the duplicate case this ADR is meant to
handle. ISNI is the one scheme refused on, because an ISNI is assigned to
an identity after a check, and the pseudonym relations are refused on
because the records state them as a distinction.

Refusals are reported under `:equivalence-conflicts`, with the reason, the
records, and the ISNIs or relation statements involved. Warnings are
reported under `:equivalence-warnings`. In the IR they become
`:reconcile/equivalence-conflict` and `:reconcile/equivalence-warning`
diagnostics on each record aligned to one of the authorities involved. They
are restated on every run.

### 4. What the clustering does with an equivalence

In `agent-cluster/cluster` an authority item may carry `:iri`, `:isni` and
`:same-as`. `cluster-records` takes them from the authority entries given
as `:authorities` (a pool's entries, matched by IRI).

- Two authorities stated one entity are **not** cannot-linked as two
  authorities. The constraint is lifted for exactly those pairs.
- A link of basis `:equivalence` joins them. It comes after a curator's
  accepted links and before the matcher's proposals. So a mention linked
  to the BnF record and one linked to the GND record become one cluster.
- None of this applies unless the clustering's `:join` holds
  `:equivalence` (the default does). Without it, two authorities are always
  two.
- Every other constraint still holds. A given cannot-link between the two
  records keeps them apart. So does a curator's acceptance of a mention
  for one record with a rejected alignment to the other. A mention only
  proposed for one record and rejected for the other stays out of the
  cluster: the stated equivalence comes first.
- Two equivalent records whose dates cannot be one life stay apart. The
  equivalence links are tried in id order, so the records with the lower
  ids stay together. An equivalence link is never part of a tie. A
  refused one is reported as a warning (`:dates-refuse-equivalence`): the
  records contradict each other.
- The cluster names what lifted the constraint: `:equivalence`, the links
  with their `:evidence`, `[{:by record :states id} ..]`, the statements of
  the shortest chain. One record under two spellings of its id gives
  `[{:same-record-id k}]`. A membership claim of that cluster states it in
  its provenance (`?equivalence`, e.g. "A and B: both state isni:…", or
  "A and B: same record id …").
- A cluster can come to hold accepted memberships to two person nodes: a
  curator accepted two persons, and an equivalence now joins them. Neither
  is superseded; a `:reconcile/person-nodes-conflict` diagnostic names
  them on each record involved.

The result does not depend on the order of the items or the links.

### 5. Federation is prepared, not done

Several authority files can now be read in one batch, and their records
joined where they say so. This is what a federation needs first: a rule for
when two files' records are one entity, and a refusal when they disagree.
This lot does not federate: there is no network query to another file, no
store of equivalences across batches (ADR 0018 §5), and no merged
"super-record". The equivalence is computed each time from the records in
the batch, so it is revised when a record changes.

## Alternatives considered

- **Keep `:distinct-authorities` absolute.** Simple, but two files about
  one person could never meet, and a duplicate in one file could never be
  merged.
- **Match authorities against each other by name and dates.** It would find
  more pairs, and wrong ones: namesakes are common across national files.
  It would also be a guess presented as a record's statement.
- **Lift the constraint without a joining link.** Then a BnF mention and a
  GND mention of one person still stay apart: nothing links them. The
  equivalence is a statement of the data, so it joins.
- **On an ISNI clash, keep the strongest part of the chain.** That chooses
  for the curator, as ADR 0057 refused for accepted claims.
- **Refuse only same-file clashes.** Rejected for the reason in §3.
- **Refuse on every scheme's disagreement.** It would refuse the
  duplicates this ADR is for, and VIAF's own merges. Only ISNI and the
  pseudonym relations refuse.

## Consequences

- On the M6 fixtures (33 BnF authorities, 197 GND records), the records
  state 19 BnF–GND pairs: exactly the eval's ARK and ISNI bridges (7 pairs
  have both). The one more authority the eval bridges comes from
  Wikidata's pairs alone, outside the records. 17 pairs are kept, one GND
  record each. No two records of one file are made one. (Before the 031
  correction of lot N5's review: 14 pairs, 3 with both, 12 kept. The 5
  pairs it adds are all kept; they bring no new refusal and no warning.)
- **Hetzel and his pseudonym Stahl are refused.** The ISNIs are crossed
  between the two files: the BnF's Hetzel shares an ISNI with the GND's
  "Stahl, P.-J." record, and the BnF's Stahl with the GND's "Hetzel,
  Pierre-Jules". The records also state which is the pseudonym of which.
  The GND says its Stahl is a pseudonym of its Hetzel; the BnF says its
  Stahl is a pseudonym of its Hetzel. Lifted to the two components, each is
  the other's pseudonym. Both are refused and reported, with the four
  relation statements.
- The M2 to M8 numbers do not move: the evals give the clustering no
  authority statements.
- Pinned by `palomar.agent-equivalence-test`.

## Note (lot O2, ADR 0061, 2026-11-11): a record's IRI typed as the person

ADR 0061 writes `owl:sameAs` only to an IRI that names the entity, not a
record. But `apply-agent-links` still gives an accepted link the
authority's node, an `E21_Person` (or `E74_Group`) whose IRI is the
authority id. When that id is a record's (a BnF catalogue ARK), the RDF
export types the record's IRI as the person. This contradicts §1 of this
ADR and the gate of ADR 0061. It is left as the input of lot O3, which
re-models the agent nodes (the occurrence, the local agent, the entity).
