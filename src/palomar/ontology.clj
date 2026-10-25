(ns palomar.ontology
  "Domain and range checking of RDF output against the published vocabularies
   it uses (audit plan step 2).

   An exporter can write a triple that parses yet says something its vocabulary
   forbids: a Nomen at the end of `P1_is_identified_by` (whose range is E41), a
   `P190_has_symbolic_content` on a Type (its domain is E90). Such triples are
   valid RDF and wrong CRM; no syntax check catches them. This namespace reads
   the vocabularies' own RDFS/OWL files — the CIDOC CRM 7.1.3 RDFS and the
   LRMoo 1.0 OWL are vendored under `test/fixtures` — and checks a triple seq
   against them: each typed subject must fall under the property's domain, each
   object under its range, and each class an output names must exist.

   It reads subclass closure only, which is all the CRM family declares about
   domains and ranges; no OWL restriction is read. The check is stricter than
   RDFS, where a domain is a rule that *infers* the subject's type: here the
   types an output states must already imply it. That is the discipline of an
   application profile, and the one that caught the errors above — under bare
   RDFS inference a Nomen at the end of P1 would simply have become an E41.
   It is plain data in, plain data out (`schema`, `violations`), so an
   application can check its own output, or a plugin's, the same way."
  (:require [clojure.string :as str]
            [palomar.xml :as rx]))

(def rdf-type "http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
(def ^:private rdfs-literal "http://www.w3.org/2000/01/rdf-schema#Literal")

(defn- local [k] (when k (name k)))

(defn- attr
  "The value of the attribute whose local name is `nm`."
  [e nm]
  (some (fn [[k v]] (when (= nm (local k)) v)) (:attrs e)))

(defn- resolve-iri
  "`ref` against `base`: an absolute IRI passes, a bare local name is appended."
  [base ref]
  (when ref (if (re-find #"^[A-Za-z][A-Za-z0-9+.-]*:" ref) ref (str base ref))))

(def ^:private class-tags #{"Class"})
(def ^:private property-tags #{"Property" "ObjectProperty" "DatatypeProperty"})

(defn- read-file
  "The declarations of one RDFS/OWL file: `{:classes {iri #{super…}}
   :properties {iri {:domain d :range r :super #{…}}}}`."
  [path]
  (let [root (rx/parse-str (str/replace (slurp path) #"^\uFEFF" ""))   ; the CRM RDFS starts with a BOM
        base (attr root "base")
        refs (fn [e tag] (set (keep #(when (= tag (local (:tag %)))
                                       (resolve-iri base (attr % "resource")))
                                    (filter map? (:content e)))))
        one  (fn [e tag] (first (refs e tag)))]
    (reduce (fn [acc e]
              (let [tag (local (:tag e))
                    iri (resolve-iri base (attr e "about"))]
                (cond
                  (and iri (class-tags tag))
                  (update-in acc [:classes iri] (fnil into #{}) (refs e "subClassOf"))

                  (and iri (property-tags tag))
                  (assoc-in acc [:properties iri] {:domain (one e "domain")
                                                   :range  (one e "range")
                                                   :super  (refs e "subPropertyOf")})
                  :else acc)))
            {:classes {} :properties {}}
            (filter map? (:content root)))))

(defn schema
  "The merged declarations of the RDFS/OWL files at `paths`."
  [paths]
  (reduce (fn [a b] {:classes    (merge-with into (:classes a) (:classes b))
                     :properties (merge (:properties a) (:properties b))})
          {:classes {} :properties {}}
          (map read-file paths)))

(def vendored
  "The vocabularies Palomar's RDF outputs use, as vendored in the repository."
  ["test/fixtures/documentary/cidoc-crm/CIDOC_CRM_v7.1.3.rdf"
   "test/fixtures/documentary/lrm/lrmoo/LRMoo_v1.0.owl"])

(def ^:private prov "http://www.w3.org/ns/prov#")

(def prov-o
  "The PROV-O terms Palomar writes, transcribed from the W3C Recommendation
   (PROV-O, 30 April 2013, §4.1 and §4.2: classes, domains and ranges). No
   PROV-O file is vendored, and these are the only terms the `:prov` target
   uses; a term added there must be added here, or it is reported unknown."
  {:classes    {(str prov "Entity")        #{}
                (str prov "Activity")      #{}
                (str prov "Agent")         #{}
                (str prov "SoftwareAgent") #{(str prov "Agent")}
                (str prov "Plan")          #{(str prov "Entity")}
                (str prov "Influence")     #{}
                (str prov "AgentInfluence") #{(str prov "Influence")}
                (str prov "Association")   #{(str prov "AgentInfluence")}}
   :properties {(str prov "wasGeneratedBy")       {:domain (str prov "Entity") :range (str prov "Activity")}
                (str prov "wasDerivedFrom")       {:domain (str prov "Entity") :range (str prov "Entity")}
                (str prov "wasAssociatedWith")    {:domain (str prov "Activity") :range (str prov "Agent")}
                (str prov "qualifiedAssociation") {:domain (str prov "Activity") :range (str prov "Association")}
                (str prov "agent")                {:domain (str prov "AgentInfluence") :range (str prov "Agent")}
                (str prov "hadPlan")              {:domain (str prov "Association") :range (str prov "Plan")}
                (str prov "used")                 {:domain (str prov "Activity") :range (str prov "Entity")}
                (str prov "wasInvalidatedBy")     {:domain (str prov "Entity") :range (str prov "Activity")}
                (str prov "wasRevisionOf")        {:domain (str prov "Entity") :range (str prov "Entity")}
                (str prov "endedAtTime")          {:domain (str prov "Activity")
                                                   :range  "http://www.w3.org/2001/XMLSchema#dateTime"}}})

(defn with
  "`sch` with the declarations of `more` (same shape as `schema`'s result) added."
  [sch more]
  {:classes    (merge-with into (:classes sch) (:classes more))
   :properties (merge (:properties sch) (:properties more))})

(defn- super-closure
  "`class` and every class it falls under, by transitive `subClassOf`."
  [sch class]
  (loop [todo [class] seen #{}]
    (if-let [[c & more] (seq todo)]
      (if (seen c) (recur more seen) (recur (into (vec more) (get-in sch [:classes c])) (conj seen c)))
      seen)))

(defn- namespace-of [iri] (second (re-find #"^(.*[/#])[^/#]*$" (str iri))))

(defn violations
  "Every way `triples` (`[s p o]`, o = `{:iri}` | `{:lit …}`) contradicts schema
   `sch`, as `{:triple t :problem kw :expected iri}`. Only properties and classes
   of a namespace `sch` declares are checked; anything else is left alone.

   - `:unknown-class` — an `rdf:type` to a class of a known namespace that it
     does not declare (a typo, or a class of another version);
   - `:unknown-property` — likewise for a predicate;
   - `:domain` — the subject has types, and none falls under the domain;
   - `:range` — an IRI object has types and none falls under the range, or a
     literal stands where a class is expected, or an IRI where a literal is;
   - `:untyped` — the subject (or an IRI object of a property with a class range)
     has no type in `triples`, so nothing says it may carry the property."
  [sch triples]
  (let [known-ns (into #{} (map namespace-of) (concat (keys (:classes sch)) (keys (:properties sch))))
        types    (reduce (fn [m [s p o]] (if (and (= p rdf-type) (:iri o)) (update m s (fnil conj #{}) (:iri o)) m))
                         {} triples)
        under?   (fn [node class] (some #(contains? (super-closure sch %) class) (types node)))
        literal? (fn [r] (or (= r rdfs-literal) (str/starts-with? (str r) "http://www.w3.org/2001/XMLSchema#")))]
    (vec
     (for [[s p o :as t] triples
           v (cond
               (= p rdf-type)
               (when (and (known-ns (namespace-of (:iri o))) (not (contains? (:classes sch) (:iri o))))
                 [{:triple t :problem :unknown-class}])

               (not (known-ns (namespace-of p))) nil

               (not (contains? (:properties sch) p))
               [{:triple t :problem :unknown-property}]

               :else
               (let [{:keys [domain range]} (get-in sch [:properties p])]
                 (concat
                  (when domain
                    (cond (empty? (types s)) [{:triple t :problem :untyped :expected domain}]
                          (not (under? s domain)) [{:triple t :problem :domain :expected domain}]))
                  (when range
                    (cond
                      (literal? range) (when (:iri o) [{:triple t :problem :range :expected range}])
                      (:lit o)         [{:triple t :problem :range :expected range}]
                      (empty? (types (:iri o))) [{:triple t :problem :untyped :expected range}]
                      (not (under? (:iri o) range)) [{:triple t :problem :range :expected range}])))))]
       v))))
