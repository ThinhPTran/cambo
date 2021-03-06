(ns cambo.model
  (:refer-clojure :exclude [get set force])
  (:require [cambo.core :as core]
            [cambo.graph :as graph]))

(defrecord Model
  [cache datasource subscribers])

(defn model [{:keys [cache] :as opts}]
  (map->Model (assoc opts :cache (atom (->> [(or cache {})]
                                            (graph/set {})
                                            :graph))
                          :subscribers (atom {}))))

(defn with-path-info [model]
  (assoc model :path-info true))

(defn get-options
  ([model]
    (get-options model nil))
  ([model opts]
    (merge (select-keys model [:path-info :boxed])
           {:normalize false}
           opts)))

(defn get-cache
  ([{:keys [cache]}]
   @cache)
  ([{:keys [cache] :as m} pathsets]
   (let [c @cache]
     (:graph (graph/get c pathsets (get-options m))))))

(defn pull-cache
  [{:keys [cache] :as m} query]
  (let [c @cache]
    (:graph (graph/pull c query (get-options m)))))

(defn publish [{:keys [subscribers]}]
  (let [subs @subscribers]
    (doseq [[_ cb] subs]
      (cb))))

(defn unsubscribe [{:keys [subscribers]} id]
  (swap! subscribers dissoc id)
  nil)

(def subscriber-id (atom 0))

(defn subscribe [{:keys [subscribers] :as model} pathsets cb]
  (let [id (swap! subscriber-id inc)]
    (swap! subscribers assoc id cb)
    (fn []
      (unsubscribe model id))))

(defn set-cache*
  [{:keys [cache] :as m} pathmaps]
  (swap! cache (fn [cache]
                 (let [{:keys [graph]} (graph/set cache pathmaps)]
                   graph)))
  (publish m)
  nil)

(defn set-cache
  [{:keys [cache] :as m} pathmaps]
  #?(:cljs (let [ps (atom nil)
                 c (swap! cache (fn [cache]
                                  (let [{:keys [graph paths]} (graph/set cache pathmaps)]
                                    (reset! ps paths)
                                    graph)))]
             (publish m)
             (:graph (graph/get c @ps {:normalize false})))
     :clj (with-local-vars [ps nil]
            (let [c (swap! cache (fn [cache]
                                   (let [{:keys [graph paths]} (graph/set cache pathmaps)]
                                     (var-set ps paths)
                                     graph)))]
              (publish m)
              (:graph (graph/get c @ps {:normalize false}))))))

(defn invalidate-cache
  [{:keys [cache]} paths]
  (swap! cache (fn [cache]
                 (:graph (graph/invalidate cache paths))))
  paths)

(defn pull
  #?(:clj ([model query]
           (pull model query (fn [_]))))
  ([{:keys [cache datasource] :as m} query cb]
    (let [{:keys [graph missing]} (graph/pull @cache query (get-options m))
          #?@(:clj [response (promise)
                    cb (fn [graph]
                         (cb graph)
                         (deliver response graph))])]
      (if (empty? missing)
        (cb graph)
        (core/pull datasource missing (fn [{:keys [graph]}]
                                        (set-cache m [graph])
                                        (cb (:graph (graph/pull @cache query (get-options m)))))))
      #?(:clj response
         :cljs nil))))

(defn set
  [{:keys [datasource] :as m} pathmaps cb]
  (set-cache m pathmaps)
  (core/set datasource pathmaps (fn [{:keys [graph]}]
                                  ;; TODO: this could be partial -- but can't trust datasource paths yet (router)
                                  (set-cache m [graph])
                                  (cb (get-cache m (core/pathmap-paths pathmaps))))))

(defn call
  [{:keys [datasource] :as m} path args queries cb]
  (core/call datasource path args queries (fn [{:keys [graph invalidate]}]
                                            (invalidate-cache m invalidate)
                                            (set-cache m [graph])
                                            (cb graph))))

(defn prime
  [{:keys [cache datasource] :as m} query cb]
  (let [{:keys [missing]} (graph/pull @cache query (get-options m))]
    (if (seq missing)
      (core/pull datasource missing (fn [{:keys [graph]}]
                                      (set-cache* m [graph])
                                      (cb true)))
      (cb {:ready true}))))

(defn force
  [{:keys [cache datasource] :as m} query cb]
  (core/pull datasource query (fn [{:keys [graph]}]
                                (set-cache* m [graph])
                                (cb true))))

;; TODO: rest of falcor model interface as necessary
