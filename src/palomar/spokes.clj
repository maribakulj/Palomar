(ns palomar.spokes
  "The source-spoke registry — the format keyword → importer plugin map shared by
   the conversion assembly (`palomar.convert`) and the validation gate
   (`palomar.validate`).

   It is deliberately lightweight: it requires only the spoke *plugins* (their
   importers and `:mapping`), not the exporters or the WEMI projection. So
   listing formats or *validating* a source — neither of which exports — does not
   drag in the whole export stack (the coupling audit point A1)."
  (:require [palomar.plugins.dc :as dc]
            [palomar.plugins.iiif :as iiif]
            [palomar.plugins.intermarc :as intermarc]
            [palomar.plugins.intermarc-ng :as intermarc-ng]
            [palomar.plugins.marc21 :as marc21]
            [palomar.plugins.mods :as mods]
            [palomar.plugins.unimarc :as unimarc]))

(def plugins
  "Source format keyword → its ADR 0007 importer plugin."
  {:intermarc    intermarc/plugin
   :intermarc-ng intermarc-ng/plugin
   :unimarc      unimarc/plugin
   :marc21       marc21/plugin
   :dc           dc/plugin
   :mods         mods/plugin
   :iiif         iiif/plugin})

(defn source-formats
  "The set of supported source format keywords."
  []
  (set (keys plugins)))

(defn plugin
  "The importer plugin for source format `from`, or nil."
  [from]
  (get plugins from))
