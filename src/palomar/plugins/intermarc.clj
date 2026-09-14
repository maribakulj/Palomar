(ns palomar.plugins.intermarc
  "INTERMARC-SRU importer (WP-4, ADR 0007).

   Parses BnF InterMARCXChange — the MARCXChange XML the BnF SRU API returns —
   into IR records carrying native `:intermarc/*` assertions. A focused parser
   (the structure is regular and known) rather than the generic shape adapter.

   Mapping:
   - one `<mxc:record>` → one Record, id `:bnf/<cb-number>` from its ARK,
     `:kind :intermarc/<type>` (e.g. `:intermarc/bibliographic`);
   - each `<controlfield tag=\"T\">` → `:intermarc/fT` = text;
   - each `<subfield code=\"C\">` of `<datafield tag=\"T\">` →
     `:intermarc/fT_C` = text (repeatable: one assertion per occurrence).

   Tags and subfield codes are numeric, so predicate *names* are prefixed `f`
   (`:intermarc/f145_3`): a keyword name may not start with a digit, and an
   un-prefixed `:intermarc/145_3` would not round-trip through EDN (ADR 0001).
   This subfield granularity is exactly what WEMI derivation reads — `f145_3` is the
   embedded Work-authority link, `f100_3` the author's, `f245_a` the title.

   Like every plugin (ADR 0007) this exposes an `:importer` closure; the core
   never sees `:intermarc/*`. Indicators and the leader are not yet captured
   (a later refinement).

   The MARCXML field/subfield parse lives in `palomar.plugins.marcxml` (shared
   with the MARC21 spoke); this namespace supplies only the INTERMARC policies:
   a record is an `<mxc:record>` (a `record` carrying the `id` ARK, vs the SRU
   `<srw:record>` wrapper that has none), its id is `:bnf/<cb-number>`, and its
   `:kind` comes from the `type` attribute."
  (:require [palomar.plugins.marcxml :as marcxml]))

;; ---------------------------------------------------------------------------
;; Public API + plugin
;; ---------------------------------------------------------------------------

(defn- policies
  "The MARCXChange family policies for the INTERMARC spoke (shared by
   `ingest`/`stream`); INTERMARC and UNIMARC differ only in the `ns`."
  [opts]
  (marcxml/mxc-policies "intermarc" opts))

(defn ingest
  "Parse an InterMARCXChange `xml-string` into a vector of Records carrying native
   `:intermarc/*` assertions. `opts` may carry `:kind` (else derived from each
   record's `type`). The controlled author is recombined from the 100 subfields by
   the `:map/intermarc-agent` combine mapping, not by a pre-pass."
  [xml-string opts]
  (marcxml/parse-records xml-string (policies opts)))

(defn stream
  "Streaming importer (WP-7): a **lazy** record seq from a MARCXChange `readable`
   (a flat `<mxc:collection>` dump; SRU pages are small and use `ingest`). The
   caller manages the reader and consumes lazily — bounded (`docs/eval/scale.md`)."
  [opts readable]
  (marcxml/stream-records readable (policies opts)))

(defn- source->string
  "Accept either a raw string or an ADR 0007 tagged source map."
  [source]
  (cond
    (string? source) source
    (map? source)    (case (:source/kind source)
                       :string (:source/value source)
                       :file   (slurp (:source/value source))
                       (throw (ex-info "Unsupported source kind for INTERMARC"
                                       {:kind (:source/kind source)})))
    :else (throw (ex-info "INTERMARC importer expects a string or tagged source"
                          {:source source}))))

(defn importer
  "ADR 0007 importer: `(fn [opts source] -> {:records [...] :diagnostics []})`."
  [opts source]
  {:records (ingest (source->string source) opts)
   :diagnostics []})

(def mapping
  "INTERMARC→canonical mapping for the bibliographic core. INTERMARC reaches the
   WEMI pivot through the *enriched* `derive-wemi` rung (the 145 $3 authority link),
   not the floor projection — but populating `:canon/*` too lets the round-trip
   exporters (DC, MARC21) and the Linked Art creator read off it. The agent is the
   *controlled* 100 main entry, **recombined declaratively** from its subfields —
   `$a` (surname) + `$m` (forename), catalog form \"Surname, Forename\" — by a
   `:mapping/combine` rule, not the transcribed 245 $f responsibility statement
   (which can be compound — multiple names and roles). The 100 authority link
   (BnF id / ISNI) is not carried: the floor is string-only (ADR 0003); that
   identity lives on the enriched `derive-wemi` rung."
  [{:mapping/id :map/intermarc-title :mapping/from :intermarc/f245_a :mapping/to :canon/title
    :mapping/transform [:trim]}
   {:mapping/id :map/intermarc-agent
    :mapping/from [:intermarc/f100_a :intermarc/f100_m] :mapping/combine ", "
    :mapping/to :canon/agent :mapping/transform [:trim]}
   {:mapping/id :map/intermarc-date :mapping/from :intermarc/f260_d :mapping/to :canon/date
    :mapping/transform [:trim]}
   {:mapping/id :map/intermarc-control :mapping/from :intermarc/f001 :mapping/to :canon/identifier
    :mapping/transform [:trim]}
   {:mapping/id :map/intermarc-ark :mapping/from :intermarc/f003 :mapping/to :canon/identifier
    :mapping/transform [:trim]}])

(def plugin
  "The INTERMARC-SRU importer plugin (ADR 0007). Ships `:mapping` (used to populate
   the canonical floor alongside `derive-wemi`'s enriched WEMI) and the `:importer`."
  {:plugin/spec-version 1
   :id                  :palomar/intermarc
   :input-format        :xml
   :mapping             mapping
   :importer            importer
   :stream-importer     stream                          ; WP-7 lazy record seq from a Reader
   :doc                 "INTERMARC-SRU (InterMARCXChange) importer — fields/subfields as :intermarc/* assertions; bibliographic core mapped to the canonical floor."})
