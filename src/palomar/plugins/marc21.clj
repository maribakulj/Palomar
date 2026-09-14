(ns palomar.plugins.marc21
  "MARC21 (MARCXML / MARC21slim) importer spoke (WP-4, ADR 0007/0009).

   The institutional headline format. Built on the shared `palomar.plugins.marcxml`
   core (same parse as INTERMARC), so a `<record>` of leader/controlfield/datafield
   becomes native `:marc21/f*` assertions; a declarative MARC21→canonical mapping
   (ADR 0009) then renames the bibliographic subset onto the canonical floor at
   `:normalize`, and the generic projection (ADR 0013) gives it a WEMI view.

   ## Ingest is lossless; loss surfaces downstream

   Unlike the mapping-driven shape adapter (the Dublin Core spoke), this focused
   parser captures **every** field as `:marc21/f*` — nothing is dropped at ingest.
   The mapping renames the modelled subset to `:canon/*`; the rest (subjects 6xx,
   physical description 300, cataloguing source 040, …) stay on the record as
   native assertions and are reported as loss at the projection edge (canonical
   fields with no WEMI home) and the export edge (predicates a target cannot
   express). So MARC21, like INTERMARC, is loss-aware by *retention* — nothing
   silently vanishes — where Dublin Core is loss-aware by *report-at-ingest*.

   ## Mapping to the canonical floor (the modelled subset)

   | MARC21                                  | canonical            |
   |-----------------------------------------|----------------------|
   | `245 $a`                                | `:canon/title`       |
   | `240 $a`                                | `:canon/uniform-title` |
   | `100/110/111 $a`, `700/710 $a`          | `:canon/agent`       |
   | `260/264 $c`                            | `:canon/date`        |
   | `010/020/022/035 $a`                    | `:canon/identifier`  |
   | `856 $u`                                | `:canon/digital-object` |
   | `500/505/511/520 $a`                    | `:canon/note`        |

   Every rule trims (MARC subfields can carry significant padding, e.g. the `010`
   LCCN). The richer `100 $3`-style authority WEMI derivation INTERMARC enjoys has no
   MARC21 equivalent here, so MARC21 takes the floor projection (string-key
   identity), not the enriched one.

   ## Scope (V1)

   - One Record per `<record>`; id `:marc/r<001>` from the control number.
   - Import only; a canonical→MARC21 exporter is a later slice.
   - Leader/008 fixed-field decoding and indicator semantics are out of scope."
  (:require [clojure.string :as str]
            [palomar.plugins.marcxml :as marcxml]))

;; ---------------------------------------------------------------------------
;; Record identity
;; ---------------------------------------------------------------------------

(defn- record-id-from-control
  "`:marc/r<sanitised-001>` from the control number. The `r` prefix guards
   against a digit-leading keyword name (an EDN round-trip hazard, ADR 0001);
   non-alphanumerics in the control number (e.g. a space in \"CF 91000008\")
   collapse to `-`."
  [control-number]
  (let [clean (-> (str control-number) str/trim (str/replace #"[^A-Za-z0-9]+" "-"))]
    (keyword "marc" (str "r" clean))))

(defn- record-id [elem]
  (if-let [cn (marcxml/control-value elem "001")]
    (record-id-from-control cn)
    (throw (ex-info "MARC21 record has no 001 control number to derive an id from"
                    {:record-local-name (marcxml/local-name elem)}))))

;; ---------------------------------------------------------------------------
;; MARC21 → canonical mapping (ADR 0009)
;; ---------------------------------------------------------------------------

(def mapping
  "Declarative MARC21→canonical mapping for the modelled bibliographic subset.
   Several MARC fields collapse onto one canonical predicate (the agent roles,
   the identifier flavours); that under-specification is the floor's, surfaced as
   loss when the pivot is projected to a role-aware target."
  [{:mapping/id :map/marc21-title :mapping/from :marc21/f245_a :mapping/to :canon/title
    :mapping/transform [:trim]}
   {:mapping/id :map/marc21-uniform-240 :mapping/from :marc21/f240_a :mapping/to :canon/uniform-title
    :mapping/transform [:trim]}
   {:mapping/id :map/marc21-author-100 :mapping/from :marc21/f100_a :mapping/to :canon/agent
    :mapping/transform [:trim]}
   {:mapping/id :map/marc21-corporate-110 :mapping/from :marc21/f110_a :mapping/to :canon/agent
    :mapping/transform [:trim]}
   {:mapping/id :map/marc21-meeting-111 :mapping/from :marc21/f111_a :mapping/to :canon/agent
    :mapping/transform [:trim]}
   {:mapping/id :map/marc21-added-700 :mapping/from :marc21/f700_a :mapping/to :canon/agent
    :mapping/transform [:trim]}
   {:mapping/id :map/marc21-added-corporate-710 :mapping/from :marc21/f710_a :mapping/to :canon/agent
    :mapping/transform [:trim]}
   {:mapping/id :map/marc21-date-260 :mapping/from :marc21/f260_c :mapping/to :canon/date
    :mapping/transform [:trim]}
   {:mapping/id :map/marc21-date-264 :mapping/from :marc21/f264_c :mapping/to :canon/date
    :mapping/transform [:trim]}
   {:mapping/id :map/marc21-lccn-010 :mapping/from :marc21/f010_a :mapping/to :canon/identifier
    :mapping/transform [:trim]}
   {:mapping/id :map/marc21-isbn-020 :mapping/from :marc21/f020_a :mapping/to :canon/identifier
    :mapping/transform [:trim]}
   {:mapping/id :map/marc21-issn-022 :mapping/from :marc21/f022_a :mapping/to :canon/identifier
    :mapping/transform [:trim]}
   {:mapping/id :map/marc21-sysnum-035 :mapping/from :marc21/f035_a :mapping/to :canon/identifier
    :mapping/transform [:trim]}
   {:mapping/id :map/marc21-url-856 :mapping/from :marc21/f856_u :mapping/to :canon/digital-object
    :mapping/transform [:trim]}
   {:mapping/id :map/marc21-note-500 :mapping/from :marc21/f500_a :mapping/to :canon/note
    :mapping/transform [:trim]}
   {:mapping/id :map/marc21-contents-505 :mapping/from :marc21/f505_a :mapping/to :canon/note
    :mapping/transform [:trim]}
   {:mapping/id :map/marc21-credits-511 :mapping/from :marc21/f511_a :mapping/to :canon/note
    :mapping/transform [:trim]}
   {:mapping/id :map/marc21-summary-520 :mapping/from :marc21/f520_a :mapping/to :canon/note
    :mapping/transform [:trim]}])

;; ---------------------------------------------------------------------------
;; Importer (ADR 0007)
;; ---------------------------------------------------------------------------

(defn- source->string
  "Accept either a raw string or an ADR 0007 tagged source map."
  [source]
  (cond
    (string? source) source
    (map? source)    (case (:source/kind source)
                       :string (:source/value source)
                       :file   (slurp (:source/value source))
                       (throw (ex-info "Unsupported source kind for MARC21"
                                       {:kind (:source/kind source)})))
    :else (throw (ex-info "MARC21 importer expects a string or tagged source"
                          {:source source}))))

(defn- policies
  "The MARCXML family policies for the MARC21 spoke (shared by `ingest`/`stream`)."
  [opts]
  {:ns        "marc21"
   :record?   (fn [e] (= "record" (marcxml/local-name e)))
   :record-id record-id
   :kind      (fn [_] (or (:kind opts) :marc21/bibliographic))
   :source    (fn [e] (marcxml/control-value e "001"))})

(defn ingest
  "Parse a MARC21slim `xml-string` (a `<collection>` of `<record>`s, or a bare
   `<record>`) into a vector of Records carrying native `:marc21/*` assertions.
   `opts` may carry `:kind` (default `:marc21/bibliographic`)."
  [xml-string opts]
  (marcxml/parse-records xml-string (policies opts)))

(defn stream
  "Streaming importer (WP-7): a **lazy** record seq from a MARCXML `readable`
   (a flat `<collection>` dump). The caller manages the reader (`with-open`) and
   consumes lazily — bounded memory (`docs/eval/scale.md`)."
  [opts readable]
  (marcxml/stream-records readable (policies opts)))

(defn importer
  "ADR 0007 importer: `(fn [opts source] -> {:records [...] :diagnostics []})`.
   Ingest is lossless (every field retained as `:marc21/*`); loss is reported by
   the downstream projection/export, not here."
  [opts source]
  {:records     (ingest (source->string source) opts)
   :diagnostics []})

(def plugin
  "The MARC21 (MARCXML) importer plugin (ADR 0007). Exposes `:mapping` as data
   (compiled to `:normalize` rules) and the `:importer` closure."
  {:plugin/spec-version 1
   :id                  :palomar/marc21
   :input-format        :xml
   :mapping             mapping
   :importer            importer
   :stream-importer     stream                          ; WP-7 lazy record seq from a Reader
   :doc                 "MARC21 (MARCXML) importer — fields/subfields as :marc21/* assertions; bibliographic subset mapped to the canonical floor."})
