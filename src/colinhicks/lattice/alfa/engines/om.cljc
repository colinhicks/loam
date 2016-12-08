(ns colinhicks.lattice.alfa.engines.om
  (:require [clojure.spec :as s]
            [clojure.pprint :as pprint]
            [colinhicks.lattice.alfa.extensions :as extensions]
            [colinhicks.lattice.alfa.impl :as l #?(:clj :refer
                                                   :cljs :refer-macros) [$->]]
            [om.dom :as dom]
            [om.next :as om #?(:clj :refer
                               :cljs :refer-macros) [ui]]))

($-> colinhicks.lattice.specs
  (s/def :$/om-ui (s/and fn? om/iquery?))
  (s/def :$/impl
    (s/merge :$/base-impl
             (s/keys :req-un [:$/om-ui]
                     :opt-un [:$/depends? :$/merge-query]))))

(defn create-element
  ([tag]
   (create-element tag nil))
  ([tag opts & children]
   #?(:clj (dom/element {:tag tag
                         :attrs (dissoc opts :ref :key)
                         :react-key (:key opts)
                         :children children})
      :cljs (js/React.createElement tag opts children))))

(defmethod extensions/dom-impl :default [tag]
  {:factory (partial create-element tag)})

(defn not-implemented-ui [tag]
  (ui
    static om/IQuery
    (query [this]
      [:lattice/id])
    Object
    (render [this]
      (apply dom/div #js {:data-id (:lattice/id (om/props this))
                          :data-tag-not-implemented (str tag)}
             (om/children this)))))

(defmethod extensions/ui-impl :default [tag]
  (let [om-ui (not-implemented-ui tag)]
    {:om-ui om-ui
     :factory (om/factory om-ui)}))

(defn collect-query [nodes]
  (into []
        (map (fn [{:keys [tag opts impl]}]
               (let [{:keys [om-ui merge-query]} impl
                     q (if om-ui
                         (let [q* (om/get-query om-ui)]
                           (if merge-query
                             (merge-query q* opts)
                             q*))
                         [:lattice/id])]
                 {[(:lattice/id opts) '_] q})))
        nodes))

(defn rendering-tree [props tree]
  (map (fn [node]
         (if-not (map? node)
           node
           (let [{:keys [tag opts children impl]} node
                 {:keys [factory]} impl
                 {:keys [lattice/id]} opts]            
             (if id
               (factory #?(:clj (get props id)
                           :cljs (clj->js (get props id)))
                        (rendering-tree props children))
               (factory #?(:clj opts
                           :cljs (clj->js opts))
                        (rendering-tree props children))))))
       tree))

(defn include-dependent-keys [tx props components]
  (let [ast (om/query->ast tx)
        tx-reads (into #{}
                       (keep #(when (= :prop (:type %))
                                (:key %)))
                       (:children ast))
        dependent-reads (keep (fn [{:keys [opts impl]}]
                                (let [id (:lattice/id opts)
                                      dependent? (get impl :dependent? (constantly false))]
                                  (when (dependent? tx-reads (get props id))
                                    id)))
                              components)]
    (-> ast
        (update :children into
                (map (fn [k]
                       {:type :prop :dispatch-key k :key k})
                     dependent-reads))
        (om/ast->query))))

(defn region* [resolved-tree]
  (let [child-nodes (l/collect-ui-nodes resolved-tree)
        query (collect-query child-nodes)
        region-component
        (ui
          static om/IQuery
          (query [this]
            query)
          om/ITxIntercept
          (tx-intercept [this tx]
            (include-dependent-keys tx (om/props this) child-nodes))
          Object
          (render [this]
            (let [props (om/props this)]
              (first (rendering-tree props resolved-tree)))))]
    {:om-ui region-component
     :factory (om/factory region-component)
     :region? true
     :child-ui-nodes child-nodes}))

(defmethod extensions/region-ui-impl :lattice/region [_ node]
  (region* (:children node)))


(comment
  (def sample-1
    [:div
     [:section
      [:span {:className "label"} "Editor label"]
      [:blueprint/editor {:lattice/id ::my-editor}]
      [:div {:className "arbitrary-nesting"}
       [:blueprint/graph {:lattice/id ::my-graph}
        [:strong "nested in component"]]]]
     [:footer {:className "static"}
      [:blueprint/auditor {:lattice/id ::my-auditor}]]])

  (def sample-2
    [:blueprint/auditor {:lattice/id ::my-auditor}])

  (def sample-3
    [:main
     [:lattice/region {:lattice/id ::sample-1} sample-1]
     [:lattice/region {:lattice/id ::sample-2} sample-2]])

  (require '[colinhicks.lattice.alfa.api :as api])

  (->> sample-3 l/normalize-tree l/resolve-implementations)
  
  (->> sample-3 api/region)
  
  (->> sample-3
       (api/region {:lattice/id ::sample-3
                    :foo true})
       api/region-db)

  (->> sample-3 api/region :children (rendering-tree {}))

  (->> sample-3 api/region :impl :om-ui om/get-query)
  
  (-> sample-3 api/region :impl :factory (as-> f (dom/render-to-str (f))))

  (defmethod extensions/ui-impl :blueprint/auditor [tag]
    (let [om-ui (not-implemented-ui tag)]
      {:dependent? (fn [ks props] (some ks [::my-editor]))
       :om-ui om-ui
       :factory (om/factory om-ui)}))

  (include-dependent-keys
   '[(foo! {:bar false}) ::my-editor]
   {}
   (->> sample-1 api/region :impl :child-ui-nodes))
 )
