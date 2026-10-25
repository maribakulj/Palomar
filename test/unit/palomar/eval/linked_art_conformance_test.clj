(ns palomar.eval.linked-art-conformance-test
  "Linked Art conformance (DoD #4) — the REAL draft-2020-12 validator, not the old
   root-only check. Uses `com.networknt/json-schema-validator` (a test-only Maven
   dependency) against the **official** Linked Art json-validator schema set
   (`test/fixtures/conformance/linked-art/schema/`, committed verbatim), with all
   14 schemas preloaded so the cross-file `$ref`s (`object.json` → `core.json` …)
   resolve offline.

   ## The honest calibration: the official schema is *stricter than real Linked Art*

   The json-validator schema is draft-2020-12 with `additionalProperties:false`
   throughout, and it models `carries` / `part_of` / `member_of` as **id-only
   references**. Real Linked Art — including Getty's own published examples —
   *embeds* fuller objects there. So the schema does **not** accept all valid Linked
   Art: validating Getty's own **Mona Lisa** example against it yields errors
   (`additionalProperties` on `notation`/`language`, a `const` type mismatch, a
   missing `id` on a ref). \"Validates strictly against this schema\" is therefore a
   *stronger* bar than \"is valid Linked Art\". This eval asserts that failure
   explicitly (calibration, not hidden) so the result below is read correctly.

   ## What we prove about our output

   A Manifestation is exported as a `LinguisticObject` (the text of an edition;
   only an F5 Item is a `HumanMadeObject`, audit S01), so its documents are
   validated against `text.json`, and Item documents against `object.json`.
   Against that strict bar:
   - `convert :marc21 … :linked-art` (the LoC sample) validates with **zero
     errors** — every doc a `LinguisticObject`, every property allowed and typed;
   - a richer record (creator, publisher, Work) deviates in exactly two known
     ways, asserted below: (1) a Person or Group with no authority id lacks the
     `id` the schema requires of a reference — the canonical floor carries no
     authority for that agent, and minting one would fabricate an identity; and
     (2) `part_of` → `PropositionalObject`, which Linked Art's own published
     example uses (`example/text/koot_nightwatch/1`) although its schema only
     allows a LinguisticObject or VisualItem there;
   - an Item document validates against `object.json` with zero errors.

   ## One documented normalization (applied at load, fixtures stay pristine)

   LA's `core.json` declares draft-2020-12 but uses the draft-7 `items:[…]` tuple
   form in `ContextStringOrArray`; under a 2020-12 validator that fails to compile.
   `normalize-2020-12` rewrites that one keyword to `prefixItems` (the exact 2020-12
   equivalent — identical syntax and semantics) at load time. The committed schema
   files are byte-for-byte upstream; the normalization lives here, in code, and the
   affected branch is not exercised by our output or the Mona Lisa (both use the
   string `@context`) anyway."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [palomar.convert :as convert]
            [palomar.plugins.lrmoo.linked-art :as linked-art])
  (:import [com.networknt.schema SchemaLocation SchemaRegistry SpecificationVersion]
           [com.networknt.schema.serialization JsonMapperFactory]
           [java.util HashMap]
           [java.util.function Consumer]))

(def ^:private schema-dir "test/fixtures/conformance/linked-art/schema/")
(def ^:private iri-base "https://linked.art/api/1.0/schema/")

(defn- normalize-2020-12
  "Rewrite the draft-7 `items:[…]` tuple to the draft-2020-12 `prefixItems:[…]`
   (identical syntax + semantics). LA's core.json declares 2020-12 but ships the
   draft-7 form in ContextStringOrArray; without this it will not compile under a
   strict 2020-12 validator. See ns doc."
  [s]
  (str/replace s #"\"items\"(\s*):(\s*)\[" "\"prefixItems\"$1:$2["))

(def ^:private registry
  ;; All schemas preloaded under their filename IRI (what `$ref`s resolve to), so
  ;; the cross-file references resolve with no network. Keyed by filename, not the
  ;; declared `$id`, which is deterministic and sidesteps an upstream `$id`
  ;; collision (abstract.json mis-declares text.json's `$id`).
  (delay
    (let [m (HashMap.)]
      (doseq [f (.listFiles (io/file schema-dir))
              :when (str/ends-with? (.getName f) ".json")]
        (.put m (str iri-base (.getName f)) (normalize-2020-12 (slurp f))))
      (SchemaRegistry/withDefaultDialect
       SpecificationVersion/DRAFT_2020_12
       (reify Consumer (accept [_ b] (.schemas b m)))))))

(def ^:private object-schema
  (delay (.getSchema @registry (SchemaLocation/of (str iri-base "object.json")))))

(def ^:private text-schema
  (delay (.getSchema @registry (SchemaLocation/of (str iri-base "text.json")))))

(def ^:private mapper (delay (JsonMapperFactory/getInstance)))

(defn- errors-against
  "Validate a JSON string against `schema`. Returns `[{:key message-key :at
   instance-location} …]`."
  [schema json-str]
  (->> (.validate @schema (.readTree @mapper json-str))
       (mapv (fn [e] {:key (.getMessageKey e) :at (str (.getInstanceLocation e))}))))

(defn- errors
  "Validate a JSON string against the Linked Art `object.json` schema."
  [json-str]
  (errors-against object-schema json-str))

(defn- text-errors
  "Validate a JSON string against the Linked Art `text.json` schema."
  [json-str]
  (errors-against text-schema json-str))

(defn- root-errors [errs] (filter #(= "" (:at %)) errs))

(def ^:private mona
  (delay (slurp "test/fixtures/conformance/linked-art/example-object-mona-lisa.json")))

(def ^:private our-docs
  (delay (->> (:output (convert/convert {:from   :marc21
                                         :to     :linked-art
                                         :source (slurp "test/fixtures/documentary/marc21/marcxml/loc_collection.xml")}))
              str/split-lines
              (remove str/blank?))))

(deftest the-deep-validator-runs-and-resolves-cross-file-refs
  (testing "the schema compiles with all $refs preloaded, and validation actually runs"
    (is (some? @object-schema)))
  (testing "a wrong root type is caught (the validator is real, not a no-op)"
    (let [errs (errors "{\"@context\":\"https://linked.art/ns/v1/linked-art.json\",\"id\":\"https://x/1\",\"type\":\"NotAType\",\"_label\":\"y\"}")]
      (is (some #(= "const" (:key %)) errs))))                       ; type must be the const "HumanMadeObject"
  (testing "ref-resolution is exercised: the Mona Lisa's errors are in nested objects (not the root)"
    (is (seq (remove #(= "" (:at %)) (errors @mona))))))            ; nested errors ⇒ object.json followed its $refs

(deftest the-official-schema-is-stricter-than-real-linked-art
  (testing "Getty's own Mona Lisa example does NOT strictly validate — the schema is stricter than real LA"
    (let [errs (errors @mona)
          kinds (set (map :key errs))]
      (is (seq errs))                                               ; > 0 errors on the canonical example
      (is (contains? kinds "additionalProperties"))                 ; notation/language rejected
      (is (contains? kinds "const"))                                ; a nested type mismatch
      (testing "but its root object is fine — the strictness bites only nested/embedded objects"
        (is (empty? (root-errors errs)))))))

(deftest our-marc-output-validates-strictly
  (testing "every convert :marc21 → :linked-art doc is a LinguisticObject with zero schema errors"
    (is (seq @our-docs))
    (doseq [d @our-docs]
      (is (str/includes? d "\"type\":\"LinguisticObject\""))
      (is (empty? (text-errors d)) (str "unexpected error: " (text-errors d))))))

(def ^:private rich-doc
  (delay (:output (convert/convert
                   {:from :dc :to :linked-art :opts {:record-id :dc/x}
                    :source (str "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                                 "<dc:title>Les Misérables</dc:title><dc:creator>Victor Hugo</dc:creator>"
                                 "<dc:publisher>Gallimard</dc:publisher><dc:description>n</dc:description>"
                                 "</metadata>")}))))

(deftest a-rich-record-deviates-only-in-the-two-known-ways
  (let [errs (text-errors @rich-doc)]
    (is (seq errs))                                                ; the bar is not vacuous
    (testing "no error at the root"
      (is (empty? (root-errors errs))))
    (testing "every error sits on an agent reference without an id, or on part_of"
      (is (every? #(or (re-find #"^/(created_by|used_for/0)/carried_out_by/0" (:at %))
                       (str/starts-with? (:at %) "/part_of/0"))
                  errs)
          (str "an unexpected error location: " errs)))
    (testing "the agent errors are a missing id (or the Person/Group alternative), the part_of one a type const"
      (is (every? #{"required" "const"} (map :key errs))))))

(deftest an-item-document-validates-as-a-human-made-object
  (testing "an F5 Item is a HumanMadeObject that carries the text, with zero schema errors"
    (let [rec {:id :record/g :kind :lrmoo/graph
               :entities [{:id :ent/m :kind :lrmoo/F3_Manifestation :iri "http://ex/m"}
                          {:id :ent/i :kind :lrmoo/F5_Item :iri "http://ex/i"}]
               :assertions [{:subject :ent/i :predicate :lrmoo/R7_exemplifies
                             :value {:value/kind :reference :value/target :ent/m}}
                            {:subject :ent/m :predicate :lrmoo/R33_has_string :value "T"}]}
          item (last (str/split-lines (:output (linked-art/exporter {} [rec]))))]
      (is (str/includes? item "\"type\":\"HumanMadeObject\""))
      (is (empty? (errors item)) (str "unexpected error: " (errors item))))))
