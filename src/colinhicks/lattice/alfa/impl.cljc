(ns colinhicks.lattice.alfa.impl
  (:require [clojure.spec :as s]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [colinhicks.lattice.alfa.extensions :as extensions]))


(defmacro $-> [ns & forms]
  "In forms' keywords, replace placeholder $ with the supplied ns.
   ($-> foo.bar {:$/baz true}) ; => {:foo.bar/baz true}"
  (cons 'do
        (walk/postwalk
         (fn [x]
           (if (and (keyword? x)
                    (str/starts-with? (str x) ":$/"))
             (keyword (str ns "/" (name x)))
             x))
         forms)))

($-> colinhicks.lattice.specs
  (s/def :$/tag keyword?)
  (s/def :$/opts (s/? (s/map-of keyword? any? :conform-keys true)))
  (s/def :$/children (s/* #(or (string? %)
                               (s/coll-of :$/tree))))
  (s/def :$/tree
    (s/cat :tag :$/tag
           :opts :$/opts
           :children :$/children))
  (s/def :$/tree-node-unresolved (s/keys :req-un [:$/tag :$/opts :$/children]))
  (s/def :$/factory fn?)
  (s/def :$/depends? fn?)
  (s/def :$/merge-query fn?)
  (s/def :$/region? boolean?)
  (s/def :$/dom-impl (s/keys :req-un [:$/factory]))
  (s/def :$/ui-impl (s/merge :$/dom-impl))
  (s/def :$/tree-node-resolved (s/keys :req-un [:$/tag
                                                :$/opts
                                                :$/children
                                                :$/ui-impl]))

  (s/def :$/region-ui-impl
    (s/merge :$/ui-impl
             (s/keys :req-un [:$/region? :$/child-ui-nodes :$/tree])))

  (s/fdef extensions/ui-impl
    :args (s/cat :tag :$/tag)
    :ret :$/ui-impl))

(defn ui-tag? [tag]
  (boolean (namespace tag)))

(defmethod extensions/region-ui-impl :default [tag _]
  (extensions/ui-impl tag))

(defn normalize-tree [raw-tree]
  (map (fn [node]
         (if (string? node)
           node
           (let [[tag maybe-opts & children] node
                 opts (when (map? maybe-opts) maybe-opts)
                 children' (if-not opts
                             (into [maybe-opts] children)
                             children)]
             {:tag tag
              :opts opts
              :children (mapcat normalize-tree children')})))
       [raw-tree]))

(defn resolve-implementations [tree]
  (map #(walk/postwalk
         (fn [node]
           (if-let [tag (and (map? node)
                             (:tag node))]
             (if-not (ui-tag? tag)
               (assoc node :ui-impl (extensions/dom-impl tag))
               (assoc node :ui-impl (extensions/region-ui-impl tag node)))
             node))
         %)
       tree))

(defn collect-ui-nodes [tree]
  (->> tree
       (mapcat (fn [node]
                 (tree-seq #(not (get-in % [:ui-impl :region?]))
                           :children
                           node)))
       (filter #(when-let [tag (:tag %)]
                  (ui-tag? tag)))))

