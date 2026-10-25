# 0026 — RDF output is checked against the vocabularies' own files

- Status: Accepted
- Date: 2026-10-25

## Context

Three domain errors reached `main` before the 2026-10-25 review caught them by
reading: a Nomen at the end of `P1_is_identified_by` (range E41), `P190` on an
E55 Type (domain E90), and `prov:wasAssociatedWith` pointing at a Plan (range
Agent). Each was valid RDF and passed every test, because no test knew what the
vocabularies declare.

## Decision

1. `palomar.ontology` reads RDFS/OWL files (class `subClassOf`, property
   `domain`, `range`, `subPropertyOf`) and reports every triple of an output
   whose stated types do not fall under the property's domain or range, every
   untyped subject of a constrained property, every literal where a class is due
   (and the reverse), and every unknown class or property of a known namespace.
2. The check is closed-world over the output: the types an output *states* must
   imply the domain. Under bare RDFS a domain infers a type instead, which would
   have turned the Nomen into an E41 silently. This is the discipline of an
   application profile.
3. The vocabularies are the vendored CIDOC CRM 7.1.3 RDFS and LRMoo 1.0 OWL; the
   few PROV-O terms Palomar writes are transcribed from the W3C Recommendation in
   `ontology/prov-o`, since no PROV-O file is vendored.
4. `palomar.ontology-test` runs every spoke fixture through the `:ntriples`,
   `:crm`, `:crm-only` and `:prov` targets and requires zero violations, and pins
   that the checker catches the three errors above. A new class or property in an
   output must be declared by its vocabulary, or the test fails.
5. To satisfy it, PROV statements and the sources they derive from are typed
   `prov:Entity`.

## Alternatives considered

- *A SHACL engine (Jena, TopBraid)*: heavier, and the shapes would be written by
  hand; the vocabularies already declare what is checked here.
- *An RDF library as a test dependency to run RDFS inference*: inference is
  exactly what hides these errors.

## Consequences

- Linked Art JSON-LD is not checked here (it needs JSON-LD expansion against the
  Linked Art context); its schema test (`linked-art-conformance-test`) covers it.
- Every later RDF change (events, alignments, named graphs) is checked by
  construction.
